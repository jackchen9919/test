# astrox-helm-chart

## 整体模型（对齐真实基础设施后）

环境只有4套：**test / dev / uat / prod**（已删除 `sit`）。

只有 2 次真正的镜像构建（buildah）：
- `test/`：checkout业务代码（GitHub）、buildah build、push镜像到ECR。
- `prod/`：保留独立的 checkout+build+push+deploy，物理上是第2次build。

`test/` 目录下只有**一个job**（`test/astrox.Jenkinsfile`），构建和部署合并在一起——是否构建由 `BRANCH_TAG` 参数是否为空控制（手动填分支名=先checkout业务代码+build+push再部署，走`test/pipline.groovy`；留空=跳过构建直接部署，走`test/deploy_pipline.groovy`），跟`prod`一样是"build+deploy同job"模式，build不是单独的job，只是job里"填不填分支名"这一个开关。`BRANCH_TAG`用Jenkins自带的git-parameter插件做成可搜索下拉框，下拉列表是实时`git ls-remote`拉取的真实分支，既能选也能打字过滤；pipeline里还留了一层`git ls-remote`校验兜底（防的是通过API等方式绕过下拉框传入不存在的分支名），不会等到build跑到一半才失败。

`dev`、`uat` 两个环境也是同样的`BRANCH_TAG`开关模式：默认留空=纯部署（不checkout业务代码/build/push，用`IMAGE_TAG`——留空自动去ECR查该服务最新push的tag，填了则用这个值部署，用于回滚/部署指定历史版本）；手动填分支名=脱离test单独构建部署（dev默认值是`main`，uat默认留空）。

Jenkins agent 全部改成 K8s 动态pod agent（`podTemplate` + `node(POD_LABEL)`），不再用固定的静态 `node(label)`；agent的K8s cloud名和镜像地址由各环境 `setting.groovy` 的 `private.jenkins_cloud`/`private.agent_image` 决定。

飞书（Lark）成功/失败通知目前已去掉（`private.lark_webhook_url`字段及各Jenkinsfile里对应的`curl`通知步骤都已删除），等有真实webhook地址再加回来。

### `env_tier` 与 `namespaces` 的区别（重要）

- `env_tier`：固定值，等于本环境目录名（`dev`/`uat`/`prod`），只用来定位chart源码子目录（`${chart_name}/${env_tier}`）和node taint/toleration的值，**不可被服务级配置覆盖**。
- `namespaces`：真正的k8s namespace，**服务级可覆盖**（`(code_info."${micro_key}".namespaces) ?: (code_info.private.namespaces)`），因为真实环境下同一环境的不同业务线会用不同namespace（如 `test-product`、`test-funds`、`uat-match`）。不需要覆盖时缺省沿用环境默认值。

这两者以前是同一个字段（`namespaces`），会导致"改了服务的k8s namespace，chart源码目录路径也跟着变"的错误联动，现已拆开。

### `private` 字段速查（4个环境`setting.groovy`通用，含义一致）

| 字段 | 说明 |
|---|---|
| `docker_repository_url` | ECR镜像仓库地址 |
| `aws_region` | AWS区域 |
| `jenkins_cloud` / `agent_image` / `deploy_agent_image` | 早期K8s动态pod agent方案的遗留字段；`jenkins-sg.hichain.me`现在用固定静态节点（`ofc-hk-bastion`）跑，代码里已经没有地方读这3个了，留着没用 |
| `chart_name` | chart包的仓库目录名，`clone helm chart`那一步clone下来的文件夹叫这个名字 |
| `namespaces` | k8s namespace默认值，per-job没单独配的话用这个 |
| `env_tier` | 固定值，等于本环境目录名，只用来定位chart源码子目录和node taint/toleration，不可被服务级配置覆盖（跟`namespaces`的区别见上一节） |
| `node_select` | k8s节点选择器 |
| `kubeconfig_credential_id`（test/dev/uat）/ `KUBECONFIG`（prod） | 部署阶段kubectl/helm连哪个集群靠这个 |
| `nfs_server` / `log_nfs_server` | 业务数据/日志的NFS挂载地址 |
| `limits_cpu` / `limits_mem` / `requests_cpu` / `requests_mem` | 容器资源limit/request默认值 |
| `kind_name` | k8s工作负载类型，helm渲染`values.yaml`的`kind`字段和`kubectl rollout restart/status`都用这个值。**取值只能是精确的`"Deployment"`或`"StatefulSet"`（大小写敏感——chart模板`{{- if eq .Values.kind "Deployment" }}`做的是字符串精确匹配；kubectl命令行本身不区分大小写，所以这个坑只会在helm渲染这一层炸，不会在kubectl这层炸，容易被忽略）** |
| `min_replicas` / `max_replicas` | HPA自动扩缩容的最小/最大副本数 |
| `no_ingress` | 是否不生成ingress（部分服务只用ApisixRoute不需要ingress） |
| `websocket_port` | websocket端口，没有就是`"null"`；per-job不填时吃这个默认值，`values.yaml`里为`null`的话helm模板会把websocket那段配置整段删掉 |
| `skywalking_enabled` | 是否开启skywalking链路追踪，per-job不填时吃这个默认值 |
| `add_java_jar` / `gradle_ins`（test/dev/prod）| 拼进Dockerfile的COPY行 / Gradle构建命令模板 |

**字段该放`private`还是per-job的判断标准**：不是看"现在值是否相同"，而是看"这个字段概念上归谁所有"——真正platform级、所有job/服务必然一样的（ECR地址、kubeconfig凭据、节点选择器）放`private`；本质上是"某个服务/job的属性"、只是碰巧现在大家的值相同的（比如`github_url`——不同服务以后大概率指向不同代码仓库）放per-job，哪怕现在看起来是重复的。

### `sit` 环境下`kind_name`的Deployment/StatefulSet覆盖测试（重要，涉及共享对象的行为）

`test/setting.groovy`里的两个job：`sit-java-apisix-route`（`kind_name: StatefulSet`）和`sit-java-apisix-route-2`（`kind_name: Deployment`），**故意配成不同的`kind_name`**，让sit环境同时覆盖到Deployment和StatefulSet两条代码路径（helm渲染分支 + `kubectl rollout restart/status`两种资源类型）。

但这两个job的`namespaces`/`app_name`完全相同，**共享同一个真实k8s对象**（这是更早为测试Helm双触发竞态故意设计的，见下方"操作步骤"外的历史背景）。这意味着：
- 这个对象实际当前是Deployment还是StatefulSet，**取决于哪个job最近一次跑成功**，不是"job1永远StatefulSet、job2永远Deployment"各自稳定存在两份对象。
- 无论跑哪个job，只要它跟当前对象的实际kind不一致，`helm upgrade`就会删掉旧对象重建新kind的对象（Deployment↔StatefulSet没有原地转换），带来一次短暂的服务中断（`replicas:1`时尤其明显）。
- 两个job如果时间点上跑得很接近，除了已知的"release already exists"竞态外，还可能出现"一个job刚把对象转成StatefulSet，另一个紧接着又把它转回Deployment"这种来回抖动，需要注意错开触发时间。

### `sit`环境的灰度发布（APISIX加权分流，v1，仅`sit-java-apisix-route`）

用`ApisixRoute`（v2 CRD）原生的多`backend` + `weight`字段做流量分流，不引入Argo Rollouts/Flagger这类额外controller，也不用"调副本数比例"这种伪灰度。机制：`apisixroute.yaml`的`backends`数组平时只有一个（稳定版Service，无`weight`字段=100%流量）；灰度开启时变成两个backend——稳定版权重`100-W`，新增的`{{ .Values.appname }}-canary` Service权重`W`（`W`是Jenkins构建参数`CANARY_WEIGHT`，0-100）。canary版对应一套独立的`canary-deployment.yaml`/`canary-service.yaml`（label/selector都带`-canary`后缀，跟稳定版完全隔离，不会被稳定版的HPA/podAntiAffinity选中）。

**默认不开灰度、不传参数行为不变**：`CANARY_WEIGHT`留空时，`values.yaml`里`canary.enabled`渲染成`false`，`apisixroute.yaml`/`canary-deployment.yaml`/`canary-service.yaml`里的`{{- if .Values.canary }}{{- if .Values.canary.enabled }}`两层guard整体不渲染，跟没有灰度这个功能之前的产物逐字节一致。

**开启灰度时，稳定版镜像不会被这次构建覆盖**：Jenkinsfile会先从集群里查线上稳定版当前真实镜像（`kubectl get ${kind_name} ... -o jsonpath='{.spec.template.spec.containers[0].image}'`），渲染进稳定版的`image`字段；这次构建/指定的新镜像只会渲染进canary Deployment。这样`helm upgrade`不会把稳定版的Pod重新调度，只新建canary这一套对象。**前提**：线上必须已有一版正常部署过的稳定版本，不能在第一次部署时就直接带`CANARY_WEIGHT`（会报错终止，提示先跑一次不带该参数的普通部署）。

**晋升/回滚都是"再跑一次普通部署"，不需要额外操作**：
- 晋升（把canary转正）：不带`CANARY_WEIGHT`，`IMAGE_TAG`/分支指向canary验证的那个版本，正常部署一次。
- 回滚（放弃canary）：不带`CANARY_WEIGHT`，`IMAGE_TAG`指回灰度开始前的旧稳定版tag，正常部署一次。

两种情况下，helm渲染出的manifest里都不再包含canary Deployment/Service（`apisixroute.yaml`收敛回单backend），Helm 3会自动把上一版渲染过、这一版不再渲染的资源从release里清理掉——不需要额外写清理脚本。

**v1已知的简化/边界（不是遗漏）**：
- canary固定是`kind: Deployment`，不跟随线上稳定版当前的`kind_name`做StatefulSet canary。
- canary workload跳过skywalking initContainer和nfs/log_nfs挂载，只覆盖sit这种nginx纯静态场景；以后要在其它project_type上开灰度，得先补齐这部分。
- 权重字段按"百分比"设计（canary=W，稳定=100-W）方便理解，虽然APISIX实际按相对比例分配、并不要求两个weight相加等于100。
- `sit-java-apisix-route-2`（上面那个kind_name覆盖测试job）**没有接入灰度参数**——如果在灰度验证窗口期误触发job2，job2渲染的values没有`canary.enabled=true`，会把canary资源和`apisixroute`的第二个backend一起清掉，相当于把灰度状态重置。这个交互只是文档提醒"灰度验证期间不要触发job2"，不做锁/互斥，跟前面kind_name竞态"手动重触发、暂不加锁"的态度一致。

### `chart_templates/`（helm chart模版共享目录）

`template.Chart.yaml`、`templates/*.yaml`、`templates/_helpers.tpl`、`template_<project_type>.values.yaml` 这些helm chart文件现在只在仓库根目录的 `chart_templates/` 里维护**一份**，不再在 `dev/`、`uat/`、`prod/`（以及新增的test部署job）各自重复一份——以前是逐环境手工复制维护，改一处要改三四处，还产生过`templates/hpa.yaml`内容重复粘贴、`values.yaml`镜像tag/ingress hosts格式不统一之类的真实bug。

各环境部署job的 `Update values.yaml` 阶段会在渲染前先把 `chart_templates/` 里的文件拷贝一份到本环境目录（`${chart_name}/${env_tier}/`），再执行原有的 `envsubst`/`sed` 渲染逻辑——每次pipeline跑的时候现装现用，git里只留一份canonical文件，同时满足helm要求chart目录必须自包含的前提。以后要改镜像资源限制、ingress规则、HPA形态之类的chart结构，改 `chart_templates/` 一处即可，四个环境都会在下次部署时吃到。

## 操作步骤

### test（构建 + 部署，同一个job，`BRANCH_TAG`是否填分支名控制是否构建）
```
1）修改 test/setting.groovy，新增子字典（jenkins项目名做key，如没有将退出）——构建字段和部署字段写在同一个字典里：
    "xxx-service": {
        # 构建相关字段（BRANCH_TAG填了分支名时用）
        "github_url": "https://github.com/your-org/xxx-service.git", #项目代码（GitHub）
        "project": "xxx-service",        #gradle/maven模块名，同时也是helm templates里的{project}命名前缀
        "project_type": "java8",         #java8/newexchange_java8/java17_maven/nginx/go/nodejs/nodejs_explore/python
        "app_name": "xxx-service",       #镜像名/应用名
        "maven_ins": "mvn clean package -Dmaven.test.skip=true",   #如果project_type是maven系（含java17_maven）
        "node_ins": "...",               #如果project_type是nodejs系
        "nodejs_version": "16.14.1",
        "namespaces": "test-product",    #可选，缺省沿用 private.namespaces="test"
        # 部署相关字段（字段含义跟dev/uat一致，见下面"test/dev/uat（部署）"）
        "replicas": "1",
        "http_port": "8080",
        "actuator_port": "8080",
        "ingress_hosts": "xxx-test.astroxs.com",
        "ingress_paths": "/",
        "no_ingress": "false",
        "websocket_port": "null",
    },
2）Build with Parameters跑job：`BRANCH_TAG`下拉选一个分支（如`main`，可打字过滤）=先构建新镜像（走`test/pipline.groovy`）再部署；留空=跳过构建直接部署（`IMAGE_TAG`留空直接跑即可，部署`:latest`标签——每次构建job都会额外维护这个tag，不再靠ECR按push时间排序猜最新）
```
构建工具是 buildah（不是 docker），构建前会先 `aws ecr get-login-password | buildah login` 显式登录ECR，ECR仓库不存在会自动 `aws ecr create-repository` 创建。`BRANCH_TAG`填了分支名时用`private.agent_image`（JDK17/Maven/buildah规格），留空时用`private.deploy_agent_image`（helm/kubectl/awscli规格），同一个job按参数二选一。

### test / dev / uat（部署）
```
1）修改对应环境的 setting.groovy，新增子字典（部署相关字段；test是在已有字典里补这些字段，见上面）：
    "xxx-service": {
        "app_name": "xxx-service",
        "project": "xxx-service",        #跟test构建字典里的project保持一致
        "project_type": "java8",
        "replicas": "1",
        "http_port": "8080",
        "actuator_port": "8080",
        "ingress_hosts": "xxx.astroxs.com",
        "ingress_paths": "/",
        "no_ingress": "false",
        "websocket_port": "null",
        "node_select": "beta.kubernetes.io/instance-type: m5.xlarge",
        "limits_cpu": "1",
        "limits_mem": "2G",
        "requests_cpu": "0.5",
        "requests_mem": "2G",
        "namespaces": "uat-match",        #可选，缺省沿用 private.namespaces
        "min_replicas": "1",              #可选，HPA最小副本数，缺省沿用 private.min_replicas
        "max_replicas": "10",             #可选，HPA最大副本数，缺省沿用 private.max_replicas
    },
2）跑对应job，**`IMAGE_TAG`留空直接构建即可**（部署`:latest`标签，不用再去test job手动复制）；要部署/回滚到某个历史tag，在`IMAGE_TAG`里填那个tag
```
`dev`/`uat`部署job的`IMAGE_TAG`留空时解析用的ECR仓库路径是test构建时**实际push的路径**（读`test/setting.groovy`里该服务的`namespaces`覆盖值，不是字面量`"test"`），所以`:latest`标签的前提是`test/setting.groovy`和`dev|uat/setting.groovy`里同一个服务的配置都已经写好、且test至少成功构建过一次。test的`BRANCH_TAG`留空时则直接读自己那份`test/setting.groovy`里的`namespaces`，不需要跨文件。

> **uat跨AWS账号前提**：test/dev共用ECR账号 `178092210163`，uat是独立账号 `696000197734`。`uat/setting.groovy` 的 `docker_repository_url` 已经指向test/dev共用registry（而不是uat自己账号），这样uat才能部署test构建产出的同一个镜像tag——但这要求test/dev账号下那个ECR仓库的仓库策略（repository policy）显式允许uat账号跨账号pull，这是AWS侧需要用户自行配置的前提条件，不是代码能解决的。

### prod（构建+部署，独立不变）
```
沿用上面test的build字段 + dev/uat的部署字段，两份都要写在 prod/setting.groovy 同一个子字典里。
```
prod的agent镜像（`private.agent_image`）需要同时具备构建工具链（JDK17/Maven/buildah）和部署工具链（helm/kubectl/awscli），因为prod始终构建，build+deploy在同一个job里跑（跟test不同的是prod没有`BRANCH_TAG`留空跳过构建这个开关，永远构建）。

> GitHub凭据：仓库里目前的凭据ID只是占位，部署前必须去 Jenkins 里核实/替换成 Astrox 自己配置的真实凭据（各 `pipline.groovy` 里标了 TODO）。
> Jenkins地址切换到 `https://jenkins.astroxs.com/` 不涉及本仓库代码，需要在 Jenkins 侧自行配置。
> **KUBECONFIG**：`test`/`dev`/`uat`三个环境不再直接写死kubeconfig文件路径字符串，改成`private.kubeconfig_credential_id`——填一个Jenkins里"Secret file"类型凭据的ID，凭据内容是能访问目标EKS集群的kubeconfig文件本身，job里用`withCredentials([file(...)])`注入。当前是占位符`FILL_IN_KUBECONFIG_CREDENTIAL_ID`，需要用户先在Jenkins（Manage Jenkins → Credentials）创建这个凭据，再把真实ID填进三个`setting.groovy`。`prod/setting.groovy`维持原来`private.KUBECONFIG`直接写路径字符串的写法不变（未改动，沿用其原有的Jenkins托管路径机制）。
> 仍然待补充的真实值：`dev`/`prod` 两个环境真实ECR地址（`setting.groovy` 里的 `FILL_IN_*` 占位符）、`prod` 的 `jenkins_cloud`/`agent_image` 真实值。
