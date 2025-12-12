"""
Webhook Sink - Send events to HTTP webhooks (Zapier, Make, n8n, etc.).

This sink sends audit events to webhook URLs, supporting various webhook platforms.
"""
import logging
import requests
import json
import hmac
import hashlib
from typing import Dict, Any
from datetime import datetime

logger = logging.getLogger(__name__)


def process(event_data: Dict[str, Any], properties: Dict[str, str]) -> Dict[str, Any]:
    """
    Process an audit event by sending it to a webhook.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - webhook-url: Webhook URL (required)
            - method: HTTP method - GET or POST (default: POST)
            - content-type: Content-Type header (default: application/json)
            - headers: Additional headers as JSON string (optional)
            - secret: Secret for HMAC signature (optional)
            - signature-header: Header name for signature (default: X-Hub-Signature-256)
            - timeout: Request timeout in seconds (default: 30)
            - verify-ssl: Verify SSL certificates (default: true)
            - retry-count: Number of retries (default: 3)

    Returns:
        dict: Processing result with webhook response
    """
    # Validate required properties
    webhook_url = properties.get('webhook-url')
    if not webhook_url:
        raise ValueError("Missing required property: 'webhook-url'")

    # Get configuration
    method = properties.get('method', 'POST').upper()
    content_type = properties.get('content-type', 'application/json')
    timeout = int(properties.get('timeout', '30'))
    verify_ssl = properties.get('verify-ssl', 'true').lower() == 'true'
    retry_count = int(properties.get('retry-count', '3'))
    secret = properties.get('secret')
    signature_header = properties.get('signature-header', 'X-Hub-Signature-256')

    # Parse additional headers
    headers = {
        'Content-Type': content_type,
        'User-Agent': 'Labs64-AuditFlow/1.0'
    }

    additional_headers = properties.get('headers')
    if additional_headers:
        try:
            headers.update(json.loads(additional_headers))
        except json.JSONDecodeError:
            logger.warning(f"Failed to parse additional headers: {additional_headers}")

    # Prepare payload
    payload = prepare_payload(event_data, content_type)

    # Add signature if secret is provided
    if secret:
        signature = generate_signature(payload, secret)
        headers[signature_header] = signature

    # Send webhook with retries
    last_error = None
    for attempt in range(retry_count):
        try:
            logger.info(f"Sending webhook to {webhook_url} (attempt {attempt + 1}/{retry_count})")

            if method == 'POST':
                response = requests.post(
                    webhook_url,
                    data=payload if content_type != 'application/json' else None,
                    json=event_data if content_type == 'application/json' else None,
                    headers=headers,
                    timeout=timeout,
                    verify=verify_ssl
                )
            elif method == 'GET':
                response = requests.get(
                    webhook_url,
                    params=flatten_dict(event_data),
                    headers=headers,
                    timeout=timeout,
                    verify=verify_ssl
                )
            else:
                raise ValueError(f"Unsupported HTTP method: {method}")

            response.raise_for_status()

            logger.info(f"Webhook sent successfully. Status: {response.status_code}")

            return {
                "sent": True,
                "destination": "webhook",
                "url": webhook_url,
                "method": method,
                "status_code": response.status_code,
                "response_time_ms": int(response.elapsed.total_seconds() * 1000),
                "attempt": attempt + 1,
                "response_body": response.text[:200] if response.text else None
            }

        except requests.exceptions.RequestException as e:
            last_error = e
            logger.warning(f"Webhook attempt {attempt + 1} failed: {e}")
            if attempt < retry_count - 1:
                # Exponential backoff
                import time
                time.sleep(2 ** attempt)
            continue

    # All retries failed
    logger.error(f"Failed to send webhook after {retry_count} attempts: {last_error}")
    raise RuntimeError(f"Failed to send webhook to {webhook_url}: {last_error}")


def prepare_payload(event_data: Dict[str, Any], content_type: str) -> str:
    """Prepare payload based on content type."""
    if content_type == 'application/json':
        return json.dumps(event_data)
    elif content_type == 'application/x-www-form-urlencoded':
        from urllib.parse import urlencode
        return urlencode(flatten_dict(event_data))
    else:
        return json.dumps(event_data)


def generate_signature(payload: str, secret: str) -> str:
    """
    Generate HMAC signature for webhook verification (GitHub/Zapier style).
    Uses SHA-256 hash.
    """
    signature = hmac.new(
        secret.encode('utf-8'),
        payload.encode('utf-8'),
        hashlib.sha256
    ).hexdigest()
    return f"sha256={signature}"


def flatten_dict(d: Dict[str, Any], parent_key: str = '', sep: str = '.') -> Dict[str, str]:
    """Flatten nested dictionary for URL encoding."""
    items = []
    for k, v in d.items():
        new_key = f"{parent_key}{sep}{k}" if parent_key else k
        if isinstance(v, dict):
            items.extend(flatten_dict(v, new_key, sep=sep).items())
        elif isinstance(v, list):
            items.append((new_key, json.dumps(v)))
        else:
            items.append((new_key, str(v)))
    return dict(items)

