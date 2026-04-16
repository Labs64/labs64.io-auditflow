"""
AWS CloudWatch Logs Sink - Send events to AWS CloudWatch Logs.

This sink sends audit events to CloudWatch Logs for centralized logging and monitoring.
"""
import logging
import json
import time

logger = logging.getLogger(__name__)

try:
    import boto3
    from botocore.exceptions import ClientError
except ImportError:
    logger.error("boto3 is not installed. Install with: pip install boto3")
    boto3 = None


def process(event_data: dict, properties: dict) -> dict:
    """
    Process an audit event by sending it to CloudWatch Logs.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - log-group: CloudWatch log group name (required)
            - log-stream: CloudWatch log stream name (default: auditflow)
            - region: AWS region (default: us-east-1)
            - access-key-id: AWS access key (optional)
            - secret-access-key: AWS secret key (optional)
            - create-log-group: Auto-create log group if not exists (default: true)
            - create-log-stream: Auto-create log stream if not exists (default: true)

    Returns:
        dict: Processing result with CloudWatch details
    """
    if boto3 is None:
        raise RuntimeError("boto3 library is required. Install with: pip install boto3")

    # Validate required properties
    log_group = properties.get('log-group')
    if not log_group:
        raise ValueError("Missing required property: 'log-group'")

    # Get configuration
    log_stream = properties.get('log-stream', 'auditflow')
    region = properties.get('region', 'us-east-1')
    access_key_id = properties.get('access-key-id')
    secret_access_key = properties.get('secret-access-key')
    create_log_group = properties.get('create-log-group', 'true').lower() == 'true'
    create_log_stream = properties.get('create-log-stream', 'true').lower() == 'true'

    # Create CloudWatch Logs client
    session_kwargs = {'region_name': region}
    if access_key_id and secret_access_key:
        session_kwargs['aws_access_key_id'] = access_key_id
        session_kwargs['aws_secret_access_key'] = secret_access_key

    logs_client = boto3.client('logs', **session_kwargs)

    try:
        # Ensure log group exists
        if create_log_group:
            ensure_log_group(logs_client, log_group)

        # Ensure log stream exists
        if create_log_stream:
            ensure_log_stream(logs_client, log_group, log_stream)

        # Prepare log event
        timestamp = int(time.time() * 1000)  # milliseconds
        message = json.dumps(event_data)

        # Put log event
        logger.info("Sending event to CloudWatch Logs: %s/%s", log_group, log_stream)

        response = logs_client.put_log_events(
            logGroupName=log_group,
            logStreamName=log_stream,
            logEvents=[
                {
                    'timestamp': timestamp,
                    'message': message
                }
            ]
        )

        logger.info("Event sent to CloudWatch Logs successfully")

        return {
            "sent": True,
            "destination": "cloudwatch",
            "log_group": log_group,
            "log_stream": log_stream,
            "region": region
        }

    except ClientError as e:
        error_code = e.response['Error']['Code']
        error_message = e.response['Error']['Message']
        logger.error("Failed to send to CloudWatch Logs: %s - %s", error_code, error_message)
        raise RuntimeError(f"Failed to send to CloudWatch Logs: {error_code} - {error_message}")
    except Exception as e:
        logger.error("Unexpected error sending to CloudWatch Logs: %s", e)
        raise RuntimeError(f"Unexpected error: {e}")


def ensure_log_group(client, log_group: str):
    """Ensure log group exists, create if it doesn't."""
    try:
        response = client.describe_log_groups(logGroupNamePrefix=log_group)
        log_groups = response.get('logGroups', [])
        exists = any(lg['logGroupName'] == log_group for lg in log_groups)

        if exists:
            logger.debug("Log group '%s' already exists", log_group)
        else:
            logger.info("Creating log group: %s", log_group)
            client.create_log_group(logGroupName=log_group)
    except ClientError as e:
        if e.response['Error']['Code'] == 'ResourceAlreadyExistsException':
            logger.debug("Log group '%s' already exists", log_group)
        else:
            raise


def ensure_log_stream(client, log_group: str, log_stream: str):
    """Ensure log stream exists, create if it doesn't."""
    try:
        response = client.describe_log_streams(
            logGroupName=log_group,
            logStreamNamePrefix=log_stream
        )
        log_streams = response.get('logStreams', [])
        exists = any(ls['logStreamName'] == log_stream for ls in log_streams)

        if exists:
            logger.debug("Log stream '%s' already exists", log_stream)
        else:
            logger.info("Creating log stream: %s", log_stream)
            client.create_log_stream(
                logGroupName=log_group,
                logStreamName=log_stream
            )
    except ClientError as e:
        if e.response['Error']['Code'] == 'ResourceAlreadyExistsException':
            logger.debug("Log stream '%s' already exists", log_stream)
        else:
            raise
