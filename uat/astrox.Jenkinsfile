import groovy.json.JsonSlurper
//jenkins agent label
//项目主函数astrox.Jenkinsfile：只做部署，不再checkout业务代码/build/push，镜像由 test 构建job统一产出
//先用内置静态agent clone一次配置仓库，只为了读出该用哪个K8s cloud/哪个agent镜像
node {
    stage('Resolve deploy agent') {
        withCredentials([gitUsernamePassword(credentialsId: '9bb9a583-a510-4e45-91cc-bd4a3b9c307d', gitToolName: 'Default')]) {
            sh '''
                rm -fr astrox-helm-chart devops
                git clone https://github.com/jackchen9919/test.git astrox-helm-chart
            '''
        }
        def file = readFile("astrox-helm-chart/uat/setting.groovy")
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
  - name: deploy
    image: ${agent_image}
    command: ['cat']
    tty: true
"""
) {
    node(POD_LABEL) {
        container('deploy') {
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
                    //读取配置文件（部署相关字段，build相关字段在 test/setting.groovy）
                    def file = readFile("astrox-helm-chart/uat/setting.groovy")
                    def jsonSlurper = new JsonSlurper()
                    def code_info = jsonSlurper.parseText(file)

                    def key = code_info.get(micro_key)

                    //判断项目是否添加信息
                    if ( key == null ){
                    println "该项目未设置信息，请联系运维配置 uat/setting.groovy"
                    sh ("exit 1")
                    }
                    // 使用范围：jenkin pipeline使用 & value.yaml传递deployment使用
                    //公共参数
                    //env_tier固定等于本环境目录名（uat），用于定位chart源码目录/node taint；namespaces是真正的k8s namespace，可被服务级配置覆盖（同一环境下不同业务线namespace不同，如uat-product/uat-match）
                    env.env_tier = (code_info.private.env_tier).toString()
                    env.chart_name = (code_info.private.chart_name).toString()
                    env.docker_repository_url = (code_info.private.docker_repository_url).toString()
                    env.nfs_server = (code_info.private.nfs_server).toString()
                    env.KUBECONFIG = (code_info.private.KUBECONFIG).toString()
                    env.log_nfs_server = (code_info.private.log_nfs_server).toString()
                    env.lark_webhook_url = (code_info.private.lark_webhook_url).toString()

                    //项目参数
                    env.app_name = (code_info."${micro_key}".app_name).toString()
                    //project 也是 helm templates/_helpers.tpl、hpa.yaml 里 {project}.xxx 命名前缀，部署阶段仍要读
                    env.project = (code_info."${micro_key}".project).toString()
                    env.replicas = (code_info."${micro_key}".replicas).toString()
                    //镜像统一从 test 构建job产出的共享仓库路径拉取，跟本环境自己的k8s namespace解耦
                    //ECR路径段必须跟test构建时实际push的路径一致——不能假定字面量"test"，因为test/setting.groovy里这个服务的namespaces可能被覆盖成"test-product"这种业务线路径
                    //跨AWS账号提醒：本环境docker_repository_url已固定指向test/dev共用registry，需确认该ECR仓库策略已允许本账号跨账号pull（含ecr:DescribeImages，用于下面自动取最新tag）
                    def test_setting = jsonSlurper.parseText(readFile("astrox-helm-chart/test/setting.groovy"))
                    env.test_namespaces = (test_setting."${micro_key}".namespaces) ?: (test_setting.private.namespaces)
                    env.aws_region = (code_info.private.aws_region).toString()
                    env.image_url = "${docker_repository_url}/${test_namespaces}/${app_name}"
                    env.actuator_port = (code_info."${micro_key}".actuator_port).toString()
                    env.http_port = (code_info."${micro_key}".http_port).toString()
                    env.ingress_hosts = (code_info."${micro_key}".ingress_hosts).toString()
                    env.ingress_paths = (code_info."${micro_key}".ingress_paths).toString()
                    env.no_ingress = (code_info."${micro_key}".no_ingress).toString()
                    env.websocket_port = (code_info."${micro_key}".websocket_port).toString()

                    //skywalking_enabled跟project_type在deployment有一定关联
                    env.project_type = (code_info."${micro_key}".project_type).toString()
                    env.skywalking_enabled = (code_info."${micro_key}".skywalking_enabled).toString()

                    //判断提取
                    env.namespaces = (code_info."${micro_key}".namespaces) ?: (code_info.private.namespaces).toString()
                    env.kind_name = (code_info."${micro_key}".kind_name) ?: (code_info.private.kind_name)
                    env.limits_cpu = (code_info."${micro_key}".limits_cpu) ?: (code_info.private.limits_cpu)
                    env.limits_mem = (code_info."${micro_key}".limits_mem) ?: (code_info.private.limits_mem)
                    env.requests_cpu = (code_info."${micro_key}".requests_cpu) ?: (code_info.private.requests_cpu)
                    env.requests_mem = (code_info."${micro_key}".requests_mem) ?: (code_info.private.requests_mem)
                    env.node_select = (code_info."${micro_key}".node_select) ?: (code_info.private.node_select)
                    env.min_replicas = (code_info."${micro_key}".min_replicas) ?: (code_info.private.min_replicas)
                    env.max_replicas = (code_info."${micro_key}".max_replicas) ?: (code_info.private.max_replicas)
                }
                //加载pipeline流程（只负责接收IMAGE_TAG参数，不再checkout+build+push）
                load("astrox-helm-chart/uat/pipline.groovy")
                // value.yaml deliver deployment full
                stage('Update values.yaml') {
                    sh '''
                        rm -rf ${chart_name}/${env_tier}/templates
                        mkdir -p ${chart_name}/${env_tier}/templates
                        cp ${chart_name}/chart_templates/template.Chart.yaml ${chart_name}/${env_tier}/
                        cp ${chart_name}/chart_templates/template_*.values.yaml ${chart_name}/${env_tier}/
                        cp ${chart_name}/chart_templates/templates/* ${chart_name}/${env_tier}/templates/
                        cd ${chart_name}/${env_tier}
                        envsubst < template_${project_type}.values.yaml > values.yaml
                        if [ "${websocket_port}" = 'null' ];then sed -i '/websocket/{N;N;d;}' values.yaml;fi
                        envsubst < template.Chart.yaml > Chart.yaml && rm -fr template_*
                        cd templates
                        sed -i 's/{project}/${project}/g' _helpers.tpl
                        sed -i 's/{project}/${project}/g' hpa.yaml
                        if [ ${no_ingress} = 'true' ];then rm -fr apisixroute.yaml;fi
                    '''
                }

                // kubeconfig 配置文件是jenkins托管的，在全局凭据里面修改
                stage('ofc helm upgrade') {
                    sh '''
                        helm list -n ${namespaces}|grep ${app_name} &> /dev/null
                        helm upgrade ${app_name} --install -n ${namespaces} ./${chart_name}/${env_tier}
                    '''
                }


                stage('update helm chart') {
                    sh '''
                        timeout 300 kubectl --kubeconfig ${KUBECONFIG} rollout status ${kind_name} -n ${namespaces} ${app_name} || status=false
                        if [ "$status" = "false" ];then
                            echo "`date +%F-%M:%S` 发布失败,打印失败容器日志."
                            timeout 120 kubetail -n ${namespaces} -l appname=${app_name}
                            echo "`date +%F-%M:%S` 执行回滚操作."
                            kubectl --kubeconfig ${KUBECONFIG} rollout -n ${namespaces} undo deployment ${app_name}
                        else
                            echo "`date +%F-%M:%S` 发布成功"
                        fi
                    '''
                }

                sh "curl -s -X POST -H 'Content-Type: application/json' -d '{\"msg_type\":\"text\",\"content\":{\"text\":\"[${env.JOB_BASE_NAME}] uat部署成功 tag=${env.image_tag}\"}}' ${env.lark_webhook_url} || true"
            } catch (e) {
                sh "curl -s -X POST -H 'Content-Type: application/json' -d '{\"msg_type\":\"text\",\"content\":{\"text\":\"[${env.JOB_BASE_NAME}] uat部署失败\"}}' ${env.lark_webhook_url} || true"
                throw e
            }
        }
    }
}
