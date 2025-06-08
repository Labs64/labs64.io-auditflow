## Toolset
# Local: k8s, kubectl, helm, docker, maven, git, java

# Local docker repository
# docker run -d -p 5005:5000 --name registry registry:2

docker images --format "{{.Repository}}:{{.Tag}} {{.ID}}" | grep "auditflow" | awk '{print $2}' | xargs -r docker rmi -f

# Build project
mvn clean package

# Build docker images
docker build -t auditflow:latest .
docker tag auditflow:latest localhost:5005/auditflow:latest
docker push localhost:5005/auditflow:latest

docker images --format "{{.Repository}}:{{.Tag}} {{.ID}}" | grep "auditflow"

# Start application
#mvn spring-boot:run -Dspring-boot.run.profiles=local
