from datetime import datetime

def transform(input_data):
    """
    Transforms a Labs64.IO AuditFlow JSON structure into a Loki-compatible payload.

    This function is intended to be dynamically loaded by the FastAPI application
    when the transformer_id matches this module (e.g., if this file is named 'loki_transformer.py'
    and the request is to /transform/loki_transformer).

    Example Input (expected from FastAPI endpoint):
    {
      "meta": {
        "timestamp": "2023-10-27T10:00:00Z",
        "correlationId": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
        "eventType": "audit.action.performed",
        "sourceSystem": "system-name/service-name",
        "eventVersion": "1.0.0",
        "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
        "tenantId": "tenant-001"
      },
      "action": {
        "name": "LOGIN_SUCCESS",
        "status": "SUCCESS",
        "message": "User logged in successfully"
      },
      "geolocation": {
        "lat": 48.1351,
        "lon": 11.5820,
        "city": "Munich",
        "region": "Bavaria",
        "country": "Germany",
        "countryCode": "DE"
      },
      "parameters": {
        "userId": "user123",
        "browser": "Chrome"
      },
      "rawData": "{\"original_event\": \"some_data\"}"
    }

    Example Output (Loki payload):
    {
      "streams": [
        {
          "stream": {
            "job": "audit-flow-transformer",
            "source_system": "system-name/service-name",
            "tenant_id": "tenant-001",
            "event_type": "audit.action.performed",
            "action_name": "LOGIN_SUCCESS",
            "action_status": "SUCCESS",
            "country_code": "DE",
            "event_version": "1.0.0"
          },
          "values": [
            [ "1698391200000000000", "ACTION:LOGIN_SUCCESS STATUS:SUCCESS USER:user123 {\"meta\": {\"timestamp\": \"2023-10-27T10:00:00Z\", ...}}" ]
          ]
        }
      ]
    }
    """
    meta = input_data.get('meta', {})
    action = input_data.get('action', {})
    geolocation = input_data.get('geolocation', {})
    parameters = input_data.get('parameters', {})
    rawData = input_data.get('rawData', None)

    # 1. Prepare Labels
    labels = {
        "job": "auditflow",
        "source_system": meta.get("sourceSystem", "unknown"),
        "tenant_id": meta.get("tenantId", "unknown"),
        "event_type": meta.get("eventType", "unknown"),
        "action_name": action.get("name", "unknown_action"),
        "action_status": action.get("status", "unknown_status"),
        "country_code": geolocation.get("countryCode", "unknown"),
        "event_version": meta.get("eventVersion", "1.0.0"),
        # IMPORTANT: Be cautious adding high-cardinality fields like 'userId' as labels.
        # It's generally better to include them in the log line content.
        # "user_id": parameters.get("userId", "anonymous")
    }

    # 2. Prepare Log Line (Value)
    log_line_prefix = (
        f"meta.correlationId:{meta.get('correlationId', 'N/A')} "
        f"action.name:{action.get('name', 'N/A')} "
        f"action.status:{action.get('status', 'N/A')} "
        f"parameters.userId:{parameters.get('userId', 'N/A')} "
        f"action.message:{action.get('message', 'N/A')} "
    )
    # Convert the entire original audit_json to a JSON string for the log line.
    log_line = log_line_prefix + rawData

    # 3. Prepare Timestamp (Unix nanoseconds)
    timestamp_iso = meta.get("timestamp")
    if timestamp_iso:
        try:
            dt_object = datetime.fromisoformat(timestamp_iso.replace('Z', '+00:00'))
            unix_nano_timestamp = str(int(dt_object.timestamp() * 1_000_000_000))
        except ValueError:
            dt_object = datetime.now() # Current time is 2025-06-23 14:00:29
            unix_nano_timestamp = str(int(dt_object.timestamp() * 1_000_000_000))
    else:
        dt_object = datetime.now() # Current time is 2025-06-23 14:00:29
        unix_nano_timestamp = str(int(dt_object.timestamp() * 1_000_000_000))

    # Construct the Loki payload structure
    loki_payload = {
        "streams": [
            {
                "stream": labels,
                "values": [
                    [unix_nano_timestamp, log_line]
                ]
            }
        ]
    }

    return loki_payload
