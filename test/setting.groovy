{
    "private": {
        //ECR镜像仓库地址(账号815644216915，业务镜像都推这里)
        "docker_repository_url": "815644216915.dkr.ecr.ap-southeast-1.amazonaws.com",
        //AWS区域
        "aws_region": "ap-southeast-1",
        //以下3个(jenkins_cloud/agent_image/deploy_agent_image)是早期K8s动态pod agent方案的遗留字段，
        //现在jenkins-sg.hichain.me用固定静态节点(ofc-hk-bastion)跑，代码里已经没有地方读这3个了，留着没用
        "jenkins_cloud": "kubernetes",
        "agent_image": "178092210163.dkr.ecr.ap-southeast-1.amazonaws.com/jenkins-agent-jdk17-maven:20260629-v9",
        "deploy_agent_image": "alpine/k8s:1.28.4",
        //chart包的仓库目录名，clone helm chart那一步clone下来的文件夹叫这个名字
        "chart_name": "astrox-helm-chart",
        //k8s namespace默认值，per-job没单独配的话用这个(本环境实际每个job都配了自己的，这个当兜底)
        "namespaces": "test",
        //拼进Dockerfile里的一行，把build产物jar包COPY进镜像
        "add_java_jar": "ADD {project}/build/libs/*.jar /app/app.jar",
        //Gradle构建命令模板
        "gradle_ins": "${jradle_path}/bin/gradle ${project}:clean --refresh-dependencies ${project}:build -x test",
        //环境标识，等于目录名test，用来定位chart源码目录/给node打taint
        "env_tier": "test",
        //k8s节点选择器，对应节点上的environment标签(这个集群裸标签只有sit一个，其余是global-*/mini-*/nova-*)
        "node_select": "environment: sit",
        //Jenkins里配置的kubeconfig凭据ID，部署阶段kubectl/helm连哪个集群靠这个
        "kubeconfig_credential_id": "ofc-test",
        //业务数据NFS挂载地址
        "nfs_server": "fs-0575ae6fd27edf058.efs.ap-southeast-1.amazonaws.com",
        //日志NFS挂载地址
        "log_nfs_server": "fs-078f16b1172edf396.efs.ap-southeast-1.amazonaws.com",
        //容器资源limit/request默认值(CPU核数/内存)
        "limits_cpu": "1",
        "limits_mem": "2G",
        "requests_cpu": "0.1",
        "requests_mem": "0.2G",
        //k8s工作负载类型，部署/rollout status都是操作这个kind
        "kind_name": "deployment",
        //HPA自动扩缩容的最小/最大副本数
        "min_replicas": "1",
        "max_replicas": "2",
        //是否不生成ingress(部分服务只用ApisixRoute不需要ingress)
        "no_ingress": "false",
        //websocket端口，没有就是null；values.yaml里为null的话helm模板会把websocket那段配置整段删掉
        "websocket_port": "null",
        //是否开启skywalking链路追踪
        "skywalking_enabled": "false",
    },

    "sit-java-apisix-route": {
        //github_url是这个job构建的业务代码所在仓库，per-job配置(以后不同服务/不同job完全可能对应不同代码仓库，
        //不是platform级共用属性，不适合放进private——只是现在这个仓库里的服务恰好都用同一个仓库)。
        //Jenkinsfile里BRANCH_TAG参数的useRepository字段从这里动态读取，不再单独写死
        "github_url": "https://github.com/jackchen9919/test.git",
        "node_ins": "npm run build",
        "nodejs_version": "NodeJS 16.14.1",
        "app_name": "apisix-route-test",
        "project": "apisix-route-test",
        "project_type": "nginx",
        "replicas": "1",
        "http_port": "8080",
        "actuator_port": "8080",
        "ingress_hosts": "apisix-route-test.astroxs.com",
        "ingress_paths": "/*",
        "namespaces": "apisix-route-test",
    },

    "sit-java-apisix-route-2": {
        "github_url": "https://github.com/jackchen9919/test.git",
        "node_ins": "npm run build",
        "nodejs_version": "NodeJS 16.14.1",
        "app_name": "apisix-route-test",
        "project": "apisix-route-test",
        "project_type": "nginx",
        "replicas": "1",
        "http_port": "8080",
        "actuator_port": "8080",
        "ingress_hosts": "apisix-route-test.astroxs.com",
        "ingress_paths": "/*",
        "namespaces": "apisix-route-test",
    },
}
