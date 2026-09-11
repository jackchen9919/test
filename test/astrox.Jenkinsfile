//JsonSlurper()/JsonSlurperClassic()都踩过坑：前者返回的LazyMap不可序列化、Groovy CPS在load()等step前
//checkpoint时报NotSerializableException；后者的构造函数未在这个Jenkins实例的脚本沙箱白名单里，报RejectedAccessException。
//改用pipeline-utility-steps插件自带的readJSON——这是正经Jenkins step而非裸Groovy对象，天然沙箱安全、返回值天然可序列化。
//test环境：构建+部署合并成同一个job（原来是test/astrox.Jenkinsfile构建job + test/deploy.Jenkinsfile部署job两个独立job，现在合并）
//是否走真实的checkout+build+push由DO_BUILD参数控制：勾选=先构建新镜像再部署（走test/pipline.groovy）；不勾选=跳过构建直接部署，用IMAGE_TAG或自动取ECR最新tag（走test/deploy_pipline.groovy）
//结构照抄prod/astrox.Jenkinsfile的"构建+部署在同一个job"模式
//BRANCH_TAG/IMAGE_TAG以前分别声明在test/pipline.groovy、test/deploy_pipline.groovy里（load()加载的子pipeline），
//declarative的parameters{}块在load()子pipeline里不会注册成真正的job级参数（UI选不到，一直显示不出分支下拉框）——统一收到这里的properties()才是唯一生效的参数声明，
//子文件里原来的parameters{}块已删除，避免两边各自调properties()互相覆盖、参数忽隐忽现
properties([
    parameters([
        booleanParam(name: 'DO_BUILD', defaultValue: false, description: '是否先构建新镜像。勾选=用下面选的分支checkout业务代码并build+push新镜像再部署；不勾选=跳过构建直接部署（用IMAGE_TAG，或自动取ECR最新tag）——等价于以前独立的test部署job'),
        //改用Active Choices的CascadeChoiceParameter（原来的gitParameter不支持"随DO_BUILD勾选状态变化"这种联动）：
        //DO_BUILD勾选时下拉框显示真实分支列表（main排第一，即默认值）；不勾选时下拉框只有一个占位选项，避免误选到真实分支却根本不生效
        //github_url要到"Check info"阶段checkout后从setting.groovy读才有，这里的脚本渲染发生在checkout之前，只能先固定写死repo地址；
        //目前3个环境的业务仓库都是同一个repo，以后如果换repo，这里要跟着手动改，不会随setting.groovy自动联动
        [$class: 'CascadeChoiceParameter',
         name: 'BRANCH_TAG',
         description: '仅在勾选DO_BUILD时生效，选要构建的分支；不勾选DO_BUILD时只有一个占位选项，选它不生效',
         randomName: 'choice-parameter-branch-tag',
         choiceType: 'PT_SINGLE_SELECT',
         referencedParameters: 'DO_BUILD',
         filterable: false,
         filterLength: 1,
         script: [$class: 'GroovyScript',
             script: [classpath: [], sandbox: false, script: '''
                 if (DO_BUILD.toString() == "true") {
                     def branches = ["main"]
                     try {
                         def out = new StringBuilder(), err = new StringBuilder()
                         def proc = ["git", "ls-remote", "--heads", "https://github.com/jackchen9919/test.git"].execute()
                         proc.consumeProcessOutput(out, err)
                         proc.waitForOrKill(15000)
                         out.toString().eachLine { line ->
                             def idx = line.indexOf("refs/heads/")
                             if (idx >= 0) {
                                 def b = line.substring(idx + "refs/heads/".length()).trim()
                                 if (b && b != "main") { branches << b }
                             }
                         }
                     } catch (Throwable t) {
                         // 网络异常时至少还有main可选，不让下拉框整个报错
                     }
                     return branches
                 } else {
                     return ["不生效(未勾选DO_BUILD)"]
                 }
             '''],
             fallbackScript: [classpath: [], sandbox: false, script: 'return ["main"]']
         ]
        ],
        string(name: 'IMAGE_TAG', defaultValue: '', description: '仅在不勾选DO_BUILD时生效。留空=自动取ECR里该服务最新一次push的tag（推荐，日常部署不用管这个）；填了=部署这个指定的历史tag（用于回滚）')
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
                    //job已从文件夹里的apisix-route-test改成扁平命名sit-java-apisix-route，查表用的key要剥掉环境前缀
                    def micro_key = env.JOB_BASE_NAME.replaceFirst(/^sit-java-/, '')
                    def file = readFile("astrox-helm-chart/test/setting.groovy")
                    def code_info = readJSON text: file

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

                //DO_BUILD是"按钮"：勾选=真的checkout+build+push（test/pipline.groovy），不勾选=跳过构建直接部署（test/deploy_pipline.groovy，IMAGE_TAG或自动取ECR最新tag）
                if (params.DO_BUILD) {
                    load("astrox-helm-chart/test/pipline.groovy")
                } else {
                    load("astrox-helm-chart/test/deploy_pipline.groovy")
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
                    //实测确认：这台节点上"image_tag"/"commit_id"这两个变量名，无论env.X赋值还是withEnv显式注入，到shell里都会被别处(节点级/全局环境变量配置，profile脚本里未找到)覆盖成空值——
                    //是变量名撞车，不是传值机制的问题（同一withEnv里另起一个不常见的名字能正常透传，验证过）。
                    //规避方式：shell侧只用不会撞车的变量名接住真实值，envsubst前先用sed把模板里的${image_tag}/${commit_id}占位符直接替换成字面值，
                    //这样就不需要环境变量名叫"image_tag"/"commit_id"，也就不会被覆盖；其余占位符仍交给envsubst按环境变量正常处理。
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
