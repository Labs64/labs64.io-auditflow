"""
Azure Blob Storage Sink - Store events in Azure Blob Storage.

This sink uploads audit events to Azure Blob Storage as JSON objects.
"""
import logging
import json
import gzip
from datetime import datetime
from typing import Dict, Any
import uuid

logger = logging.getLogger(__name__)

try:
    from azure.storage.blob import BlobServiceClient, ContentSettings
    from azure.core.exceptions import AzureError
except ImportError:
    logger.error("azure-storage-blob is not installed. Install with: pip install azure-storage-blob")
    BlobServiceClient = None


def process(event_data: Dict[str, Any], properties: Dict[str, str]) -> Dict[str, Any]:
    """
    Process an audit event by uploading it to Azure Blob Storage.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - container: Azure container name (required)
            - connection-string: Azure storage connection string (required if not using account-key)
            - account-name: Storage account name (required if using account-key)
            - account-key: Storage account key (required if using account-key)
            - prefix: Blob prefix/folder (default: auditflow/)
            - compress: Enable gzip compression (default: false)
            - partition-by-date: Partition by date (default: true)
            - partition-format: Date format for partitioning (default: year=%Y/month=%m/day=%d/)
            - content-type: Content type (default: application/json)

    Returns:
        dict: Processing result with Azure Blob Storage details
    """
    if BlobServiceClient is None:
        raise RuntimeError("azure-storage-blob library is required. Install with: pip install azure-storage-blob")

    # Validate required properties
    container_name = properties.get('container')
    if not container_name:
        raise ValueError("Missing required property: 'container'")

    # Get connection details
    connection_string = properties.get('connection-string')
    account_name = properties.get('account-name')
    account_key = properties.get('account-key')

    if not connection_string and not (account_name and account_key):
        raise ValueError("Either 'connection-string' or both 'account-name' and 'account-key' are required")

    # Get configuration
    prefix = properties.get('prefix', 'auditflow/')
    compress = properties.get('compress', 'false').lower() == 'true'
    partition_by_date = properties.get('partition-by-date', 'true').lower() == 'true'
    partition_format = properties.get('partition-format', 'year=%Y/month=%m/day=%d/')
    content_type = properties.get('content-type', 'application/json')

    # Create Blob Service Client
    if connection_string:
        blob_service_client = BlobServiceClient.from_connection_string(connection_string)
    else:
        account_url = f"https://{account_name}.blob.core.windows.net"
        from azure.storage.blob import BlobServiceClient
        blob_service_client = BlobServiceClient(
            account_url=account_url,
            credential=account_key
        )

    try:
        # Get container client
        container_client = blob_service_client.get_container_client(container_name)

        # Ensure container exists
        if not container_client.exists():
            logger.info(f"Creating container: {container_name}")
            container_client.create_container()

        # Build blob name
        blob_name = build_blob_name(
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

        # Get blob client
        blob_client = container_client.get_blob_client(blob_name)

        # Set metadata
        metadata = {
            'event_type': event_data.get('eventType', 'unknown'),
            'source_system': event_data.get('sourceSystem', 'unknown'),
            'timestamp': datetime.utcnow().isoformat()
        }

        # Upload
        logger.info(f"Uploading event to Azure Blob Storage: {container_name}/{blob_name}")

        blob_client.upload_blob(
            content_bytes,
            overwrite=True,
            content_settings=ContentSettings(content_type=final_content_type),
            metadata=metadata
        )

        logger.info(f"Event uploaded to Azure Blob Storage successfully")

        # Get blob properties
        blob_properties = blob_client.get_blob_properties()

        return {
            "sent": True,
            "destination": "azure_blob",
            "container": container_name,
            "blob": blob_name,
            "compressed": compress,
            "size_bytes": len(content_bytes),
            "etag": blob_properties.etag,
            "last_modified": blob_properties.last_modified.isoformat() if blob_properties.last_modified else None
        }

    except AzureError as e:
        logger.error(f"Failed to upload to Azure Blob Storage: {e}")
        raise RuntimeError(f"Failed to upload to Azure Blob Storage container '{container_name}': {e}")
    except Exception as e:
        logger.error(f"Unexpected error uploading to Azure Blob Storage: {e}")
        raise RuntimeError(f"Unexpected error: {e}")


def build_blob_name(
    prefix: str,
    partition_by_date: bool,
    partition_format: str,
    compress: bool,
    event_data: Dict[str, Any]
) -> str:
    """Build blob name with optional date partitioning."""
    name_parts = [prefix.rstrip('/')]

    # Add date partition
    if partition_by_date:
        date_part = datetime.utcnow().strftime(partition_format)
        name_parts.append(date_part.rstrip('/'))

    # Generate unique filename
    event_id = event_data.get('eventId', str(uuid.uuid4()))
    timestamp = datetime.utcnow().strftime('%Y%m%d-%H%M%S')

    extension = 'json'
    if compress:
        extension += '.gz'

    filename = f"{timestamp}-{event_id[:8]}.{extension}"
    name_parts.append(filename)

    return '/'.join(name_parts)

