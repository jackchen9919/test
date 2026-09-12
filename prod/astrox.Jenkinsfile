import groovy.json.JsonSlurper
//jenkins agent label
//项目主函数astrox.Jenkinsfile，读取setting.groovy配置信息，加载公共jenkins-pipline
//prod是唯一"build+deploy在同一个job里"的环境（跟test/dev/uat不同），所以静态节点上必须同时具备构建工具链（JDK17/Maven/docker）和部署工具链（helm/kubectl/awscli）
//jenkins-sg.hichain.me 没装Kubernetes插件/没配置任何Cloud，只有一个静态节点（标签ofc-hk-bastion），agent直接跑在这个静态节点上，不再用K8s动态pod agent
//该节点没装buildah，构建工具已改用docker（见prod/pipline.groovy），节点上的jenkins用户已在docker组里
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
                    //读取配置文件
                    def file = readFile("astrox-helm-chart/prod/setting.groovy")
                    def jsonSlurper = new JsonSlurper()
                    def code_info = jsonSlurper.parseText(file)

                    def key = code_info.get(micro_key)
                    env.ec2 = (code_info."${micro_key}".ec2).toString()
                    env.maven_path = "/var/lib/jenkins/tools/hudson.tasks.Maven_MavenInstallation/maven3.6.3"
                    //NodeJS_14.17.1
                    env.nodejs_path = "/var/lib/jenkins/tools/jenkins.plugins.nodejs.tools.NodeJSInstallation"
                    env.jradle_path = "/var/lib/jenkins/tools/hudson.plugins.gradle.GradleInstallation/Gradle7.1.1"

                    //判断项目是否添加信息
                    if ( key == null ){
                    println "该项目未设置信息，请联系运维配置 jenkins_info.groovy"
                    sh ("exit 1")
                    }
                    // 使用范围：jenkin pipeline使用 & value.yaml传递deployment使用
                    //公共参数
                    //env_tier固定等于本环境目录名（prod），用于定位chart源码目录/node taint；namespaces是真正的k8s namespace，可被服务级配置覆盖
                    env.env_tier = (code_info.private.env_tier).toString()
                    env.chart_name = (code_info.private.chart_name).toString()
                    //env.node_select = (code_info.private.node_select).toString()
                    env.docker_repository_url = (code_info.private.docker_repository_url).toString()
                    env.aws_region = (code_info.private.aws_region).toString()
                    env.nfs_server = (code_info.private.nfs_server).toString()
                    env.KUBECONFIG = (code_info.private.KUBECONFIG).toString()
                    env.log_nfs_server = (code_info.private.log_nfs_server).toString()
                    //服务级可覆盖，缺省沿用环境默认值
                    env.namespaces = (code_info."${micro_key}".namespaces) ?: (code_info.private.namespaces)

                    //项目参数
                    env.app_name = (code_info."${micro_key}".app_name).toString()
                    env.nodejs_version = (code_info."${micro_key}".nodejs_version).toString()
                    env.project = (code_info."${micro_key}".project).toString()
                    env.github_url = (code_info."${micro_key}".github_url).toString()
                    env.replicas = (code_info."${micro_key}".replicas).toString()
                    //prod独立build+push，用自己账号的registry+自己的k8s namespace
                    env.image_url = "${docker_repository_url}/${namespaces}/${app_name}"
                    env.actuator_port = (code_info."${micro_key}".actuator_port).toString()
                    env.http_port = (code_info."${micro_key}".http_port).toString()
                    env.ingress_hosts = (code_info."${micro_key}".ingress_hosts).toString()
                    env.ingress_paths = (code_info."${micro_key}".ingress_paths).toString()
                    env.no_ingress = (code_info."${micro_key}".no_ingress) ?: (code_info.private.no_ingress)
                    env.node_ins = (code_info."${micro_key}".node_ins).toString()
                    env.maven_ins = (code_info."${micro_key}".maven_ins).toString()
                    //websocket_port/skywalking_enabled大多数服务都是"没有/不开"，per-job不填时吃private默认值(null/false)，
                    //只有真用websocket或者想开skywalking的服务才需要在per-job块里显式覆盖
                    env.websocket_port = (code_info."${micro_key}".websocket_port) ?: (code_info.private.websocket_port)

                    //skywalking_enabled跟project_type在deployment有一定关联
                    env.project_type = (code_info."${micro_key}".project_type).toString()
                    env.skywalking_enabled = (code_info."${micro_key}".skywalking_enabled) ?: (code_info.private.skywalking_enabled)

                    //判断提取
                    env.kind_name = (code_info."${micro_key}".kind_name) ?: (code_info.private.kind_name)
                    env.gradle_ins = (code_info."${micro_key}".gradle_ins) ?: (code_info.private.gradle_ins)
                    env.add_java_jar = (code_info."${micro_key}".add_java_jar) ?: (code_info.private.add_java_jar)

                    env.limits_cpu = (code_info."${micro_key}".limits_cpu) ?: (code_info.private.limits_cpu)
                    env.limits_mem = (code_info."${micro_key}".limits_mem) ?: (code_info.private.limits_mem)
                    env.requests_cpu = (code_info."${micro_key}".requests_cpu) ?: (code_info.private.requests_cpu)
                    env.requests_mem = (code_info."${micro_key}".requests_mem) ?: (code_info.private.requests_mem)
                    env.node_select = (code_info."${micro_key}".node_select) ?: (code_info.private.node_select)
                    env.autoscaling_enable = (code_info."${micro_key}".autoscaling_enable) ?: (code_info.private.autoscaling_enable)
                    env.min_replicas = (code_info."${micro_key}".min_replicas) ?: (code_info.private.min_replicas)
                    env.max_replicas = (code_info."${micro_key}".max_replicas) ?: (code_info.private.max_replicas)

                }
                //加载pipeline流程
                load("astrox-helm-chart/prod/pipline.groovy")
                // reading commit id，value.yaml deliver deployment full
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
                    //when {
                    //    expression { env.product_line == "dev" } //设置先对条件进行判断，符合预期才进入steps
                    //}
                    sh '''
                        helm list -n ${namespaces}|grep ${app_name} &> /dev/null
                        helm upgrade ${app_name} --install -n ${namespaces} ./${chart_name}/${env_tier} --force
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
            } catch (e) {
                throw e
            }
}
