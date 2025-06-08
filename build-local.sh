#!/bin/bash

# Set application name
APP_NAME="auditflow"

## Toolset
# Local: k8s, kubectl, helm, docker, maven, git, java

# Local docker repository
# docker run -d -p 5005:5000 --name registry registry:2

# Remove existing images related to the app
docker images --format "{{.Repository}}:{{.Tag}} {{.ID}}" | grep "$APP_NAME" | awk '{print $2}' | xargs -r docker rmi -f

# Build project
mvn clean package

# Build docker image
docker build -t $APP_NAME:latest .
docker tag $APP_NAME:latest localhost:5005/$APP_NAME:latest
docker push localhost:5005/$APP_NAME:latest

# List images related to the app
docker images --format "{{.Repository}}:{{.Tag}} {{.ID}}" | grep "$APP_NAME"

# Start application (uncomment if needed)
# mvn spring-boot:run -Dspring-boot.run.profiles=local