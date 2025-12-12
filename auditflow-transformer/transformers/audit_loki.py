from datetime import datetime

status_to_level_mapping = {
    "SUCCESS": "INFO",
    "FAILURE": "ERROR",
    "PENDING": "WARN"
}

def get_log_level(status):
    """
    Maps a status string to a log level string.
    """
    return status_to_level_mapping.get(status.upper(), "UNKNOWN")

def transform(input_data):
    """
    Transforms a Labs64.IO AuditFlow JSON structure into a Loki-compatible payload.

    This function is intended to be dynamically loaded by the FastAPI application
    when the transformer_id matches this module (e.g., if this file is named 'loki_transformer.py'
    and the request is to /transform/loki_transformer).

    Example Input (expected from FastAPI endpoint):
    {
      "timestamp": "2025-07-04T10:00:00Z",
      "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
      "eventType": "audit.action.performed",
      "sourceSystem": "system-name/service-name",
      "tenantId": "tenant-001",
      "geolocation": {
        "lat": 48.1351,
        "lon": 11.5820,
        "city": "Munich",
        "region": "Bavaria",
        "country": "Germany",
        "countryCode": "DE"
      },
      "extra": {
        "userId": "user123",
        "browser": "Chrome",
        "action_name": "LOGIN_SUCCESS",
        "action_status": "SUCCESS",
        "action_message": "User logged in successfully"
      }
    }

    Example Output (Loki payload):
    {
      "streams": [
        {
          "stream": {
            "job": "auditflow",
            "service_name": "netlicensing/core",
            "tenant_id": "V12345678",
            "event_type": "api.call",
            "action_name": "licensee/validate",
            "action_status": "SUCCESS"
          },
          "values": [
            [
              "1756157127354000128",
              "Validation completed successfully",
              {
                "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
                "level": "INFO",
                "userId": "customer123",
                "country_code": "DE",
                "latitude": "48.1264019",
                "longitude": "11.5407647"
              }
            ]
          ]
        }
      ]
    }
    """
    geolocation = input_data.get('geolocation', {})
    extra = input_data.get('extra', {})

    # Prepare the "stream" object based on the desired output structure
    stream_data = {
        "job": "auditflow",
        "service_name": input_data.get("sourceSystem", "unknown"),
        "tenant_id": input_data.get("tenantId", "unknown"),
        "event_type": input_data.get("eventType", "unknown"),
        "action_name": extra.get("action_name", "unknown_action"),
        "action_status": extra.get("action_status", "unknown_status"),
    }

    # Prepare the nested dictionary for the "values" array
    values_dict = {
        "eventId": input_data.get("eventId", "N/A"),
        "level": get_log_level(extra.get("action_status", "N/A")),
        "userId": extra.get("userId", "N/A"),
        "country_code": geolocation.get("countryCode", "N/A"),
        "latitude": f'{geolocation.get("lat", "N/A")}',
        "longitude": f'{geolocation.get("lon", "N/A")}',
    }

    # Prepare the "values" array
    # The timestamp needs to be a string in Unix nanoseconds
    timestamp_iso = input_data.get("timestamp")
    unix_nano_timestamp = "0"
    if timestamp_iso:
        try:
            from datetime import datetime
            dt_object = datetime.fromisoformat(timestamp_iso.replace('Z', '+00:00'))
            unix_nano_timestamp = str(int(dt_object.timestamp() * 1_000_000_000))
        except ValueError:
            # Handle invalid timestamp format, e.g., by using a default or current time
            pass

    values_data = [
        unix_nano_timestamp,
        extra.get("action_message", "N/A"),
        values_dict
    ]

    # Construct the final nested output structure
    loki_payload = {
        "streams": [
            {
                "stream": stream_data,
                "values": [values_data]
            }
        ]
    }

    return loki_payload
