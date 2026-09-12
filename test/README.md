# test

test目录下只有**一个job**（`test/astrox.Jenkinsfile`），构建和部署合并在一起，读同一份 `test/setting.groovy`。整体模型和字段说明见根目录 `README.md`。

## 构建+部署job（`test/astrox.Jenkinsfile`）

用 `BRANCH_TAG` 参数（Build with Parameters里可搜索的下拉框，git-parameter插件实时`git ls-remote`拉真实分支，既能选也能打字过滤）是否选了分支名决定这一次跑不跑构建，构建不是单独的job/按钮，只是这个job里的一个开关：

- **`BRANCH_TAG` 选分支**（如`main`）：checkout GitHub业务代码、build、push镜像（走 `test/pipline.groovy`），接着做helm部署。日常提交新代码走这个。pipeline里还留了一层`git ls-remote`校验兜底（防的是通过API等方式绕过下拉框传入不存在的分支名），不会等checkout/build跑到一半才失败。
- **`BRANCH_TAG` 留空**：跳过构建，直接部署（走 `test/deploy_pipline.groovy`）。默认（`IMAGE_TAG`留空）部署`:latest`标签——每次构建job push完版本tag后都会额外维护这个`:latest`（不再靠ECR按push时间排序猜最新，那套在同内容重复构建时会失效）；只有要回滚/部署指定历史版本时，才填`IMAGE_TAG`。

构建工具是 buildah（显式 `aws ecr login`），不是 docker。构建时agent镜像用`private.agent_image`（JDK17/Maven/buildah规格）；不构建只部署时用`private.deploy_agent_image`（helm/kubectl/awscli规格），两者按`BRANCH_TAG`是否填了分支名二选一。

`test/setting.groovy` 的子字典需要build+部署字段都写在同一个字典里：

| 字段 | 说明 |
|---|---|
| `github_url`/`node_ins`/`nodejs_version`等 | 仅`BRANCH_TAG`填了分支名时用，业务代码checkout/构建参数 |
| `app_name` | 镜像名/应用名 |
| `project` | helm templates里的`{project}`命名前缀 |
| `project_type` | java8/newexchange_java8/java17_maven/nginx/go/nodejs/nodejs_explore/python |
| `replicas` | 初始副本数 |
| `http_port` / `actuator_port` | 服务端口/健康检查端口 |
| `ingress_hosts` / `ingress_paths` | ingress域名/路径 |
| `no_ingress` | `"true"`则不生成apisixroute.yaml |
| `websocket_port` | 没有websocket则填`"null"` |
| `namespaces`（可选） | 缺省沿用 `private.namespaces="test"` |
| `min_replicas`/`max_replicas`（可选） | HPA副本数上下限，缺省沿用 `private.min_replicas`/`private.max_replicas` |

`private`块每个字段的说明见根目录`README.md`的"`private` 字段速查"表格；这里只补一点test特有的：`kubeconfig_credential_id`是Jenkins里"Secret file"类型凭据的ID，凭据内容是能访问目标EKS集群的kubeconfig文件本身（不是文件路径字符串），job里通过`withCredentials([file(...)])`把凭据内容落到临时文件再交给helm/kubectl。

`sit-java-apisix-route`/`sit-java-apisix-route-2`这两个job的`kind_name`故意配成不同值（一个`StatefulSet`一个`Deployment`），用来在sit环境同时覆盖两条代码路径；但它们共享同一个k8s对象，实际行为和风险见根`README.md`对应小节，不要假设这两个job各自稳定存在独立的一份对象。

跟dev/uat/prod一样，`Update values.yaml`阶段会从仓库根目录的`chart_templates/`共享目录拷贝chart文件后再渲染，详见根`README.md`的"`chart_templates/`"一节。

### `CANARY_WEIGHT`参数（灰度发布，仅本job）

Build with Parameters里新增的`CANARY_WEIGHT`留空即不开灰度，行为跟没有这个参数之前完全一样；填0-100的数字即开启灰度，把这个百分比的流量分给这次构建/`IMAGE_TAG`指定的版本，其余流量留在当前线上稳定版不动（稳定版镜像不会被这次构建覆盖）。要求线上已有一版正常部署过的稳定版本——不能在第一次部署时就直接带这个参数（会报错终止）。机制原理、晋升/回滚方式、v1已知简化和跟`sit-java-apisix-route-2`的交互风险，见根`README.md`"`sit`环境的灰度发布"一节。
