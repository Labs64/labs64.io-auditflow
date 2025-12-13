"""
Google Cloud Storage Sink - Store events in Google Cloud Storage.

This sink uploads audit events to GCS as JSON objects.
"""
import logging
import json
import gzip
from datetime import datetime
import uuid

logger = logging.getLogger(__name__)

try:
    from google.cloud import storage
    from google.cloud.exceptions import GoogleCloudError
except ImportError:
    logger.error("google-cloud-storage is not installed. Install with: pip install google-cloud-storage")
    storage = None


def process(event_data: dict, properties: dict) -> dict:
    """
    Process an audit event by uploading it to Google Cloud Storage.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - bucket: GCS bucket name (required)
            - prefix: Object key prefix/folder (default: auditflow/)
            - project-id: GCP project ID (optional, uses default if not provided)
            - credentials-file: Path to service account JSON file (optional)
            - compress: Enable gzip compression (default: false)
            - partition-by-date: Partition by date (default: true)
            - partition-format: Date format for partitioning (default: year=%Y/month=%m/day=%d/)
            - content-type: Content type (default: application/json)

    Returns:
        dict: Processing result with GCS details
    """
    if storage is None:
        raise RuntimeError("google-cloud-storage library is required. Install with: pip install google-cloud-storage")

    # Validate required properties
    bucket_name = properties.get('bucket')
    if not bucket_name:
        raise ValueError("Missing required property: 'bucket'")

    # Get configuration
    prefix = properties.get('prefix', 'auditflow/')
    project_id = properties.get('project-id')
    credentials_file = properties.get('credentials-file')
    compress = properties.get('compress', 'false').lower() == 'true'
    partition_by_date = properties.get('partition-by-date', 'true').lower() == 'true'
    partition_format = properties.get('partition-format', 'year=%Y/month=%m/day=%d/')
    content_type = properties.get('content-type', 'application/json')

    # Create GCS client
    if credentials_file:
        client = storage.Client.from_service_account_json(credentials_file, project=project_id)
    else:
        client = storage.Client(project=project_id)

    # Get bucket
    try:
        bucket = client.bucket(bucket_name)

        # Build object name
        object_name = build_object_name(
            prefix,
            partition_by_date,
            partition_format,
            compress,
            event_data
        )

        # Prepare content
        content = json.dumps(event_data, indent=2)

        # Compress if needed
        if compress:
            content_bytes = gzip.compress(content.encode('utf-8'))
            final_content_type = 'application/gzip'
        else:
            content_bytes = content.encode('utf-8')
            final_content_type = content_type

        # Create blob
        blob = bucket.blob(object_name)

        # Set metadata
        blob.metadata = {
            'event-type': event_data.get('meta', {}).get('eventType', 'unknown'),
            'source-system': event_data.get('meta', {}).get('sourceSystem', 'unknown'),
            'timestamp': datetime.utcnow().isoformat()
        }

        # Upload
        logger.info(f"Uploading event to GCS: gs://{bucket_name}/{object_name}")

        blob.upload_from_string(
            content_bytes,
            content_type=final_content_type
        )

        logger.info(f"Event uploaded to GCS successfully")

        return {
            "sent": True,
            "destination": "gcs",
            "bucket": bucket_name,
            "object": object_name,
            "compressed": compress,
            "size_bytes": len(content_bytes),
            "generation": blob.generation,
            "public_url": blob.public_url
        }

    except GoogleCloudError as e:
        logger.error(f"Failed to upload to GCS: {e}")
        raise RuntimeError(f"Failed to upload to GCS bucket '{bucket_name}': {e}")
    except Exception as e:
        logger.error(f"Unexpected error uploading to GCS: {e}")
        raise RuntimeError(f"Unexpected error: {e}")


def build_object_name(
    prefix: str,
    partition_by_date: bool,
    partition_format: str,
    compress: bool,
    event_data: dict
) -> str:
    """Build GCS object name with optional date partitioning."""
    name_parts = [prefix.rstrip('/')]

    # Add date partition
    if partition_by_date:
        date_part = datetime.utcnow().strftime(partition_format)
        name_parts.append(date_part.rstrip('/'))

    # Generate unique filename
    event_id = event_data.get('meta', {}).get('eventId', str(uuid.uuid4()))
    timestamp = datetime.utcnow().strftime('%Y%m%d-%H%M%S')

    extension = 'json'
    if compress:
        extension += '.gz'

    filename = f"{timestamp}-{event_id[:8]}.{extension}"
    name_parts.append(filename)

    return '/'.join(name_parts)
