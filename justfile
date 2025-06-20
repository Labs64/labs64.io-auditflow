APP_NAME := "auditflow"

# build application
build:
    mvn clean package

# build docker image
docker: build
    docker build -t {{APP_NAME}}:latest .
    docker tag {{APP_NAME}}:latest localhost:5005/{{APP_NAME}}:latest
    docker push localhost:5005/{{APP_NAME}}:latest
    docker images | grep "{{APP_NAME}}"

# run application
run: build
    mvn spring-boot:run -Dspring-boot.run.profiles=local

# check maven dependencies/plugins/properties updates
check-updates:
    mvn versions:display-plugin-updates
    mvn versions:display-property-updates
    mvn versions:display-dependency-updates -DprocessDependencyManagement=false
