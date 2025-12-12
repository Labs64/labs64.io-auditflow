"""
Loki Sink - Send events to Grafana Loki for log aggregation.

This sink sends transformed audit events to Grafana Loki.
"""
import logging
import requests
import json
import time
from datetime import datetime
from requests.auth import HTTPBasicAuth
from typing import Dict, Any, List

logger = logging.getLogger(__name__)


def process(event_data: Dict[str, Any], properties: Dict[str, str]) -> Dict[str, Any]:
    """
    Process an audit event by sending it to Grafana Loki.

    Args:
        event_data: The transformed audit event data (should be in Loki format)
        properties: Configuration properties
            - service-url: Loki base URL (required)
            - service-path: Push path (default: /loki/api/v1/push)
            - username: Basic auth username (optional)
            - password: Basic auth password (optional)
            - tenant-id: X-Scope-OrgID header for multi-tenancy (optional)

    Returns:
        dict: Processing result with Loki response
    """
    # Validate required properties
    service_url = properties.get('service-url')
    if not service_url:
        raise ValueError("Missing required property: 'service-url'")

    # Get configuration
    service_path = properties.get('service-path', '/loki/api/v1/push')
    username = properties.get('username')
    password = properties.get('password')
    tenant_id = properties.get('tenant-id')

    # Build full URL
    full_url = f"{service_url.rstrip('/')}{service_path}"

    # Prepare headers
    headers = {
        'Content-Type': 'application/json'
    }

    # Add tenant ID if provided
    if tenant_id:
        headers['X-Scope-OrgID'] = tenant_id

    # Prepare authentication
    auth = None
    if username and password:
        auth = HTTPBasicAuth(username, password)

    # Ensure event_data is in Loki format
    # If it's already in Loki format (has 'streams'), use as-is
    # Otherwise, wrap it
    if 'streams' not in event_data:
        # Convert to Loki format
        timestamp_ns = str(int(time.time() * 1000000000))  # nanoseconds

        # Extract labels from top-level fields
        labels = {
            'job': 'auditflow',
            'event_type': event_data.get('eventType', 'unknown'),
            'source_system': event_data.get('sourceSystem', 'unknown'),
        }

        # Build label string
        label_str = ','.join([f'{k}="{v}"' for k, v in labels.items()])

        loki_data = {
            'streams': [
                {
                    'stream': labels,
                    'values': [
                        [timestamp_ns, json.dumps(event_data)]
                    ]
                }
            ]
        }
    else:
        loki_data = event_data

    try:
        # Send event to Loki
        logger.info(f"Sending event to Loki: {full_url}")
        response = requests.post(
            full_url,
            json=loki_data,
            headers=headers,
            auth=auth,
            timeout=10
        )

        response.raise_for_status()

        logger.info(f"Event sent to Loki successfully. Status: {response.status_code}")

        return {
            "sent": True,
            "destination": "loki",
            "url": full_url,
            "status_code": response.status_code,
            "streams_count": len(loki_data.get('streams', []))
        }

    except requests.exceptions.RequestException as e:
        logger.error(f"Failed to send event to Loki: {e}")
        raise RuntimeError(f"Failed to send event to Loki at {full_url}: {e}")
    except Exception as e:
        logger.error(f"Unexpected error sending event to Loki: {e}")
        raise RuntimeError(f"Unexpected error: {e}")

