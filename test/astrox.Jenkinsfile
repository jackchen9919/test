import groovy.json.JsonSlurper
//test环境：构建+部署合并成同一个job（原来是test/astrox.Jenkinsfile构建job + test/deploy.Jenkinsfile部署job两个独立job，现在合并）
//是否走真实的checkout+build+push由DO_BUILD参数控制：勾选=先构建新镜像再部署（走test/pipline.groovy）；不勾选=跳过构建直接部署，用SPECIFY_TAG/IMAGE_TAG或自动取ECR最新tag（走test/deploy_pipline.groovy）
//结构照抄prod/astrox.Jenkinsfile的"构建+部署在同一个job"模式
properties([
    parameters([
        booleanParam(name: 'DO_BUILD', defaultValue: true, description: '是否先构建新镜像。勾选=checkout业务代码并build+push新镜像再部署；不勾选=跳过构建直接部署（用SPECIFY_TAG/IMAGE_TAG，或自动取ECR最新tag）——等价于以前独立的test部署job')
    ])
])

//jenkins-sg.hichain.me 没装Kubernetes插件/没配置任何Cloud，只有一个静态节点（标签ofc-hk-bastion），agent直接跑在这个静态节点上，不再用K8s动态pod agent
//该节点没装buildah，构建工具已改用docker（见test/pipline.groovy），节点上的jenkins用户已在docker组里
node('ofc-hk-bastion') {
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
                    def file = readFile("astrox-helm-chart/test/setting.groovy")
                    def jsonSlurper = new JsonSlurper()
                    def code_info = jsonSlurper.parseText(file)

                    def key = code_info.get(micro_key)

                    //判断项目是否添加信息
                    if ( key == null ){
                    println "该项目未设置信息，请联系运维配置 test/setting.groovy"
                    sh ("exit 1")
                    }
                    // 使用范围：jenkin pipeline使用 & value.yaml传递deployment使用
                    //公共参数
                    env.env_tier = (code_info.private.env_tier).toString()
                    env.chart_name = (code_info.private.chart_name).toString()
                    env.node_select = (code_info.private.node_select).toString()
                    env.docker_repository_url = (code_info.private.docker_repository_url).toString()
                    env.aws_region = (code_info.private.aws_region).toString()
                    env.kubeconfig_credential_id = (code_info.private.kubeconfig_credential_id).toString()

                    //build相关参数（DO_BUILD勾选时才会用到，但不勾选时也一起读出来无妨）
                    env.github_url = (code_info."${micro_key}".github_url).toString()
                    env.node_ins = (code_info."${micro_key}".node_ins).toString()
                    env.nodejs_version = (code_info."${micro_key}".nodejs_version).toString()

                    //项目/部署参数
                    env.app_name = (code_info."${micro_key}".app_name).toString()
                    //project 也是 helm templates/_helpers.tpl、hpa.yaml 里 {project}.xxx 命名前缀
                    env.project = (code_info."${micro_key}".project).toString()
                    env.replicas = (code_info."${micro_key}".replicas).toString()
                    env.namespaces = (code_info."${micro_key}".namespaces) ?: (code_info.private.namespaces).toString()
                    env.image_url = "${docker_repository_url}/${namespaces}/${app_name}"
                    env.actuator_port = (code_info."${micro_key}".actuator_port).toString()
                    env.http_port = (code_info."${micro_key}".http_port).toString()
                    env.ingress_hosts = (code_info."${micro_key}".ingress_hosts).toString()
                    env.ingress_paths = (code_info."${micro_key}".ingress_paths).toString()
                    env.no_ingress = (code_info."${micro_key}".no_ingress) ?: (code_info.private.no_ingress)
                    env.websocket_port = (code_info."${micro_key}".websocket_port).toString()

                    //skywalking_enabled跟project_type在deployment有一定关联
                    env.project_type = (code_info."${micro_key}".project_type).toString()
                    env.skywalking_enabled = (code_info."${micro_key}".skywalking_enabled).toString()

                    //判断提取
                    env.kind_name = (code_info."${micro_key}".kind_name) ?: (code_info.private.kind_name)
                    env.limits_cpu = (code_info."${micro_key}".limits_cpu) ?: (code_info.private.limits_cpu)
                    env.limits_mem = (code_info."${micro_key}".limits_mem) ?: (code_info.private.limits_mem)
                    env.requests_cpu = (code_info."${micro_key}".requests_cpu) ?: (code_info.private.requests_cpu)
                    env.requests_mem = (code_info."${micro_key}".requests_mem) ?: (code_info.private.requests_mem)
                    env.nfs_server = (code_info."${micro_key}".nfs_server) ?: (code_info.private.nfs_server)
                    env.log_nfs_server = (code_info."${micro_key}".log_nfs_server) ?: (code_info.private.log_nfs_server)
                    env.min_replicas = (code_info."${micro_key}".min_replicas) ?: (code_info.private.min_replicas)
                    env.max_replicas = (code_info."${micro_key}".max_replicas) ?: (code_info.private.max_replicas)
                }

                //DO_BUILD是"按钮"：勾选=真的checkout+build+push（test/pipline.groovy），不勾选=跳过构建直接部署（test/deploy_pipline.groovy，SPECIFY_TAG/IMAGE_TAG或自动取ECR最新tag）
                if (params.DO_BUILD) {
                    load("astrox-helm-chart/test/pipline.groovy")
                } else {
                    load("astrox-helm-chart/test/deploy_pipline.groovy")
                }

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
                        echo "----- rendered values.yaml (debug) -----"
                        cat -A values.yaml
                        echo "----- end values.yaml -----"
                        if [ "${websocket_port}" = 'null' ];then sed -i '/websocket/{N;N;d;}' values.yaml;fi
                        envsubst < template.Chart.yaml > Chart.yaml && rm -fr template_*
                        cd templates
                        sed -i 's/{project}/${project}/g' _helpers.tpl
                        sed -i 's/{project}/${project}/g' hpa.yaml
                        if [ ${no_ingress} = 'true' ];then rm -fr apisixroute.yaml;fi
                    '''
                }

                // kubeconfig 文件不是agent镜像里现成的，走Jenkins "Secret file" 凭据注入：
                // withCredentials把凭据内容落到一个临时文件，赋给KUBECONFIG环境变量——helm/kubectl都会自动读这个环境变量，不用再显式传--kubeconfig
                stage('ofc helm upgrade') {
                    withCredentials([file(credentialsId: env.kubeconfig_credential_id, variable: 'KUBECONFIG')]) {
                        sh '''
                            echo "target cluster: $(kubectl config current-context)"
                            helm list -n ${namespaces}|grep ${app_name} &> /dev/null
                            helm upgrade ${app_name} --install -n ${namespaces} ./${chart_name}/${env_tier}
                        '''
                    }
                }

                stage('update helm chart') {
                    withCredentials([file(credentialsId: env.kubeconfig_credential_id, variable: 'KUBECONFIG')]) {
                        sh '''
                            timeout 380 kubectl rollout status ${kind_name} -n ${namespaces} ${app_name} || status=false
                            if [ "$status" = "false" ];then
                                echo "`date +%F-%M:%S` 发布失败,打印失败容器日志."
                                timeout 120 kubetail -n ${namespaces} -l appname=${app_name}
                                echo "`date +%F-%M:%S` 执行回滚操作."
                                kubectl rollout -n ${namespaces} undo deployment ${app_name}
                            else
                                echo "`date +%F-%M:%S` 发布成功"
                            fi
                        '''
                    }
                }
            } catch (e) {
                throw e
            }
}
