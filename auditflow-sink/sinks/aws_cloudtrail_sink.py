"""
AWS CloudTrail Sink - Send events to AWS CloudTrail Lake.

This sink sends audit events to AWS CloudTrail Lake for long-term audit storage and analysis.
"""
import logging
import json
import uuid

logger = logging.getLogger(__name__)

try:
    import boto3
    from botocore.exceptions import ClientError
except ImportError:
    logger.error("boto3 is not installed. Install with: pip install boto3")
    boto3 = None


def process(event_data: dict, properties: dict) -> dict:
    """
    Process an audit event by sending it to CloudTrail Lake.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - channel-arn: CloudTrail Lake channel ARN (required)
            - region: AWS region (default: us-east-1)
            - access-key-id: AWS access key (optional)
            - secret-access-key: AWS secret key (optional)

    Returns:
        dict: Processing result with CloudTrail details
    """
    if boto3 is None:
        raise RuntimeError("boto3 library is required. Install with: pip install boto3")

    # Validate required properties
    channel_arn = properties.get('channel-arn')
    if not channel_arn:
        raise ValueError("Missing required property: 'channel-arn'")

    # Get configuration
    region = properties.get('region', 'us-east-1')
    access_key_id = properties.get('access-key-id')
    secret_access_key = properties.get('secret-access-key')

    # Create CloudTrail client
    session_kwargs = {'region_name': region}
    if access_key_id and secret_access_key:
        session_kwargs['aws_access_key_id'] = access_key_id
        session_kwargs['aws_secret_access_key'] = secret_access_key

    cloudtrail_client = boto3.client('cloudtrail-data', **session_kwargs)

    # Convert to CloudTrail format
    cloudtrail_event = convert_to_cloudtrail_format(event_data)

    try:
        # Send to CloudTrail Lake
        logger.info(f"Sending event to CloudTrail Lake channel: {channel_arn}")

        response = cloudtrail_client.put_audit_events(
            auditEvents=[cloudtrail_event],
            channelArn=channel_arn
        )

        successful = response.get('successful', [])
        failed = response.get('failed', [])

        if failed:
            error = failed[0]
            raise RuntimeError(f"CloudTrail ingestion failed: {error.get('errorCode')} - {error.get('errorMessage')}")

        result_id = successful[0].get('id') if successful else None
        logger.info(f"Event sent to CloudTrail Lake successfully. ID: {result_id}")

        return {
            "sent": True,
            "destination": "cloudtrail",
            "channel_arn": channel_arn,
            "region": region,
            "event_id": result_id
        }

    except ClientError as e:
        error_code = e.response['Error']['Code']
        error_message = e.response['Error']['Message']
        logger.error(f"Failed to send to CloudTrail Lake: {error_code} - {error_message}")
        raise RuntimeError(f"Failed to send to CloudTrail Lake: {error_code} - {error_message}")
    except Exception as e:
        logger.error(f"Unexpected error sending to CloudTrail Lake: {e}")
        raise RuntimeError(f"Unexpected error: {e}")


def convert_to_cloudtrail_format(event_data: dict) -> dict:
    """
    Convert AuditFlow event to CloudTrail format.

    CloudTrail Lake requires specific format:
    - eventData: The actual event payload (JSON string)
    - id: Unique event ID (UUID)
    """
    meta = event_data.get('meta', {})
    action = event_data.get('action', {})

    # Generate event ID
    event_id = meta.get('eventId', str(uuid.uuid4()))

    # Build CloudTrail event
    cloudtrail_event = {
        'id': event_id,
        'eventData': json.dumps(event_data)
    }

    return cloudtrail_event
