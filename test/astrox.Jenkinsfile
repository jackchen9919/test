import groovy.json.JsonSlurper
//jenkins agent label
//test构建job：只负责给dev/uat产出一份共享镜像，不做helm部署
//先用内置静态agent（不需要JDK/Maven）clone一次配置仓库，只为了读出该用哪个K8s cloud/哪个agent镜像，
//读完这个静态agent就不再使用，真正的checkout+build在下面podTemplate起的动态pod里重新做一次
node {
    stage('Resolve build agent') {
        withCredentials([gitUsernamePassword(credentialsId: '9bb9a583-a510-4e45-91cc-bd4a3b9c307d', gitToolName: 'Default')]) {
            sh '''
                rm -fr astrox-helm-chart devops
                git clone https://github.com/jackchen9919/test.git astrox-helm-chart
            '''
        }
        def micro_key = env.JOB_BASE_NAME
        def file = readFile("astrox-helm-chart/test/setting.groovy")
        def jsonSlurper = new JsonSlurper()
        def code_info = jsonSlurper.parseText(file)
        env.jenkins_cloud = (code_info.private.jenkins_cloud).toString()
        env.agent_image = (code_info.private.agent_image).toString()
    }
}

podTemplate(cloud: "${jenkins_cloud}", yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: build
    image: ${agent_image}
    command: ['cat']
    tty: true
"""
) {
    node(POD_LABEL) {
        container('build') {
            try {
                //此方案用来解决checkout scm helm拉取分支报错问题
                stage('clone helm chart') {
                    withCredentials([gitUsernamePassword(credentialsId: '9bb9a583-a510-4e45-91cc-bd4a3b9c307d', gitToolName: 'Default')]) {
                        sh '''
                            rm -fr astrox-helm-chart devops
                            git clone https://github.com/jackchen9919/test.git astrox-helm-chart
                        '''
                    }
                }
                stage('Check info') {
                    def micro_key = env.JOB_BASE_NAME
                    //读取配置文件
                    def file = readFile("astrox-helm-chart/test/setting.groovy")
                    def jsonSlurper = new JsonSlurper()
                    def code_info = jsonSlurper.parseText(file)

                    def key = code_info.get(micro_key)
                    env.maven_path = "/var/lib/jenkins/tools/hudson.tasks.Maven_MavenInstallation/maven3.6.3"
                    env.nodejs_path = "/var/lib/jenkins/tools/jenkins.plugins.nodejs.tools.NodeJSInstallation"
                    env.jradle_path = "/var/lib/jenkins/tools/hudson.plugins.gradle.GradleInstallation/Gradle7.1.1"

                    //判断项目是否添加信息
                    if ( key == null ){
                    println "该项目未设置信息，请联系运维配置 test/setting.groovy"
                    sh ("exit 1")
                    }
                    //公共参数（构建相关，不含部署字段）
                    env.chart_name = (code_info.private.chart_name).toString()
                    env.docker_repository_url = (code_info.private.docker_repository_url).toString()
                    env.aws_region = (code_info.private.aws_region).toString()
                    env.lark_webhook_url = (code_info.private.lark_webhook_url).toString()
                    //服务级可覆盖，缺省沿用环境默认值（同一环境下不同业务线ECR路径/namespace可以不同，如test-product/test-funds）
                    env.namespaces = (code_info."${micro_key}".namespaces) ?: (code_info.private.namespaces)

                    //项目参数（构建相关）
                    env.app_name = (code_info."${micro_key}".app_name).toString()
                    env.nodejs_version = (code_info."${micro_key}".nodejs_version).toString()
                    env.project = (code_info."${micro_key}".project).toString()
                    env.github_url = (code_info."${micro_key}".github_url).toString()
                    env.image_url = "${docker_repository_url}/${namespaces}/${app_name}"
                    env.node_ins = (code_info."${micro_key}".node_ins).toString()
                    env.maven_ins = (code_info."${micro_key}".maven_ins).toString()
                    env.project_type = (code_info."${micro_key}".project_type).toString()

                    //判断提取
                    env.gradle_ins = (code_info."${micro_key}".gradle_ins) ?: (code_info.private.gradle_ins)
                    env.add_java_jar = (code_info."${micro_key}".add_java_jar) ?: (code_info.private.add_java_jar)
                }

                //加载pipeline流程（checkout业务代码 + build + push）
                load("astrox-helm-chart/test/pipline.groovy")

                sh "curl -s -X POST -H 'Content-Type: application/json' -d '{\"msg_type\":\"text\",\"content\":{\"text\":\"[${env.JOB_BASE_NAME}] test构建成功 tag=${env.image_tag}\"}}' ${env.lark_webhook_url} || true"
            } catch (e) {
                sh "curl -s -X POST -H 'Content-Type: application/json' -d '{\"msg_type\":\"text\",\"content\":{\"text\":\"[${env.JOB_BASE_NAME}] test构建失败\"}}' ${env.lark_webhook_url} || true"
                throw e
            }
        }
    }
}
