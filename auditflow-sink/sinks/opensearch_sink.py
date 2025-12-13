"""
OpenSearch Sink - Send events to OpenSearch/Elasticsearch.

This sink sends transformed audit events to an OpenSearch cluster.
"""
import logging
import requests
import json
from datetime import datetime
from requests.auth import HTTPBasicAuth

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
    """
    Process an audit event by sending it to OpenSearch.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - service-url: OpenSearch base URL (required)
            - service-path: Index path (default: /auditflow/_doc)
            - username: Basic auth username (optional)
            - password: Basic auth password (optional)
            - verify-ssl: Verify SSL certificates (default: true)

    Returns:
        dict: Processing result with OpenSearch response
    """
    # Validate required properties
    service_url = properties.get('service-url')
    if not service_url:
        raise ValueError("Missing required property: 'service-url'")

    # Get configuration
    service_path = properties.get('service-path', '/auditflow/_doc')
    username = properties.get('username')
    password = properties.get('password')
    verify_ssl = properties.get('verify-ssl', 'true').lower() == 'true'

    # Build full URL
    full_url = f"{service_url.rstrip('/')}{service_path}"

    # Prepare headers
    headers = {
        'Content-Type': 'application/json'
    }

    # Add timestamp if not present
    if 'meta' in event_data and 'timestamp' not in event_data['meta']:
        event_data['meta']['timestamp'] = datetime.utcnow().isoformat() + 'Z'

    # Prepare authentication
    auth = None
    if username and password:
        auth = HTTPBasicAuth(username, password)

    try:
        # Send event to OpenSearch
        logger.info(f"Sending event to OpenSearch: {full_url}")
        response = requests.post(
            full_url,
            json=event_data,
            headers=headers,
            auth=auth,
            verify=verify_ssl,
            timeout=10
        )

        response.raise_for_status()

        # Parse response
        result = response.json()

        logger.info(f"Event sent to OpenSearch successfully. Document ID: {result.get('_id', 'unknown')}")

        return {
            "sent": True,
            "destination": "opensearch",
            "url": full_url,
            "document_id": result.get('_id'),
            "index": result.get('_index'),
            "result": result.get('result'),
            "status_code": response.status_code
        }

    except requests.exceptions.RequestException as e:
        logger.error(f"Failed to send event to OpenSearch: {e}")
        raise RuntimeError(f"Failed to send event to OpenSearch at {full_url}: {e}")
    except Exception as e:
        logger.error(f"Unexpected error sending event to OpenSearch: {e}")
        raise RuntimeError(f"Unexpected error: {e}")
