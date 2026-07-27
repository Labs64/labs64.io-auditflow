FROM eclipse-temurin:25-jre

ARG GIT_REVISION=unknown

LABEL org.opencontainers.image.title="Labs64.IO :: AuditFlow" \
      org.opencontainers.image.description="Scalable audit logging backend for modern microservices" \
      org.opencontainers.image.url="https://labs64.io" \
      org.opencontainers.image.source="https://github.com/Labs64/labs64.io-auditflow" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.revision="${GIT_REVISION}" \
      io.labs64.edition="ce"

# Define build arguments
ARG USER_ID=1064
ARG GROUP_ID=1064
ARG USER_NAME=l64user
ARG GROUP_NAME=l64group

# Create group and user with the specified IDs
RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/* && \
    groupadd -g ${GROUP_ID} ${GROUP_NAME} && \
    useradd -m -u ${USER_ID} -g ${GROUP_NAME} -s /bin/bash ${USER_NAME}

# OpenTelemetry Java Agent — bundled but inert. Deployment config enables it via
# JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar (observability is an
# infrastructure concern; same image runs with and without it).
ARG OTEL_JAVAAGENT_VERSION=2.29.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
RUN chmod 444 /otel/opentelemetry-javaagent.jar

# Extension directory. The jar is built with the ZIP layout, so it launches through
# PropertiesLauncher and anything on `loader.path` joins the application classpath at
# startup — jars dropped in here contribute beans, auto-configuration, property
# defaults and spring.factories entries without rebuilding the image.
#
# It ships EMPTY and INERT: with nothing in it, `loader.path` is not set at all and
# startup is exactly the previous fat-jar behaviour. This is the Java counterpart of
# the transformer/sink services' *_bootstrap/ directories, available to any plugin
# author — it knows nothing about who might use it.
ENV LABS64_EXT_DIR=/opt/labs64/ext
RUN mkdir -p ${LABS64_EXT_DIR} && chown ${USER_NAME}:${GROUP_NAME} ${LABS64_EXT_DIR}

# Set working directory
WORKDIR /home/${USER_NAME}

# Copy the jar file with appropriate ownership
COPY --chown=${USER_NAME}:${GROUP_NAME} target/*.jar /home/${USER_NAME}/app.jar

# Set user context
USER ${USER_NAME}

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1

# Default command — path uses the literal value of USER_NAME (l64user).
#
# `loader.path` is expanded from the extension directory at startup rather than being
# set to the directory itself: PropertiesLauncher treats a directory entry as a
# classpath *root* and does not scan it for jars (verified against Boot 4.1), and it
# does not glob, so `/opt/labs64/ext` and `/opt/labs64/ext/*.jar` both silently load
# nothing. Listing the jars explicitly is what actually works.
#
# With an empty directory the `${EXT:+...}` expansion contributes no flag at all, so
# CE startup is unchanged. `exec` keeps java as PID 1 so it receives signals directly.
ENTRYPOINT ["/bin/sh", "-c", "EXT=$(ls -1 ${LABS64_EXT_DIR}/*.jar 2>/dev/null | tr '\\n' ',' | sed 's/,$//'); exec java $JAVA_OPTS ${EXT:+-Dloader.path=\"$EXT\"} -jar /home/l64user/app.jar"]
