pipeline {
    agent any
    tools {
        gradle "Gradle7.1.1"
        maven "maven3.6.3"
        nodejs "NodeJS 16.14.1"
        nodejs "NodeJS 14.17.1"
    }
    parameters {
        gitParameter name: 'BRANCH_TAG',
                     type: 'PT_BRANCH_TAG',
                     branchFilter: 'origin/(.*)',
                     defaultValue: 'dev',
                     selectedValue: 'DEFAULT',
                     sortMode: 'DESCENDING_SMART',
                     quickFilterEnabled: 'True',
                     description: '',
                     useRepository: "${github_url}"
    }

    stages {
        stage('Check helm project code') {   //Solving the Problem of No Version Branch Packaging in the New Jenkinsfile
            steps {
                // use name of the patchset as the build name
                // buildName "${BUILD_DISPLAY_NAME} started by ${BUILD_USER_ID}"
                buildDescription "tag: ${params.BRANCH_TAG}"

                // sh "rm -fr ${chart_name}"
                dir('astrox-helm-chart') {
                    checkout scm
                }
                script {
                    // TODO(部署前必须确认): 换成Astrox在Jenkins里真实配置的GitHub凭据ID，不要沿用模板里的占位值
                    repo_credentials_id = 'GITHUB_CREDENTIALS_ID_PLACEHOLDER'
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
                    // build and pushing — 这个tag就是dev/uat部署job要填的IMAGE_TAG
                    env.image_tag = "${tagAfterSlash}-${commit_id}-${BUILD_ID}"
                    // ECR仓库若不存在则动态创建；显式ECR登录（buildah不像docker那样有daemon级凭据缓存，每次都要登录）
                    sh """
                        aws ecr describe-repositories --repository-names ${namespaces}/${app_name} --region ${aws_region} || \\
                            aws ecr create-repository --repository-name ${namespaces}/${app_name} --region ${aws_region} \\
                            --image-scanning-configuration scanOnPush=true --encryption-configuration encryptionType=AES256
                        aws ecr get-login-password --region ${aws_region} | buildah login --username AWS --password-stdin ${docker_repository_url}
                        buildah --storage-driver=vfs bud -t ${image_url}:${image_tag} .
                        buildah --storage-driver=vfs push ${image_url}:${image_tag}
                    """
                }
            }
        }

        stage('Print image tag') {
            steps {
                echo "构建完成，镜像: ${image_url}:${image_tag}"
                echo "去 dev/uat 对应部署job里，把 IMAGE_TAG 参数填成: ${image_tag}"
            }
        }
    }
}
