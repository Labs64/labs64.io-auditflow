"""
Google Cloud Storage Sink - Store events in Google Cloud Storage.

This sink uploads audit events to GCS as JSON objects.
"""
import logging
import json
import gzip
from datetime import datetime, timezone
import uuid

from auditflow_sdk import batch_object_name, event_datetime, jsonl_body

__version__ = "1.1.0"

PROPERTIES = {
    "bucket": "GCS bucket name (required)",
    "prefix": "Object prefix/folder (default: auditflow/)",
    "project-id": "GCP project ID (optional, uses application default if omitted)",
    "credentials-file": "Path to a service account JSON key file (optional)",
    "compress": "Enable gzip compression: true/false (default: false)",
    "partition-by-date": "Partition objects by date: true/false (default: true)",
    "partition-format": "strftime pattern for date partitioning (default: year=%Y/month=%m/day=%d/)",
    "content-type": "Content-Type for the uploaded object (default: application/json)",
}

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
            'event-type': event_data.get('eventType', 'unknown'),
            'source-system': event_data.get('sourceSystem', 'unknown'),
            'timestamp': datetime.now(timezone.utc).isoformat()
        }

        # Upload
        logger.info("Uploading event to GCS: gs://%s/%s", bucket_name, object_name)

        blob.upload_from_string(
            content_bytes,
            content_type=final_content_type
        )

        logger.info("Event uploaded to GCS successfully")

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
        logger.error("Failed to upload to GCS: %s", e)
        raise RuntimeError(f"Failed to upload to GCS bucket '{bucket_name}': {e}")
    except Exception as e:
        logger.error("Unexpected error uploading to GCS: %s", e)
        raise RuntimeError(f"Unexpected error: {e}")


def process_batch(events: list, properties: dict) -> list:
    """Write a batch of events as one JSON Lines object per folder.

    Events that share a folder (the same date partition) go into ONE object, so a batch costs one
    upload instead of one per event. The object name is the earliest event time plus a hash of the
    group's event ids: re-delivering the same group overwrites the same object. A different grouping
    on redelivery can repeat an event in a second object, so readers de-duplicate by ``eventId``.
    """
    if storage is None:
        raise RuntimeError("google-cloud-storage library is required. Install with: pip install google-cloud-storage")
    bucket_name = properties.get('bucket')
    if not bucket_name:
        raise ValueError("Missing required property: 'bucket'")
    prefix = properties.get('prefix', 'auditflow/')
    project_id = properties.get('project-id')
    credentials_file = properties.get('credentials-file')
    compress = properties.get('compress', 'false').lower() == 'true'
    partition_by_date = properties.get('partition-by-date', 'true').lower() == 'true'
    partition_format = properties.get('partition-format', 'year=%Y/month=%m/day=%d/')

    try:
        if credentials_file:
            client = storage.Client.from_service_account_json(credentials_file, project=project_id)
        else:
            client = storage.Client(project=project_id)
        bucket = client.bucket(bucket_name)
    except Exception as e:  # noqa: BLE001 - nothing was written: every event failed
        logger.error("Failed to open GCS bucket '%s': %s", bucket_name, e)
        return [RuntimeError(f"Failed to upload to GCS bucket '{bucket_name}': {e}")] * len(events)

    groups = {}
    for index, event in enumerate(events):
        name = build_object_name(prefix, partition_by_date, partition_format, compress, event)
        groups.setdefault(name.rsplit('/', 1)[0], []).append(index)

    outcomes = [None] * len(events)
    for folder, indexes in groups.items():
        group = [events[i] for i in indexes]
        object_name = f"{folder}/{batch_object_name(group, compress)}"
        body = jsonl_body(group, compress)
        try:
            blob = bucket.blob(object_name)
            blob.metadata = {'event-count': str(len(group))}
            blob.upload_from_string(body, content_type='application/gzip' if compress else 'application/x-ndjson')
            logger.info("Uploaded batch of %d event(s) to gs://%s/%s", len(group), bucket_name, object_name)
            result = {"sent": True, "destination": "gcs", "bucket": bucket_name, "object": object_name}
        except Exception as e:  # noqa: BLE001 - the group's upload failed: every event in it failed
            logger.error("Failed to upload batch object gs://%s/%s: %s", bucket_name, object_name, e)
            result = RuntimeError(f"Failed to upload to GCS bucket '{bucket_name}': {e}")
        for i in indexes:
            outcomes[i] = result
    return outcomes


def build_object_name(
    prefix: str,
    partition_by_date: bool,
    partition_format: str,
    compress: bool,
    event_data: dict
) -> str:
    """Build GCS object name with optional date partitioning."""
    name_parts = [prefix.rstrip('/')]

    # The event's own receipt time, not the upload time: a redelivered event then gets the same
    # name and rewrites its object instead of adding a copy.
    now = event_datetime(event_data)

    # Add date partition
    if partition_by_date:
        date_part = now.strftime(partition_format)
        name_parts.append(date_part.rstrip('/'))

    # One object per event: the full eventId makes the name unique. A shortened id is not: with
    # time-ordered ids (UUIDv7) every event of the same second shares its first characters.
    event_id = event_data.get('eventId', str(uuid.uuid4()))
    timestamp = now.strftime('%Y%m%d-%H%M%S')

    extension = 'json'
    if compress:
        extension += '.gz'

    filename = f"{timestamp}-{event_id}.{extension}"
    name_parts.append(filename)

    return '/'.join(name_parts)
