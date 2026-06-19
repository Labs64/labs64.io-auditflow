"""Datadog Sink - forward audit events to the Datadog Logs intake API."""
import json
import logging

import requests

from auditflow_sdk import require_properties

__version__ = "1.0.0"

PROPERTIES = {
    "api-key": "Datadog API key (required)",
    "site": "Datadog site, e.g. datadoghq.com or datadoghq.eu (default: datadoghq.com)",
    "service": "Value for the 'service' field (default: auditflow)",
    "source": "Value for 'ddsource' (default: auditflow)",
    "tags": "Comma-separated ddtags (optional)",
    "timeout": "Request timeout in seconds (default: 10)",
}

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
    """Send a single audit event to the Datadog Logs intake API."""
    require_properties(properties, "api-key")

    site = properties.get("site", "datadoghq.com")
    url = f"https://http-intake.logs.{site}/api/v2/logs"
    log_entry = {
        "ddsource": properties.get("source", "auditflow"),
        "service": properties.get("service", "auditflow"),
        "ddtags": properties.get("tags", ""),
        "message": json.dumps(event_data),
    }
    headers = {"DD-API-KEY": properties["api-key"], "Content-Type": "application/json"}
    timeout = float(properties.get("timeout", 10))

    response = requests.post(url, headers=headers, json=[log_entry], timeout=timeout)
    response.raise_for_status()
    logger.info("Delivered audit event to Datadog (%s), status=%s", site, response.status_code)
    return {"delivered": True, "status_code": response.status_code, "site": site}
