"""
Syslog Sink - Send events to Syslog server.

This sink sends audit events to a Syslog server using standard syslog protocol.
Supports both UDP and TCP transports.
"""
import logging
import socket
import json
from datetime import datetime
from typing import Dict, Any

logger = logging.getLogger(__name__)


# Syslog severity levels
SEVERITY = {
    'EMERGENCY': 0,
    'ALERT': 1,
    'CRITICAL': 2,
    'ERROR': 3,
    'WARNING': 4,
    'NOTICE': 5,
    'INFO': 6,
    'DEBUG': 7
}

# Syslog facility codes
FACILITY = {
    'KERN': 0,
    'USER': 1,
    'MAIL': 2,
    'DAEMON': 3,
    'AUTH': 4,
    'SYSLOG': 5,
    'LPR': 6,
    'NEWS': 7,
    'UUCP': 8,
    'CRON': 9,
    'AUTHPRIV': 10,
    'LOCAL0': 16,
    'LOCAL1': 17,
    'LOCAL2': 18,
    'LOCAL3': 19,
    'LOCAL4': 20,
    'LOCAL5': 21,
    'LOCAL6': 22,
    'LOCAL7': 23
}


def process(event_data: Dict[str, Any], properties: Dict[str, str]) -> Dict[str, Any]:
    """
    Process an audit event by sending it to Syslog.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - host: Syslog server host (required)
            - port: Syslog server port (default: 514)
            - protocol: Transport protocol - 'udp' or 'tcp' (default: udp)
            - facility: Syslog facility (default: USER)
            - severity: Syslog severity (default: INFO)
            - tag: Application tag (default: auditflow)
            - format: Message format - 'json' or 'cef' (default: json)

    Returns:
        dict: Processing result with syslog details
    """
    # Validate required properties
    host = properties.get('host')
    if not host:
        raise ValueError("Missing required property: 'host'")

    # Get configuration
    port = int(properties.get('port', '514'))
    protocol = properties.get('protocol', 'udp').lower()
    facility = properties.get('facility', 'USER').upper()
    severity = properties.get('severity', 'INFO').upper()
    tag = properties.get('tag', 'auditflow')
    msg_format = properties.get('format', 'json').lower()

    # Validate protocol
    if protocol not in ['udp', 'tcp']:
        raise ValueError(f"Invalid protocol: {protocol}. Must be 'udp' or 'tcp'")

    # Calculate priority
    facility_code = FACILITY.get(facility, FACILITY['USER'])
    severity_code = SEVERITY.get(severity, SEVERITY['INFO'])
    priority = (facility_code * 8) + severity_code

    # Format message
    if msg_format == 'json':
        message = format_json(event_data)
    elif msg_format == 'cef':
        message = format_cef(event_data)
    else:
        message = json.dumps(event_data)

    # Build syslog message (RFC 3164 format)
    timestamp = datetime.utcnow().strftime('%b %d %H:%M:%S')
    hostname = socket.gethostname()
    syslog_message = f"<{priority}>{timestamp} {hostname} {tag}: {message}"

    try:
        # Send via UDP or TCP
        if protocol == 'udp':
            send_udp(host, port, syslog_message)
        else:
            send_tcp(host, port, syslog_message)

        logger.info(f"Event sent to Syslog server {host}:{port} via {protocol.upper()}")

        return {
            "sent": True,
            "destination": "syslog",
            "host": host,
            "port": port,
            "protocol": protocol,
            "facility": facility,
            "severity": severity,
            "message_length": len(syslog_message)
        }

    except Exception as e:
        logger.error(f"Failed to send event to Syslog: {e}")
        raise RuntimeError(f"Failed to send event to Syslog at {host}:{port}: {e}")


def send_udp(host: str, port: int, message: str):
    """Send message via UDP."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.sendto(message.encode('utf-8'), (host, port))
    finally:
        sock.close()


def send_tcp(host: str, port: int, message: str):
    """Send message via TCP."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        sock.connect((host, port))
        sock.sendall(message.encode('utf-8') + b'\n')
    finally:
        sock.close()


def format_json(event_data: Dict[str, Any]) -> str:
    """Format event as JSON string."""
    return json.dumps(event_data, separators=(',', ':'))


def format_cef(event_data: Dict[str, Any]) -> str:
    """
    Format event as Common Event Format (CEF).
    CEF:Version|Device Vendor|Device Product|Device Version|Signature ID|Name|Severity|Extension
    """
    extra = event_data.get('extra', {})

    # CEF header
    cef_version = 0
    device_vendor = "Labs64"
    device_product = "AuditFlow"
    device_version = "1.0"
    signature_id = event_data.get('eventType', 'unknown')
    name = extra.get('action_name', 'unknown')
    severity = 5  # Medium

    # CEF extension
    extensions = []
    extensions.append(f"src={event_data.get('sourceSystem', 'unknown')}")
    extensions.append(f"act={extra.get('action_name', 'unknown')}")
    extensions.append(f"outcome={extra.get('action_status', 'unknown')}")

    if 'eventId' in event_data:
        extensions.append(f"externalId={event_data['eventId']}")

    extension = ' '.join(extensions)

    return f"CEF:{cef_version}|{device_vendor}|{device_product}|{device_version}|{signature_id}|{name}|{severity}|{extension}"

