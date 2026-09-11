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
2）Build with Parameters跑job：`BRANCH_TAG`下拉选一个分支（如`main`，可打字过滤）=先构建新镜像（走`test/pipline.groovy`）再部署；留空=跳过构建直接部署（`IMAGE_TAG`留空直接跑即可，自动去ECR取该服务最新一次push的tag）
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
2）跑对应job，**`IMAGE_TAG`留空直接构建即可**（自动去ECR取该服务最新一次push的tag，不用再去test job手动复制）；要部署/回滚到某个历史tag，在`IMAGE_TAG`里填那个tag
```
`dev`/`uat`部署job的`IMAGE_TAG`自动解析用的ECR仓库路径是test构建时**实际push的路径**（读`test/setting.groovy`里该服务的`namespaces`覆盖值，不是字面量`"test"`），所以自动取tag前提是`test/setting.groovy`和`dev|uat/setting.groovy`里同一个服务的配置都已经写好。test的`BRANCH_TAG`留空时则直接读自己那份`test/setting.groovy`里的`namespaces`，不需要跨文件。

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
