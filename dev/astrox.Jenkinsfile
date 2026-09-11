import groovy.json.JsonSlurper
//jenkins agent label
//项目主函数astrox.Jenkinsfile：默认只做部署（镜像由test构建job统一产出）；DO_BUILD勾选时也支持脱离test、自行指定分支checkout+build+push再部署
//jenkins-sg.hichain.me 没装Kubernetes插件/没配置任何Cloud，只有一个静态节点（标签ofc-hk-bastion），该节点已确认有helm/kubectl/aws-cli/envsubst，agent直接跑在这个静态节点上，不再用K8s动态pod agent
properties([
    parameters([
        booleanParam(name: 'DO_BUILD', defaultValue: false, description: '默认不勾选=部署test已构建好的镜像(用下面SPECIFY_TAG/IMAGE_TAG，或自动取ECR最新tag)；勾选=从指定分支重新checkout+build+push新镜像再部署，用于dev想脱离test单独验证某个分支')
    ])
])
node('ofc-hk-bastion') {
            try {
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
                    def file = readFile("astrox-helm-chart/dev/setting.groovy")
                    def jsonSlurper = new JsonSlurper()
                    def code_info = jsonSlurper.parseText(file)

                    def key = code_info.get(micro_key)

                    //判断项目是否添加信息
                    if ( key == null ){
                    println "该项目未设置信息，请联系运维配置 dev/setting.groovy"
                    sh ("exit 1")
                    }
                    // 使用范围：jenkin pipeline使用 & value.yaml传递deployment使用
                    //公共参数
                    //env_tier固定等于本环境目录名（dev），用于定位chart源码目录/node taint；namespaces是真正的k8s namespace，可被服务级配置覆盖（同一环境下不同业务线namespace不同，如dev-product/dev-funds）
                    env.env_tier = (code_info.private.env_tier).toString()
                    env.chart_name = (code_info.private.chart_name).toString()
                    env.node_select = (code_info.private.node_select).toString()
                    env.docker_repository_url = (code_info.private.docker_repository_url).toString()
                    env.kubeconfig_credential_id = (code_info.private.kubeconfig_credential_id).toString()

                    //项目参数
                    env.app_name = (code_info."${micro_key}".app_name).toString()
                    //project 也是 helm templates/_helpers.tpl、hpa.yaml 里 {project}.xxx 命名前缀，部署阶段仍要读
                    env.project = (code_info."${micro_key}".project).toString()
                    env.replicas = (code_info."${micro_key}".replicas).toString()
                    //镜像统一从 test 构建job产出的共享仓库路径拉取，跟本环境自己的k8s namespace解耦
                    //ECR路径段必须跟test构建时实际push的路径一致——不能假定字面量"test"，因为test/setting.groovy里这个服务的namespaces可能被覆盖成"test-product"这种业务线路径
                    def test_setting = jsonSlurper.parseText(readFile("astrox-helm-chart/test/setting.groovy"))
                    env.test_namespaces = (test_setting."${micro_key}".namespaces) ?: (test_setting.private.namespaces)
                    //build相关参数（DO_BUILD勾选时才会用到），跟test共用一份配置，不在dev/setting.groovy里重复维护
                    env.github_url = (test_setting."${micro_key}".github_url).toString()
                    env.node_ins = (test_setting."${micro_key}".node_ins).toString()
                    env.nodejs_version = (test_setting."${micro_key}".nodejs_version).toString()
                    env.aws_region = (code_info.private.aws_region).toString()
                    env.image_url = "${docker_repository_url}/${test_namespaces}/${app_name}"
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
                    env.namespaces = (code_info."${micro_key}".namespaces) ?: (code_info.private.namespaces).toString()
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

                //DO_BUILD是"按钮"：勾选=真的checkout+build+push（dev/pipline_build.groovy），不勾选=跳过构建直接部署test已构建的镜像（dev/pipline.groovy，SPECIFY_TAG/IMAGE_TAG或自动取ECR最新tag）
                if (params.DO_BUILD) {
                    load("astrox-helm-chart/dev/pipline_build.groovy")
                } else {
                    load("astrox-helm-chart/dev/pipline.groovy")
                }

                //load()加载的声明式sub-pipeline跑在独立node()/workspace里，里面设的env.image_tag(/commit_id)不会可靠带回这一层scripted pipeline
                //（Jenkins load()跨作用域的已知限制），统一从sub-pipeline落的临时文件读回来，同一台静态节点上文件系统是共享的
                stage('Read build outputs') {
                    def image_tag_file = "/tmp/${env.JOB_NAME.replaceAll('/', '_')}-${env.BUILD_NUMBER}-image_tag.txt"
                    env.image_tag = readFile(image_tag_file).trim()
                    sh "rm -f ${image_tag_file}"
                    if (params.DO_BUILD) {
                        def commit_id_file = "/tmp/${env.JOB_NAME.replaceAll('/', '_')}-${env.BUILD_NUMBER}-commit_id.txt"
                        env.commit_id = readFile(commit_id_file).trim()
                        sh "rm -f ${commit_id_file}"
                    }
                }

                // value.yaml deliver deployment full
                stage('Update values.yaml') {
                    //实测确认（test环境已验证）：这台节点上"image_tag"/"commit_id"这两个变量名，无论env.X赋值还是withEnv显式注入，到shell里都会被别处(节点级/全局环境变量配置)覆盖成空值——
                    //是变量名撞车，不是传值机制的问题。规避方式：shell侧只用不会撞车的变量名接住真实值，envsubst前先用sed把模板里的${image_tag}/${commit_id}占位符直接替换成字面值。
                    withEnv(["IMAGE_TAG_VALUE=${env.image_tag}", "COMMIT_ID_VALUE=${env.commit_id ?: ''}"]) {
                        sh '''
                            rm -rf ${chart_name}/${env_tier}/templates
                            mkdir -p ${chart_name}/${env_tier}/templates
                            cp ${chart_name}/chart_templates/template.Chart.yaml ${chart_name}/${env_tier}/
                            cp ${chart_name}/chart_templates/template_*.values.yaml ${chart_name}/${env_tier}/
                            cp ${chart_name}/chart_templates/templates/* ${chart_name}/${env_tier}/templates/
                            cd ${chart_name}/${env_tier}
                            sed -i "s|\\${image_tag}|${IMAGE_TAG_VALUE}|g; s|\\${commit_id}|${COMMIT_ID_VALUE}|g" template_${project_type}.values.yaml template.Chart.yaml
                            envsubst < template_${project_type}.values.yaml > values.yaml
                            if [ "${websocket_port}" = 'null' ];then sed -i '/websocket/{N;N;d;}' values.yaml;fi
                            envsubst < template.Chart.yaml > Chart.yaml && rm -fr template_*
                            cd templates
                            sed -i 's/{project}/${project}/g' _helpers.tpl
                            sed -i 's/{project}/${project}/g' hpa.yaml
                            if [ ${no_ingress} = 'true' ];then rm -fr apisixroute.yaml;fi
                        '''
                    }
                }

                // kubeconfig 文件不是agent镜像里现成的，走Jenkins "Secret file" 凭据注入：
                // withCredentials把凭据内容落到一个临时文件，赋给KUBECONFIG环境变量——helm/kubectl都会自动读这个环境变量，不用再显式传--kubeconfig
                stage('ofc helm upgrade') {
                    //when {
                    //    expression { env.product_line == "dev" } //设置先对条件进行判断，符合预期才进入steps
                    //}
                    withCredentials([file(credentialsId: env.kubeconfig_credential_id, variable: 'KUBECONFIG')]) {
                        sh '''
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
