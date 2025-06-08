FROM eclipse-temurin:21-alpine

RUN addgroup -g 1064 l64group && \
    adduser -D -u 1064 -G l64group l64user

WORKDIR /home/l64user

COPY --chown=l64user:l64group target/*.jar /home/l64user/app.jar

USER l64user

ENTRYPOINT ["java", "-jar", "/home/l64user/app.jar"]