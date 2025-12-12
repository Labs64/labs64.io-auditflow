FROM python:3-alpine

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

# Create the transformers subfolder in the container
RUN mkdir -p /home/${USER_NAME}/transformers

# Copy the requirements file into the container at the working directory
COPY --chown=${USER_NAME}:${GROUP_NAME} requirements.txt .

# Copy the main FastAPI application file
COPY --chown=${USER_NAME}:${GROUP_NAME} transformer.py .

# Copy internal transformation files into their subfolder
COPY --chown=${USER_NAME}:${GROUP_NAME} transformers/ /home/${USER_NAME}/transformers/

# TEST ONLY: Copy the transformers_bootstrap folder into the container
# This folder is intended to be mapped as a Kubernetes volume in a production setup
#RUN mkdir -p /home/${USER_NAME}/transformers_bootstrap
#COPY --chown=${USER_NAME}:${GROUP_NAME} transformers_bootstrap/ /home/${USER_NAME}/transformers_bootstrap/

# Install any needed packages specified in requirements.txt
RUN python -m pip install --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Make port 8081 available outside this container
EXPOSE 8081

# Set user context
USER ${USER_NAME}

# Run the FastAPI application using Uvicorn
CMD ["uvicorn", "transformer:app", "--host", "0.0.0.0", "--port", "8081"]
