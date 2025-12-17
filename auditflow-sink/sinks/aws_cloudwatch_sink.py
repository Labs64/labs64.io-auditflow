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

        # Get sequence token
        sequence_token = get_sequence_token(logs_client, log_group, log_stream)

        # Put log event
        logger.info(f"Sending event to CloudWatch Logs: {log_group}/{log_stream}")

        put_kwargs = {
            'logGroupName': log_group,
            'logStreamName': log_stream,
            'logEvents': [
                {
                    'timestamp': timestamp,
                    'message': message
                }
            ]
        }

        if sequence_token:
            put_kwargs['sequenceToken'] = sequence_token

        response = logs_client.put_log_events(**put_kwargs)

        logger.info(f"Event sent to CloudWatch Logs successfully")

        return {
            "sent": True,
            "destination": "cloudwatch",
            "log_group": log_group,
            "log_stream": log_stream,
            "region": region,
            "next_sequence_token": response.get('nextSequenceToken')
        }

    except ClientError as e:
        error_code = e.response['Error']['Code']
        error_message = e.response['Error']['Message']
        logger.error(f"Failed to send to CloudWatch Logs: {error_code} - {error_message}")
        raise RuntimeError(f"Failed to send to CloudWatch Logs: {error_code} - {error_message}")
    except Exception as e:
        logger.error(f"Unexpected error sending to CloudWatch Logs: {e}")
        raise RuntimeError(f"Unexpected error: {e}")


def ensure_log_group(client, log_group: str):
    """Ensure log group exists, create if it doesn't."""
    try:
        response = client.describe_log_groups(logGroupNamePrefix=log_group)
        # Check if the exact log group exists in the response
        log_groups = response.get('logGroups', [])
        exists = any(lg['logGroupName'] == log_group for lg in log_groups)

        if exists:
            logger.debug(f"Log group '{log_group}' already exists")
        else:
            logger.info(f"Creating log group: {log_group}")
            client.create_log_group(logGroupName=log_group)
    except ClientError as e:
        if e.response['Error']['Code'] == 'ResourceAlreadyExistsException':
            logger.debug(f"Log group '{log_group}' already exists")
        else:
            raise


def ensure_log_stream(client, log_group: str, log_stream: str):
    """Ensure log stream exists, create if it doesn't."""
    try:
        response = client.describe_log_streams(
            logGroupName=log_group,
            logStreamNamePrefix=log_stream
        )
        # Check if the exact log stream exists in the response
        log_streams = response.get('logStreams', [])
        exists = any(ls['logStreamName'] == log_stream for ls in log_streams)

        if exists:
            logger.debug(f"Log stream '{log_stream}' already exists")
        else:
            logger.info(f"Creating log stream: {log_stream}")
            client.create_log_stream(
                logGroupName=log_group,
                logStreamName=log_stream
            )
    except ClientError as e:
        if e.response['Error']['Code'] == 'ResourceAlreadyExistsException':
            logger.debug(f"Log stream '{log_stream}' already exists")
        else:
            raise


def get_sequence_token(client, log_group: str, log_stream: str) -> str:
    """Get the current sequence token for the log stream."""
    try:
        response = client.describe_log_streams(
            logGroupName=log_group,
            logStreamNamePrefix=log_stream
        )
        streams = response.get('logStreams', [])
        if streams:
            return streams[0].get('uploadSequenceToken')
    except ClientError:
        pass
    return None
