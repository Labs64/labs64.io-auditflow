FROM eclipse-temurin:25-alpine

LABEL org.opencontainers.image.title="Labs64.IO :: AuditFlow" \
      org.opencontainers.image.description="Scalable audit logging backend for modern microservices" \
      org.opencontainers.image.url="https://labs64.io" \
      org.opencontainers.image.source="https://github.com/Labs64/labs64.io-auditflow" \
      org.opencontainers.image.licenses="LGPL-3.0"

# Define build arguments
ARG USER_ID=1064
ARG GROUP_ID=1064
ARG USER_NAME=l64user
ARG GROUP_NAME=l64group

# Create group and user with the specified IDs
RUN addgroup -g ${GROUP_ID} ${GROUP_NAME} && \
    adduser -D -u ${USER_ID} -G ${GROUP_NAME} ${USER_NAME}

# Set working directory
WORKDIR /home/${USER_NAME}

# Copy the jar file with appropriate ownership
COPY --chown=${USER_NAME}:${GROUP_NAME} target/*.jar /home/${USER_NAME}/app.jar

# Set user context
USER ${USER_NAME}

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

# Default command — path uses the literal value of USER_NAME (l64user)
ENTRYPOINT ["/bin/sh", "-c", "java $JAVA_OPTS -jar /home/l64user/app.jar"]
