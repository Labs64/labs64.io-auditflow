"""
Syslog Sink - Send events to Syslog server.

This sink sends audit events to a Syslog server using standard syslog protocol.
Supports both UDP and TCP transports.
"""
import logging
import socket
import json
from datetime import datetime, timezone

__version__ = "1.0.0"

PROPERTIES = {
    "host": "Syslog server hostname or IP (required)",
    "port": "Syslog server port (default: 514)",
    "protocol": "Transport: udp or tcp (default: udp)",
    "facility": "Syslog facility: USER, LOCAL0-7, DAEMON, AUTH, ... (default: USER)",
    "severity": "Syslog severity: INFO, WARNING, ERROR, CRITICAL, ... (default: INFO)",
    "tag": "Application tag in the syslog message (default: auditflow)",
    "format": "Message format: json or cef (default: json)",
}

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


def process(event_data: dict, properties: dict) -> dict:
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
    timestamp = datetime.now(timezone.utc).strftime('%b %d %H:%M:%S')
    hostname = socket.gethostname()
    syslog_message = f"<{priority}>{timestamp} {hostname} {tag}: {message}"

    try:
        # Send via UDP or TCP
        if protocol == 'udp':
            send_udp(host, port, syslog_message)
        else:
            send_tcp(host, port, syslog_message)

        logger.info("Event sent to Syslog server %s:%d via %s", host, port, protocol.upper())

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
        logger.error("Failed to send event to Syslog: %s", e)
        raise RuntimeError(f"Failed to send event to Syslog at {host}:{port}: {e}")


def send_udp(host: str, port: int, message: str):
    """Send message via UDP."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(5)
    try:
        sock.sendto(message.encode('utf-8'), (host, port))
    finally:
        sock.close()


def send_tcp(host: str, port: int, message: str):
    """Send message via TCP."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(5)
    try:
        sock.connect((host, port))
        sock.sendall(message.encode('utf-8') + b'\n')
    finally:
        sock.close()


def format_json(event_data: dict) -> str:
    """Format event as JSON string."""
    return json.dumps(event_data, separators=(',', ':'))


def _escape_cef_header(value) -> str:
    """Escape a CEF header field: backslash and pipe only.

    `=` is a delimiter in the extension, not the header, so escaping it here would corrupt a
    perfectly legal name.
    """
    return str(value).replace('\\', '\\\\').replace('|', '\\|')


def _escape_cef_extension(value) -> str:
    """Escape a CEF extension value: backslash, `=`, and newlines.

    `extra` is an open map, so values are arbitrary publisher input. An unescaped `=` silently
    splits one field into two, and a raw newline lets a value forge a second syslog record.
    """
    if isinstance(value, bool):
        # Checked before int — bool is an int subclass, and "True" is not valid JSON.
        text = "true" if value else "false"
    elif isinstance(value, (str, int, float)):
        text = str(value)
    else:
        text = json.dumps(value, separators=(',', ':'), sort_keys=True)
    return (text.replace('\\', '\\\\')
                .replace('=', '\\=')
                .replace('\r\n', '\\n')
                .replace('\n', '\\n')
                .replace('\r', '\\n'))


def format_cef(event_data: dict) -> str:
    """
    Format event as Common Event Format (CEF).
    CEF:Version|Device Vendor|Device Product|Device Version|Signature ID|Name|Severity|Extension

    `extra` is an open map: no key is guaranteed. Absent keys are omitted rather than rendered as
    "unknown" (a value indistinguishable from a publisher that really sent it), and every key the
    formatter does not recognise is passed through as its own extension so nothing is lost.
    """
    extra = event_data.get('extra') or {}

    # CEF header
    cef_version = 0
    device_vendor = "Labs64"
    device_product = "AuditFlow"
    device_version = "1.0"
    signature_id = event_data.get('eventType')
    # `actionName` is a convention, not a guaranteed field — fall back to the event classification,
    # which the contract does require.
    name = extra.get('actionName') or event_data.get('eventType')

    severity = 5  # Medium

    # CEF extension — only fields the event actually carries.
    extensions = []
    for source_key, cef_key in (('sourceSystem', 'src'), ('eventId', 'externalId'),
                                ('correlationId', 'cs1'), ('tenantId', 'cs2')):
        value = event_data.get(source_key)
        if value is not None:
            extensions.append(f"{cef_key}={_escape_cef_extension(value)}")

    for source_key, cef_key in (('actionName', 'act'), ('actionStatus', 'outcome'),
                                ('actionMessage', 'msg'), ('userId', 'suser')):
        value = extra.get(source_key)
        if value is not None:
            extensions.append(f"{cef_key}={_escape_cef_extension(value)}")

    # Everything else in `extra`, under its own key — a deployment's field names must survive.
    _MAPPED = {'actionName', 'actionStatus', 'actionMessage', 'userId'}
    for key, value in extra.items():
        if key in _MAPPED or value is None:
            continue
        extensions.append(f"{_escape_cef_extension(key)}={_escape_cef_extension(value)}")

    extension = ' '.join(extensions)

    return (f"CEF:{cef_version}|{device_vendor}|{device_product}|{device_version}"
            f"|{_escape_cef_header(signature_id or '')}|{_escape_cef_header(name or '')}"
            f"|{severity}|{extension}")
