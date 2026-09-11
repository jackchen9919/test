pipeline {
    agent any
    tools {
        gradle "Gradle7.1.1"
        maven "maven3.6.3"
        nodejs "NodeJS 16.14.1"
        nodejs "NodeJS 14.17.1"
    }
    //BRANCH_TAG参数已挪到外层dev/astrox.Jenkinsfile的properties()统一声明（这里原来的parameters{}块在load()子pipeline里不会注册成真正job参数）

    stages {
        stage('Check helm project code') {   //Solving the Problem of No Version Branch Packaging in the New Jenkinsfile
            steps {
                buildDescription "tag: ${params.BRANCH_TAG}"

                dir('astrox-helm-chart') {
                    checkout scm
                }
                script {
                    // TODO(部署前必须确认): 业务代码仓库若是私有仓库，换成Astrox在Jenkins里真实配置的GitHub凭据ID；
                    // 公开仓库（如当前测试用的jackchen9919/test）留空即可，Git插件对空credentialsId按匿名checkout处理
                    repo_credentials_id = ''
                }
                checkout([$class: 'GitSCM',
                          branches: [[name: "${params.BRANCH_TAG}"]],
                          doGenerateSubmoduleConfigurations: false,
                          extensions: [],
                          gitTool: 'Default',
                          submoduleCfg: [],
                          userRemoteConfigs: [[url: "${github_url}",credentialsId: repo_credentials_id,]]
                        ])
                script {
                    // Get the commit id
                    def commit_id = sh(script: 'git rev-parse --short=8 HEAD', returnStdout: true).trim()
                    // Set the commit id as an environment variable
                    env.commit_id = commit_id
                    //load()加载的声明式pipeline跑在独立node()/workspace里，这里设的env.*不会可靠带回外层scripted pipeline
                    //（Jenkins load()跨作用域的已知限制），改落临时文件，外层Update values.yaml阶段前读回来
                    writeFile file: "/tmp/${env.JOB_NAME.replaceAll('/', '_')}-${env.BUILD_NUMBER}-commit_id.txt", text: env.commit_id
                }
            }
        }

        stage('Build/Push') {
            steps {
                sh "cp ${chart_name}/Dockerfile_dir/${project_type}_Dockerfile ./Dockerfile"
                sh "rm -fr  /var/lib/jenkins/.m2/repository/org/codehaus/groovy/groovy/2.5.14"
                // packaging
                script {
                    def parts = BRANCH_TAG.split('/')
                    def tagAfterSlash = parts.length > 1 ? parts[1] : BRANCH_TAG
                    env.tagAfterSlash = tagAfterSlash

                    if ("${project_type}" == 'java8') {
                        sh "sed -i 's#{add_java_jar}#${add_java_jar}#g' Dockerfile"
                        sh "sed -i 's#{project}#${project.replace(':', '/')}#g' Dockerfile"
                        sh "sed -i 's/{docker_repository_url}/${docker_repository_url}/g' Dockerfile"
                        sh ". /etc/profile && ${gradle_ins}"
                    } else if ("${project_type}" == 'newexchange_java8') {
                        sh "sed -i 's#{project}#${project.replace(':', '/')}#g' Dockerfile"
                        sh "sed -i 's/{docker_repository_url}/${docker_repository_url}/g' Dockerfile"
                        sh "export JAVA_HOME=/srv/jenkins/jdk1.8.0_331 && export PATH=$JAVA_HOME/bin:$PATH && ${maven_ins}"
                    } else if ("${project_type}" == 'java17_maven') {
                        sh "sed -i 's#{project}#${project.replace(':', '/')}#g' Dockerfile"
                        sh "sed -i 's/{docker_repository_url}/${docker_repository_url}/g' Dockerfile"
                        sh ". /etc/profile && mvn -pl ${project.replace(':', '/')} -am package -DskipTests"
                    } else if ("${project_type}" == 'nginx') {
                        sh "cp ${chart_name}/customize_server.conf ./"
                        nodejs("${nodejs_version}") {
                            sh "${node_ins}"
                        }
                    } else if ("${project_type}" == 'go') {
                        sh "echo go"
                    } else if ("${project_type}" == 'nodejs') {
                        nodejs("${nodejs_version}") {
                            sh "${node_ins}"
                        }
                    } else if ("${project_type}" == 'nodejs_explore') {
                        sh "sed -i 's#{namespaces}#${namespaces}#g' Dockerfile"
                    } else if ("${project_type}" == 'python') {
                        sh "echo python"
                    } else {
                        error "Invalid project_type parameter value: ${project_type}"
                    }
                    // build and pushing —— dev自行构建，直接推到跟test共用的ECR路径（test_namespaces），保持跟部署路径消费的镜像地址一致
                    env.image_tag = "${tagAfterSlash}-${commit_id}-${BUILD_ID}"
                    writeFile file: "/tmp/${env.JOB_NAME.replaceAll('/', '_')}-${env.BUILD_NUMBER}-image_tag.txt", text: env.image_tag
                    // ECR仓库若不存在则动态创建；显式ECR登录
                    sh """
                        aws ecr describe-repositories --repository-names ${test_namespaces}/${app_name} --region ${aws_region} || \\
                            aws ecr create-repository --repository-name ${test_namespaces}/${app_name} --region ${aws_region} \\
                            --image-scanning-configuration scanOnPush=true --encryption-configuration encryptionType=AES256
                        aws ecr get-login-password --region ${aws_region} | docker login --username AWS --password-stdin ${docker_repository_url}
                        docker build -t ${image_url}:${image_tag} .
                        docker push ${image_url}:${image_tag}
                        //额外打latest标签并push：sit/uat"跳过构建直接部署"路径靠这个latest标签取真正最新的镜像，
                        //不再依赖ECR describe-images按imagePushedAt排序——同一份构建内容多次push会共用同一个digest，
                        //ECR只按digest记一条imagePushedAt，排序在这种场景下形同虚设，选出来的tag可能是任意一个历史tag而非真正最新构建
                        docker tag ${image_url}:${image_tag} ${image_url}:latest
                        docker push ${image_url}:latest
                    """
                }
            }
        }

        stage('Print image tag') {
            steps {
                echo "构建完成，镜像: ${image_url}:${image_tag}，接下来直接在本job部署"
            }
        }
    }
}
