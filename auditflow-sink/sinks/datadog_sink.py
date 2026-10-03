"""Datadog Sink - forward audit events to the Datadog Logs intake API."""
import json
import logging

import requests

from auditflow_sdk import chunk_indexes, require_properties

__version__ = "1.1.0"

PROPERTIES = {
    "api-key": "Datadog API key (required)",
    "site": "Datadog site, e.g. datadoghq.com or datadoghq.eu (default: datadoghq.com)",
    "service": "Value for the 'service' field (default: auditflow)",
    "source": "Value for 'ddsource' (default: auditflow)",
    "tags": "Comma-separated ddtags (optional)",
    "timeout": "Request timeout in seconds (default: 10)",
}

logger = logging.getLogger(__name__)


# Limits of the Logs intake API per request: 1000 entries and 5 MB uncompressed.
_MAX_ENTRIES = 1000
_MAX_BYTES = 4_500_000


def process(event_data: dict, properties: dict) -> dict:
    """Send a single audit event to the Datadog Logs intake API."""
    return _send([event_data], properties)


def process_batch(events: list, properties: dict) -> list:
    """Send a batch of events in as few requests as the intake limits allow (1000 entries, 5 MB).

    A request is accepted or refused as a whole: the events of a refused request are retried.
    """
    require_properties(properties, "api-key")
    # The event travels as a JSON string inside the entry, so its quotes are escaped once more.
    sizes = [len(json.dumps(json.dumps(event))) + 200 for event in events]
    outcomes = [None] * len(events)
    for indexes in chunk_indexes(sizes, _MAX_ENTRIES, _MAX_BYTES):
        try:
            result = _send([events[i] for i in indexes], properties)
        except Exception as e:  # noqa: BLE001 - the request failed: every event in it failed
            logger.error("Datadog refused a request of %d event(s): %s", len(indexes), e)
            result = RuntimeError(f"Failed to send to Datadog: {e}")
        for i in indexes:
            outcomes[i] = result
    return outcomes


def _send(events: list, properties: dict) -> dict:
    require_properties(properties, "api-key")

    site = properties.get("site", "datadoghq.com")
    url = f"https://http-intake.logs.{site}/api/v2/logs"
    entries = [{
        "ddsource": properties.get("source", "auditflow"),
        "service": properties.get("service", "auditflow"),
        "ddtags": properties.get("tags", ""),
        "message": json.dumps(event),
    } for event in events]
    headers = {"DD-API-KEY": properties["api-key"], "Content-Type": "application/json"}
    timeout = float(properties.get("timeout", 10))

    response = requests.post(url, headers=headers, json=entries, timeout=timeout)
    response.raise_for_status()
    logger.info("Delivered %d audit event(s) to Datadog (%s), status=%s", len(events), site, response.status_code)
    return {"delivered": True, "status_code": response.status_code, "site": site}
