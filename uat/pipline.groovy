pipeline {
    agent any
    //IMAGE_TAG参数已挪到外层uat/astrox.Jenkinsfile的properties()统一声明（这里原来的parameters{}块在load()子pipeline里不会注册成真正job参数）
    stages {
        stage('Resolve image tag') {
            steps {
                script {
                    if (params.IMAGE_TAG?.trim()) {
                        env.image_tag = params.IMAGE_TAG.trim()
                        echo "已手动指定IMAGE_TAG，使用: ${image_tag}"
                    } else {
                        //不再靠aws ecr describe-images按imagePushedAt排序猜"最新tag"——同一份构建内容多次push会复用同一个image digest，
                        //ECR只按digest记一条imagePushedAt，排序在这种场景下形同虚设，选出来的可能是任意一个历史tag。
                        //改成直接部署构建job(test/pipline.groovy)每次push时额外维护的:latest标签，语义上就是"最新一次真实构建"
                        env.image_tag = 'latest'
                        def exists = sh(script: "aws ecr describe-images --repository-name ${test_namespaces}/${app_name} --region ${aws_region} --image-ids imageTag=latest", returnStatus: true) == 0
                        if (!exists) {
                            error "ECR里没有:latest标签，请确认test构建job至少成功构建过一次，或手动指定IMAGE_TAG"
                        }
                        echo "IMAGE_TAG未填写，使用最新一次构建打的:latest标签"
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
