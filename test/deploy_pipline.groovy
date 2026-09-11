pipeline {
    agent any
    parameters {
        booleanParam(name: 'SPECIFY_TAG', defaultValue: false, description: '是否手动指定要部署的镜像tag。默认不勾选=自动取ECR里该服务最新一次push的tag（推荐，日常部署不用管这个）；勾选=手动填下面的IMAGE_TAG，用于回滚/部署指定历史版本')
        string(name: 'IMAGE_TAG', defaultValue: '', description: '仅在勾选SPECIFY_TAG时生效，填要部署的历史tag')
    }
    stages {
        stage('Resolve image tag') {
            steps {
                script {
                    if (params.SPECIFY_TAG) {
                        if (!params.IMAGE_TAG?.trim()) {
                            error "已勾选SPECIFY_TAG手动指定tag，但IMAGE_TAG为空，请填写要部署的tag"
                        }
                        env.image_tag = params.IMAGE_TAG.trim()
                        echo "已勾选SPECIFY_TAG，使用手动指定的tag: ${image_tag}"
                    } else {
                        env.image_tag = sh(
                            script: "aws ecr describe-images --repository-name ${namespaces}/${app_name} --region ${aws_region} --query 'sort_by(imageDetails,&imagePushedAt)[-1].imageTags[0]' --output text",
                            returnStdout: true
                        ).trim()
                        if (!env.image_tag || env.image_tag == 'None') {
                            error "自动获取ECR最新tag失败，请确认test构建job至少成功构建过一次，或勾选SPECIFY_TAG手动指定IMAGE_TAG"
                        }
                        echo "SPECIFY_TAG未勾选，自动取ECR最新推送的tag: ${image_tag}"
                    }
                    //load()加载的声明式pipeline跑在独立node()/workspace里，这里设的env.image_tag不会可靠带回外层scripted pipeline
                    //（Jenkins load()跨作用域的已知限制，实测有时能带回有时不能），改落一个临时文件，外层Update values.yaml阶段前读回来
                    writeFile file: "/tmp/${env.JOB_NAME.replaceAll('/', '_')}-${env.BUILD_NUMBER}-image_tag.txt", text: env.image_tag
                }
                echo "部署镜像: ${image_url}:${image_tag}"
            }
        }
    }
}
