# test

test目录下有**两个完全独立、互不影响的job**，都读同一份 `test/setting.groovy`。整体模型和字段说明见根目录 `README.md`。

## 构建job（`test/astrox.Jenkinsfile` + `test/pipline.groovy`）

共享构建job：checkout GitHub业务代码、build、push镜像，不做helm部署。

`test/setting.groovy` 的子字典需要 build 字段（`github_url`/`project`/`project_type`/`maven_ins`/`node_ins`/`nodejs_version`等）。build完在"Print image tag"步骤打印出镜像tag——本目录部署job、以及dev/uat对应部署job默认（`SPECIFY_TAG`不勾选）都会自动去ECR取这个服务最新push的tag，不需要再手动复制；这里打印出来的tag只在要回滚/部署指定历史版本时，才需要勾选`SPECIFY_TAG`并填到部署job的 `IMAGE_TAG` 参数里。构建工具是 buildah（显式 `aws ecr login`），不是 docker。agent镜像用`private.agent_image`（JDK17/Maven/buildah）。

## 部署job（`test/deploy.Jenkinsfile` + `test/deploy_pipline.groovy`）

test环境的独立helm部署job，只做部署，不checkout/build/push——结构照抄dev/uat的纯部署模式（`SPECIFY_TAG`+`IMAGE_TAG`参数、Update values.yaml→helm upgrade→rollout status自动回滚→飞书通知）。跟上面构建job用**同一份`test/setting.groovy`**，所以不用像dev/uat那样跨文件读`test`的namespaces——直接用本文件里的`namespaces`覆盖值，即是构建job实际push镜像时用的那个namespace。

`test/setting.groovy` 的子字典（跟build字段写在同一个字典里）还需要补部署相关字段：
| 字段 | 说明 |
|---|---|
| `app_name` | 镜像名/应用名，跟build字典共用同一个key |
| `project` | helm templates里的`{project}`命名前缀，跟build字典共用同一个key |
| `project_type` | java8/newexchange_java8/java17_maven/nginx/go/nodejs/nodejs_explore/python |
| `replicas` | 初始副本数 |
| `http_port` / `actuator_port` | 服务端口/健康检查端口 |
| `ingress_hosts` / `ingress_paths` | ingress域名/路径 |
| `no_ingress` | `"true"`则不生成apisixroute.yaml |
| `websocket_port` | 没有websocket则填`"null"` |
| `namespaces`（可选） | 缺省沿用 `private.namespaces="test"` |
| `min_replicas`/`max_replicas`（可选） | HPA副本数上下限，缺省沿用 `private.min_replicas`/`private.max_replicas` |

`private`块新增的部署相关字段：`env_tier`（固定`"test"`）、`deploy_agent_image`（部署agent镜像，只需要helm/kubectl/awscli，跟build用的`agent_image`分开，当前是占位符`FILL_IN_DEPLOY_AGENT_IMAGE_WITH_HELM_KUBECTL_AWSCLI`需要用户替换成真实镜像）、`node_select`、`KUBECONFIG`、`nfs_server`/`log_nfs_server`、`limits_cpu`/`limits_mem`/`requests_cpu`/`requests_mem`、`kind_name`、`min_replicas`/`max_replicas`。

跟dev/uat/prod一样，部署job的`Update values.yaml`阶段会从仓库根目录的`chart_templates/`共享目录拷贝chart文件后再渲染，详见根`README.md`的"`chart_templates/`"一节。
