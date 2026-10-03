"""
AWS CloudWatch Logs Sink - Send events to AWS CloudWatch Logs.

This sink sends audit events to CloudWatch Logs for centralized logging and monitoring.
"""
import logging
import json
import time

from auditflow_sdk import RejectedEvent, chunk_indexes

__version__ = "1.1.0"

PROPERTIES = {
    "log-group": "CloudWatch Logs log group name (required)",
    "log-stream": "Log stream name (default: auditflow)",
    "region": "AWS region (default: us-east-1)",
    "access-key-id": "AWS access key ID (optional, uses default credential chain if omitted)",
    "secret-access-key": "AWS secret access key (optional)",
    "create-log-group": "Auto-create log group if missing: true/false (default: true)",
    "create-log-stream": "Auto-create log stream if missing: true/false (default: true)",
}

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


# Limits of PutLogEvents per call: 10,000 events and 1,048,576 bytes, counting 26 bytes per event on
# top of its message.
_MAX_EVENTS_PER_CALL = 10_000
_MAX_BYTES_PER_CALL = 1_000_000
_EVENT_OVERHEAD_BYTES = 26


def process_batch(events: list, properties: dict) -> list:
    """Send a batch of events with as few PutLogEvents calls as its limits allow (10,000 events, 1 MB).

    The log group and stream are checked once per batch instead of once per event. A call is
    accepted or refused as a whole: the events of a refused call are retried. CloudWatch Logs keeps
    every event it accepts, so a redelivered event appears twice: de-duplicate by ``eventId``.
    """
    if boto3 is None:
        raise RuntimeError("boto3 library is required. Install with: pip install boto3")
    log_group = properties.get('log-group')
    if not log_group:
        raise ValueError("Missing required property: 'log-group'")
    log_stream = properties.get('log-stream', 'auditflow')
    region = properties.get('region', 'us-east-1')

    session_kwargs = {'region_name': region}
    if properties.get('access-key-id') and properties.get('secret-access-key'):
        session_kwargs['aws_access_key_id'] = properties['access-key-id']
        session_kwargs['aws_secret_access_key'] = properties['secret-access-key']

    try:
        logs_client = boto3.client('logs', **session_kwargs)
        if properties.get('create-log-group', 'true').lower() == 'true':
            ensure_log_group(logs_client, log_group)
        if properties.get('create-log-stream', 'true').lower() == 'true':
            ensure_log_stream(logs_client, log_group, log_stream)
    except Exception as e:  # noqa: BLE001 - nothing was sent: every event failed
        logger.error("Failed to prepare CloudWatch Logs %s/%s: %s", log_group, log_stream, e)
        return [RuntimeError(f"Failed to send to CloudWatch Logs: {e}")] * len(events)

    timestamp = int(time.time() * 1000)  # milliseconds; one value keeps the call in time order
    messages = [json.dumps(event) for event in events]
    sizes = [len(message.encode('utf-8')) + _EVENT_OVERHEAD_BYTES for message in messages]
    outcomes = [None] * len(events)
    for indexes in chunk_indexes(sizes, _MAX_EVENTS_PER_CALL, _MAX_BYTES_PER_CALL):
        try:
            response = logs_client.put_log_events(
                logGroupName=log_group,
                logStreamName=log_stream,
                logEvents=[{'timestamp': timestamp, 'message': messages[i]} for i in indexes],
            )
        except Exception as e:  # noqa: BLE001 - the call failed: every event in it failed
            logger.error("Failed to send %d event(s) to CloudWatch Logs: %s", len(indexes), e)
            for i in indexes:
                outcomes[i] = RuntimeError(f"Failed to send to CloudWatch Logs: {e}")
            continue
        refused = _refused_positions(response.get('rejectedLogEventsInfo') or {}, len(indexes))
        for position, i in enumerate(indexes):
            if position in refused:
                outcomes[i] = RejectedEvent("CloudWatch Logs refused the event: its timestamp is outside the "
                                            "accepted range")
            else:
                outcomes[i] = {"sent": True, "destination": "cloudwatch", "log_group": log_group,
                               "log_stream": log_stream, "region": region}
        logger.info("Sent %d event(s) to CloudWatch Logs: %s/%s", len(indexes) - len(refused), log_group, log_stream)
    return outcomes


def _refused_positions(info: dict, count: int) -> set:
    """Positions within one call that CloudWatch Logs did not store (too old, expired or too new)."""
    refused = set()
    for key in ('tooOldLogEventEndIndex', 'expiredLogEventEndIndex'):
        if isinstance(info.get(key), int):
            refused.update(range(0, min(info[key], count)))  # exclusive end index
    if isinstance(info.get('tooNewLogEventStartIndex'), int):
        refused.update(range(max(info['tooNewLogEventStartIndex'], 0), count))
    return refused


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
