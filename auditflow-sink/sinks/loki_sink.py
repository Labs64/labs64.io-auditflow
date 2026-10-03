"""
Loki Sink - Send events to Grafana Loki for log aggregation.

This sink sends transformed audit events to Grafana Loki.
"""
import logging
import requests
import json
import time
from requests.auth import HTTPBasicAuth

from auditflow_sdk import deliver_each

__version__ = "1.1.0"

PROPERTIES = {
    "service-url": "Loki base URL, e.g. http://loki:3100 (required)",
    "service-path": "Push path (default: /loki/api/v1/push)",
    "username": "Basic auth username (optional)",
    "password": "Basic auth password (optional)",
    "tenant-id": "X-Scope-OrgID header for multi-tenant Loki (optional)",
}

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
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

    loki_data = _to_loki(event_data)

    try:
        # Send event to Loki
        logger.info("Sending event to Loki: %s", full_url)
        response = requests.post(
            full_url,
            json=loki_data,
            headers=headers,
            auth=auth,
            timeout=10
        )

        response.raise_for_status()

        logger.info("Event sent to Loki successfully. Status: %s", response.status_code)

        return {
            "sent": True,
            "destination": "loki",
            "url": full_url,
            "status_code": response.status_code,
            "streams_count": len(loki_data.get('streams', []))
        }

    except requests.exceptions.RequestException as e:
        logger.error("Failed to send event to Loki: %s", e)
        raise RuntimeError(f"Failed to send event to Loki at {full_url}: {e}")
    except Exception as e:
        logger.error("Unexpected error sending event to Loki: %s", e)
        raise RuntimeError(f"Unexpected error: {e}")


def process_batch(events: list, properties: dict) -> list:
    """Push a batch of events to Loki in ONE request.

    Entries with the same labels are merged into one stream and ordered by time. If Loki answers
    400 (it refuses an entry, for example one that is too old), the events are pushed one by one
    instead, so the others are stored and only the refused one fails. Loki drops an entry it already
    has (same stream, time and line), so that repeat adds nothing for events shaped by the
    ``audit_loki`` transformer. Any other failure fails the whole batch, which the backend retries.
    """
    service_url = properties.get('service-url')
    if not service_url:
        raise ValueError("Missing required property: 'service-url'")
    full_url = f"{service_url.rstrip('/')}{properties.get('service-path', '/loki/api/v1/push')}"
    headers = {'Content-Type': 'application/json'}
    if properties.get('tenant-id'):
        headers['X-Scope-OrgID'] = properties['tenant-id']
    username = properties.get('username')
    password = properties.get('password')
    auth = HTTPBasicAuth(username, password) if username and password else None

    streams = {}
    for event in events:
        for stream in _to_loki(event).get('streams', []):
            labels = stream.get('stream', {})
            merged = streams.setdefault(json.dumps(labels, sort_keys=True), {'stream': labels, 'values': []})
            merged['values'].extend(stream.get('values', []))
    for merged in streams.values():
        merged['values'].sort(key=_entry_time)

    try:
        logger.info("Sending %d event(s) to Loki: %s", len(events), full_url)
        response = requests.post(full_url, json={'streams': list(streams.values())}, headers=headers,
                                 auth=auth, timeout=10)
        response.raise_for_status()
    except requests.exceptions.HTTPError as e:
        if e.response is not None and e.response.status_code == 400 and len(events) > 1:
            logger.warning("Loki refused a batch of %d event(s) (%s); pushing them one by one",
                           len(events), e.response.text.strip()[:200])
            return deliver_each(events, properties, process)
        return [RuntimeError(f"Failed to send events to Loki at {full_url}: {e}")] * len(events)
    except Exception as e:  # noqa: BLE001 - the request failed: every event failed
        return [RuntimeError(f"Failed to send events to Loki at {full_url}: {e}")] * len(events)

    result = {"sent": True, "destination": "loki", "url": full_url, "status_code": response.status_code,
              "streams_count": len(streams)}
    return [result] * len(events)


def _to_loki(event_data: dict) -> dict:
    """The event as a Loki push payload: as it is when a transformer already shaped it (it has
    ``streams``), wrapped in one stream otherwise."""
    if 'streams' in event_data:
        return event_data
    timestamp_ns = str(int(time.time() * 1000000000))  # nanoseconds
    labels = {
        'job': 'auditflow',
        'event_type': event_data.get('eventType', 'unknown'),
        'source_system': event_data.get('sourceSystem', 'unknown'),
    }
    return {'streams': [{'stream': labels, 'values': [[timestamp_ns, json.dumps(event_data)]]}]}


def _entry_time(entry) -> int:
    try:
        return int(entry[0])
    except (TypeError, ValueError, IndexError):
        return 0
