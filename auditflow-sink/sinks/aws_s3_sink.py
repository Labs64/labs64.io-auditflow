"""
AWS S3 Sink - Store events in Amazon S3.

This sink uploads audit events to AWS S3 as JSON objects.
Supports batching, compression, and partitioning by date.
"""
import logging
import json
import gzip
from datetime import datetime
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
    Process an audit event by uploading it to AWS S3.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - bucket: S3 bucket name (required)
            - prefix: Object key prefix/folder (default: auditflow/)
            - region: AWS region (default: us-east-1)
            - access-key-id: AWS access key (optional, uses default credentials if not provided)
            - secret-access-key: AWS secret key (optional)
            - compress: Enable gzip compression (default: false)
            - partition-by-date: Partition by date (default: true)
            - partition-format: Date format for partitioning (default: year=%Y/month=%m/day=%d/)
            - file-format: File format - json or jsonl (default: json)
            - endpoint-url: Custom S3 endpoint URL (optional, for S3-compatible storage)

    Returns:
        dict: Processing result with S3 details
    """
    if boto3 is None:
        raise RuntimeError("boto3 library is required. Install with: pip install boto3")

    # Validate required properties
    bucket = properties.get('bucket')
    if not bucket:
        raise ValueError("Missing required property: 'bucket'")

    # Get configuration
    prefix = properties.get('prefix', 'auditflow/')
    region = properties.get('region', 'us-east-1')
    access_key_id = properties.get('access-key-id')
    secret_access_key = properties.get('secret-access-key')
    compress = properties.get('compress', 'false').lower() == 'true'
    partition_by_date = properties.get('partition-by-date', 'true').lower() == 'true'
    partition_format = properties.get('partition-format', 'year=%Y/month=%m/day=%d/')
    file_format = properties.get('file-format', 'json').lower()
    endpoint_url = properties.get('endpoint-url')

    # Create S3 client
    session_kwargs = {'region_name': region}
    if access_key_id and secret_access_key:
        session_kwargs['aws_access_key_id'] = access_key_id
        session_kwargs['aws_secret_access_key'] = secret_access_key

    if endpoint_url:
        s3_client = boto3.client('s3', endpoint_url=endpoint_url, **session_kwargs)
    else:
        s3_client = boto3.client('s3', **session_kwargs)

    # Build object key
    object_key = build_object_key(
        prefix,
        partition_by_date,
        partition_format,
        file_format,
        compress,
        event_data
    )

    # Prepare content
    if file_format == 'jsonl':
        content = json.dumps(event_data) + '\n'
    else:
        content = json.dumps(event_data, indent=2)

    # Compress if needed
    if compress:
        content_bytes = gzip.compress(content.encode('utf-8'))
        content_type = 'application/gzip'
    else:
        content_bytes = content.encode('utf-8')
        content_type = 'application/json'

    try:
        # Upload to S3
        logger.info(f"Uploading event to S3: s3://{bucket}/{object_key}")

        response = s3_client.put_object(
            Bucket=bucket,
            Key=object_key,
            Body=content_bytes,
            ContentType=content_type,
            Metadata={
                'event-type': event_data.get('eventType', 'unknown'),
                'source-system': event_data.get('sourceSystem', 'unknown'),
                'timestamp': datetime.utcnow().isoformat()
            }
        )

        logger.info(f"Event uploaded to S3 successfully. ETag: {response.get('ETag')}")

        return {
            "sent": True,
            "destination": "s3",
            "bucket": bucket,
            "key": object_key,
            "region": region,
            "compressed": compress,
            "size_bytes": len(content_bytes),
            "etag": response.get('ETag'),
            "version_id": response.get('VersionId')
        }

    except ClientError as e:
        error_code = e.response['Error']['Code']
        error_message = e.response['Error']['Message']
        logger.error(f"Failed to upload to S3: {error_code} - {error_message}")
        raise RuntimeError(f"Failed to upload to S3 bucket '{bucket}': {error_code} - {error_message}")
    except Exception as e:
        logger.error(f"Unexpected error uploading to S3: {e}")
        raise RuntimeError(f"Unexpected error: {e}")


def build_object_key(
    prefix: str,
    partition_by_date: bool,
    partition_format: str,
    file_format: str,
    compress: bool,
    event_data: dict
) -> str:
    """Build S3 object key with optional date partitioning."""
    key_parts = [prefix.rstrip('/')]

    # Add date partition
    if partition_by_date:
        date_part = datetime.utcnow().strftime(partition_format)
        key_parts.append(date_part.rstrip('/'))

    # Generate unique filename
    event_id = event_data.get('eventId', str(uuid.uuid4()))
    timestamp = datetime.utcnow().strftime('%Y%m%d-%H%M%S')

    extension = 'json'
    if compress:
        extension += '.gz'

    filename = f"{timestamp}-{event_id[:8]}.{extension}"
    key_parts.append(filename)

    return '/'.join(key_parts)
