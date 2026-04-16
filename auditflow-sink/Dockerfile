FROM python:3.13-alpine

LABEL org.opencontainers.image.title="Labs64.IO :: AuditFlow - Sink Service" \
      org.opencontainers.image.description="Pluggable event sink service for AuditFlow" \
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

# Create the sinks subfolder in the container
RUN mkdir -p /home/${USER_NAME}/sinks

# Copy the requirements file and install dependencies in a single layer
COPY --chown=${USER_NAME}:${GROUP_NAME} requirements.txt .
RUN python -m pip install --upgrade pip --no-cache-dir && \
    pip install --no-cache-dir -r requirements.txt

# Copy application files (after deps so layer is cached on code-only changes)
COPY --chown=${USER_NAME}:${GROUP_NAME} sink.py .
COPY --chown=${USER_NAME}:${GROUP_NAME} sinks/ /home/${USER_NAME}/sinks/

# sinks_bootstrap is intended to be mounted as a Kubernetes volume at runtime.
# Example: kubectl set volume deployment/auditflow-sink --add \
#            --name=sinks-bootstrap --mount-path=/home/l64user/sinks_bootstrap \
#            --type=configmap --configmap-name=custom-sinks

# Make port 8082 available outside this container
EXPOSE 8082

# Set user context
USER ${USER_NAME}

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8082/sinks || exit 1

# Run the FastAPI application using Uvicorn
CMD ["uvicorn", "sink:app", "--host", "0.0.0.0", "--port", "8082"]
