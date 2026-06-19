"""Splunk Sink - forward audit events to a Splunk HTTP Event Collector (HEC)."""
import logging

import requests

from auditflow_sdk import require_properties

__version__ = "1.0.0"

PROPERTIES = {
    "hec-url": "Full HEC endpoint URL, e.g. https://splunk:8088/services/collector (required)",
    "token": "HEC token (required)",
    "index": "Target index (optional)",
    "sourcetype": "Event sourcetype (default: _json)",
    "source": "Event source (default: auditflow)",
    "verify-ssl": "Verify TLS certificates: true/false (default: true)",
    "timeout": "Request timeout in seconds (default: 10)",
}

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
    """Send a single audit event to a Splunk HEC endpoint."""
    require_properties(properties, "hec-url", "token")

    payload = {
        "event": event_data,
        "sourcetype": properties.get("sourcetype", "_json"),
        "source": properties.get("source", "auditflow"),
    }
    if properties.get("index"):
        payload["index"] = properties["index"]

    headers = {"Authorization": f"Splunk {properties['token']}"}
    verify_ssl = str(properties.get("verify-ssl", "true")).lower() != "false"
    timeout = float(properties.get("timeout", 10))

    response = requests.post(
        properties["hec-url"], headers=headers, json=payload, timeout=timeout, verify=verify_ssl
    )
    response.raise_for_status()
    logger.info("Delivered audit event to Splunk HEC, status=%s", response.status_code)
    return {"delivered": True, "status_code": response.status_code}
