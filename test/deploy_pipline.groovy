pipeline {
    agent any
    //IMAGE_TAG参数已挪到外层test/astrox.Jenkinsfile的properties()统一声明（这里原来的parameters{}块在load()子pipeline里不会注册成真正job参数）
    stages {
        stage('Resolve image tag') {
            steps {
                script {
                    if (params.IMAGE_TAG?.trim()) {
                        env.image_tag = params.IMAGE_TAG.trim()
                        echo "已手动指定IMAGE_TAG，使用: ${image_tag}"
                    } else {
                        env.image_tag = sh(
                            script: "aws ecr describe-images --repository-name ${namespaces}/${app_name} --region ${aws_region} --query 'sort_by(imageDetails,&imagePushedAt)[-1].imageTags[0]' --output text",
                            returnStdout: true
                        ).trim()
                        if (!env.image_tag || env.image_tag == 'None') {
                            error "自动获取ECR最新tag失败，请确认test构建job至少成功构建过一次，或手动指定IMAGE_TAG"
                        }
                        echo "IMAGE_TAG未填写，自动取ECR最新推送的tag: ${image_tag}"
                    }
                    //load()加载的声明式pipeline跑在独立node()/workspace里，这里设的env.image_tag不会可靠带回外层scripted pipeline
                    //（Jenkins load()跨作用域的已知限制），改落一个临时文件，外层Update values.yaml阶段前读回来
                    writeFile file: "/tmp/${env.JOB_NAME.replaceAll('/', '_')}-${env.BUILD_NUMBER}-image_tag.txt", text: env.image_tag
                }
                echo "部署镜像: ${image_url}:${image_tag}"
            }
        }
    }
}
