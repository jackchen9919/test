docker build -t harbor.hichain.me/ops/java8:v1.8 .
docker push harbor.hichain.me/ops/java8:v1.8
docker rmi $(docker images | grep "none" | awk '{print $3}')