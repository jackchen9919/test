//JsonSlurper()/JsonSlurperClassic()都踩过坑：前者返回的LazyMap不可序列化、Groovy CPS在load()等step前
//checkpoint时报NotSerializableException（uat这里两次parseText复用同一实例更容易触发）；后者的构造函数未在这个Jenkins实例的脚本沙箱白名单里，报RejectedAccessException。
//改用pipeline-utility-steps插件自带的readJSON——这是正经Jenkins step而非裸Groovy对象，天然沙箱安全、返回值天然可序列化。
//jenkins agent label
//项目主函数astrox.Jenkinsfile：默认只做部署（镜像由test构建job统一产出）；BRANCH_TAG填了分支名时也支持脱离test、自行指定分支checkout+build+push再部署
//jenkins-sg.hichain.me 没装Kubernetes插件/没配置任何Cloud，只有一个静态节点（标签ofc-hk-bastion），该节点已确认有helm/kubectl/aws-cli/envsubst，agent直接跑在这个静态节点上，不再用K8s动态pod agent
//BRANCH_TAG/IMAGE_TAG以前分别声明在uat/pipline_build.groovy、uat/pipline.groovy里（load()加载的子pipeline），
//declarative的parameters{}块在load()子pipeline里不会注册成真正的job级参数（UI选不到）——统一收到"Register parameters"阶段的properties()调用才是唯一生效的参数声明，
//子文件里原来的parameters{}块已删除，避免两边各自调properties()互相覆盖、参数忽隐忽现
//BRANCH_TAG/IMAGE_TAG都是"留空=默认行为，填了=手动覆盖"同一种模式：
//BRANCH_TAG留空=不构建，直接部署test已构建的镜像（走IMAGE_TAG那套）；填分支名=用这个分支checkout+build+push再部署。
//默认值留空——原来DO_BUILD默认不勾选，这里保留同样的"默认不构建"行为，uat想脱离test单独验证某个分支才手动选。
//BRANCH_TAG用git-parameter插件做成可搜索下拉框（Jenkins已装此插件，跟dev-java-global-ex-msg等job用的是同一个），
//列表是从useRepository指定仓库实时git ls-remote拉的真实分支，既能选也能打字过滤。之前试过Active Choices的
//Groovy脚本方案也能列真实分支，但要过Script Approval人工审批，每套Jenkins环境都要重新审一遍，运维成本更高；
//git-parameter是原生参数类型，不用脚本、不用审批。useRepository不再单独写死一份仓库地址字符串——
//改成从test/setting.groovy对应job块的github_url字段读取（per-job配置，uat没有自己的github_url，
//一直跨文件读test那份），在下面"Register parameters"阶段用env.github_url动态传入
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
                    //setting.groovy里的key跟job名保持完全一致(uat-java-apisix-route)，不再剥环境前缀
                    def micro_key = env.JOB_BASE_NAME
                    //读取配置文件（部署相关字段，build相关字段在 test/setting.groovy）
                    def file = readFile("astrox-helm-chart/uat/setting.groovy")
                    def code_info = readJSON text: file

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
                    env.kubeconfig_credential_id = (code_info.private.kubeconfig_credential_id).toString()
                    env.log_nfs_server = (code_info.private.log_nfs_server).toString()

                    //项目参数
                    env.app_name = (code_info."${micro_key}".app_name).toString()
                    //project 也是 helm templates/_helpers.tpl、hpa.yaml 里 {project}.xxx 命名前缀，部署阶段仍要读
                    env.project = (code_info."${micro_key}".project).toString()
                    env.replicas = (code_info."${micro_key}".replicas).toString()
                    //镜像统一从 test 构建job产出的共享仓库路径拉取，跟本环境自己的k8s namespace解耦
                    //ECR路径段必须跟test构建时实际push的路径一致——不能假定字面量"test"，因为test/setting.groovy里这个服务的namespaces可能被覆盖成"test-product"这种业务线路径
                    //跨AWS账号提醒：本环境docker_repository_url已固定指向test/dev共用registry，需确认该ECR仓库策略已允许本账号跨账号pull（含ecr:DescribeImages，用于下面自动取最新tag）
                    //test/setting.groovy的key已改成跟sit job名一致(sit-java-apisix-route)，不是本job名——同一个服务把uat-java-替换成sit-java-即可推出对应key
                    def test_micro_key = env.JOB_BASE_NAME.replaceFirst(/^uat-java-/, 'sit-java-')
                    def test_setting = readJSON text: readFile("astrox-helm-chart/test/setting.groovy")
                    env.test_namespaces = (test_setting."${test_micro_key}".namespaces) ?: (test_setting.private.namespaces)
                    //build相关参数（BRANCH_TAG填了才会用到），跟test共用一份配置，不在uat/setting.groovy里重复维护
                    //github_url是这个服务构建的业务代码仓库地址，per-job配置，不放在private里（不同服务以后可能对应不同仓库）
                    env.github_url = (test_setting."${test_micro_key}".github_url).toString()
                    env.node_ins = (test_setting."${test_micro_key}".node_ins).toString()
                    env.nodejs_version = (test_setting."${test_micro_key}".nodejs_version).toString()
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
                    env.node_select = (code_info."${micro_key}".node_select) ?: (code_info.private.node_select)
                    env.min_replicas = (code_info."${micro_key}".min_replicas) ?: (code_info.private.min_replicas)
                    env.max_replicas = (code_info."${micro_key}".max_replicas) ?: (code_info.private.max_replicas)
                }

                //properties()必须放在这里（Check info阶段之后），而不是文件最顶部：useRepository要动态传入
                //env.github_url（上面Check info阶段刚从test/setting.groovy的private.github_url读出来），而env.github_url
                //只有checkout到本地磁盘后才能读到，不可能在node()/checkout之前就拿到。Jenkins scripted pipeline的
                //properties()调用生效时机只跟"这次build跑没跑到这一行"有关，跟它在脚本里的物理位置无关，所以挪到这里
                //不影响"参数改动要等下一次build才生效"这个语义。
                stage('Register parameters') {
                    properties([
                        parameters([
                            [$class: 'GitParameterDefinition',
                             name: 'BRANCH_TAG',
                             type: 'PT_BRANCH',
                             description: '默认留空=部署test已构建好的镜像(用下面IMAGE_TAG，或部署:latest标签)；选分支=从这个分支重新checkout+build+push新镜像再部署，用于uat想脱离test单独验证某个分支。下拉列表是实时拉取的真实分支，支持打字过滤；如果通过API等方式绕过下拉框传入了不存在的分支名，会在下面"Validate branch"阶段直接报错终止，不会跑到一半才失败',
                             branchFilter: 'origin/(.*)',
                             tagFilter: '*',
                             sortMode: 'DESCENDING_SMART',
                             defaultValue: '',
                             selectedValue: 'DEFAULT',
                             useRepository: env.github_url,
                             quickFilterEnabled: true,
                             listSize: '5',
                             requiredParameter: false],
                            string(name: 'IMAGE_TAG', defaultValue: '', description: '仅在BRANCH_TAG留空时生效。留空=部署:latest标签（推荐，日常部署不用管这个；每次构建job都会额外维护这个tag）；填了=部署这个指定的历史tag（用于回滚）')
                        ])
                    ])
                }

                //BRANCH_TAG手动输入，可能打错字/分支已被删——build之前先用git ls-remote验证这个分支在业务仓库里真实存在，
                //不存在就直接在这里报错终止，不会等checkout/build跑到一半才失败，报错信息也比git原生报错更好懂
                if (params.BRANCH_TAG?.trim()) {
                    stage('Validate branch') {
                        def branch = params.BRANCH_TAG.trim()
                        def exists = sh(script: "git ls-remote --exit-code --heads ${env.github_url} ${branch}", returnStatus: true) == 0
                        if (!exists) {
                            //TODO(以后如果业务仓库变成私有仓库): 这里的git ls-remote也要补上跟uat/pipline_build.groovy里一致的凭据，否则私有仓库会被误判成"分支不存在"
                            error "分支 \"${branch}\" 在 ${env.github_url} 里不存在，请检查BRANCH_TAG参数有没有打错"
                        }
                    }
                }

                //BRANCH_TAG是"开关"：填了分支名=真的checkout+build+push（uat/pipline_build.groovy），留空=跳过构建直接部署test已构建的镜像（uat/pipline.groovy，IMAGE_TAG或:latest标签）
                if (params.BRANCH_TAG?.trim()) {
                    load("astrox-helm-chart/uat/pipline_build.groovy")
                } else {
                    load("astrox-helm-chart/uat/pipline.groovy")
                }

                //load()加载的声明式sub-pipeline跑在独立node()/workspace里，里面设的env.image_tag(/commit_id)不会可靠带回这一层scripted pipeline
                //（Jenkins load()跨作用域的已知限制），统一从sub-pipeline落的临时文件读回来，同一台静态节点上文件系统是共享的
                stage('Read build outputs') {
                    def image_tag_file = "/tmp/${env.JOB_NAME.replaceAll('/', '_')}-${env.BUILD_NUMBER}-image_tag.txt"
                    env.image_tag = readFile(image_tag_file).trim()
                    sh "rm -f ${image_tag_file}"
                    if (params.BRANCH_TAG?.trim()) {
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
                    withCredentials([file(credentialsId: env.kubeconfig_credential_id, variable: 'KUBECONFIG')]) {
                        sh '''
                            helm list -n ${namespaces}|grep ${app_name} &> /dev/null
                            helm upgrade ${app_name} --install -n ${namespaces} ./${chart_name}/${env_tier}
                            if [ "${image_tag}" = "latest" ]; then
                                echo "IMAGE_TAG用的是浮动的:latest标签，Deployment里镜像字符串没变，helm upgrade不会自动触发滚动更新——手动rollout restart强制重新拉取"
                                kubectl rollout restart ${kind_name} -n ${namespaces} ${app_name}
                            fi
                        '''
                    }
                }


                stage('update helm chart') {
                    withCredentials([file(credentialsId: env.kubeconfig_credential_id, variable: 'KUBECONFIG')]) {
                        sh '''
                            timeout 300 kubectl rollout status ${kind_name} -n ${namespaces} ${app_name} || status=false
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
