"""Splunk Sink - forward audit events to a Splunk HTTP Event Collector (HEC)."""
import json
import logging

import requests

from auditflow_sdk import deliver_each, require_properties

__version__ = "1.1.0"

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
    return _send([event_data], properties)


def process_batch(events: list, properties: dict) -> list:
    """Send a batch of events in ONE HEC request (event objects one after another in the body).

    If HEC answers 400 (it names the first event it could not read), the events are sent one by one
    instead, so the others are indexed and only the unreadable one fails. HEC may already have
    indexed the events before that one, so they can appear twice: de-duplicate by ``eventId``. Any
    other failure fails the whole batch, which the backend retries.
    """
    require_properties(properties, "hec-url", "token")
    try:
        result = _send(events, properties)
    except requests.exceptions.HTTPError as e:
        if e.response is not None and e.response.status_code == 400 and len(events) > 1:
            logger.warning("Splunk HEC refused a batch of %d event(s) (%s); sending them one by one",
                           len(events), e.response.text.strip()[:200])
            return deliver_each(events, properties, process)
        return [RuntimeError(f"Failed to send to Splunk HEC: {e}")] * len(events)
    except Exception as e:  # noqa: BLE001 - the request failed: every event failed
        return [RuntimeError(f"Failed to send to Splunk HEC: {e}")] * len(events)
    return [result] * len(events)


def _send(events: list, properties: dict) -> dict:
    require_properties(properties, "hec-url", "token")

    envelope = {
        "sourcetype": properties.get("sourcetype", "_json"),
        "source": properties.get("source", "auditflow"),
    }
    if properties.get("index"):
        envelope["index"] = properties["index"]

    headers = {"Authorization": f"Splunk {properties['token']}"}
    verify_ssl = str(properties.get("verify-ssl", "true")).lower() != "false"
    timeout = float(properties.get("timeout", 10))

    if len(events) == 1:
        response = requests.post(
            properties["hec-url"], headers=headers, json={"event": events[0], **envelope},
            timeout=timeout, verify=verify_ssl
        )
    else:
        # HEC reads several events from one request as JSON objects one after another.
        body = "\n".join(json.dumps({"event": event, **envelope}) for event in events).encode("utf-8")
        response = requests.post(
            properties["hec-url"], headers={**headers, "Content-Type": "application/json"}, data=body,
            timeout=timeout, verify=verify_ssl
        )
    response.raise_for_status()
    logger.info("Delivered %d audit event(s) to Splunk HEC, status=%s", len(events), response.status_code)
    return {"delivered": True, "status_code": response.status_code}
