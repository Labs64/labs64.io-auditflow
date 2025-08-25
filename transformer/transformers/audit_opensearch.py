def transform(input_data):
    """
    Transforms an AuditEvent JSON object into a flattened, OpenSearch-friendly format.

    Args:
        input_data (dict): The incoming AuditEvent JSON payload.

    Returns:
        dict: A transformed dictionary with key audit event details,
              optimized for OpenSearch ingestion (e.g., geo_point format).

    Example Input (for /transform/audit_event):
    {
      "meta": {
        "timestamp": "2025-07-04T10:00:00Z",
        "correlationId": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
        "eventType": "audit.action.performed",
        "sourceSystem": "system-name/service-name",
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
      "extra": {
        "userId": "user123",
        "browser": "Chrome"
      },
      "rawData": "{\"original_event\": \"some_data\"}"
    }

    Example Output (OpenSearch friendly):
    {
      "timestamp": "2025-07-04T10:00:00Z",
      "event_id": "fedcba98-7654-3210-fedc-ba9876543210",
      "correlation_id": "a1b2c3d4-e5f6-7890-1234-567890abcdef",
      "event_type": "audit.action.performed",
      "source_system": "system-name/service-name",
      "event_version": "1.0.0",
      "tenant_id": "tenant-001",
      "action_name": "LOGIN_SUCCESS",
      "action_status": "SUCCESS",
      "action_message": "User logged in successfully",
      "location": {
        "lat": 48.1351,
        "lon": 11.5820
      },
      "location_city": "Munich",
      "location_region": "Bavaria",
      "location_country": "Germany",
      "location_country_code": "DE",
      "extra": {
        "userId": "user123",
        "browser": "Chrome"
      },
      "raw_data_string": "{\"original_event\": \"some_data\"}"
    }
    """
    transformed_data = {}

    meta = input_data.get('meta', {})
    action = input_data.get('action', {})
    geolocation = input_data.get('geolocation', {})
    extra = input_data.get('extra', {})
    rawData = input_data.get('rawData', None)

    # MetaInfo fields (flattened)
    transformed_data['timestamp'] = meta.get('timestamp')
    transformed_data['event_id'] = meta.get('eventId')
    transformed_data['correlation_id'] = meta.get('correlationId')
    transformed_data['event_type'] = meta.get('eventType')
    transformed_data['source_system'] = meta.get('sourceSystem')
    transformed_data['tenant_id'] = meta.get('tenantId')

    # ActionDetails fields (flattened)
    transformed_data['action_name'] = action.get('name')
    transformed_data['action_status'] = action.get('status')
    transformed_data['action_message'] = action.get('message')

    # Geolocation fields - Combined for OpenSearch geo_point
    if geolocation and geolocation.get('lat') is not None and geolocation.get('lon') is not None:
        transformed_data['location'] = {
            "lat": geolocation['lat'],
            "lon": geolocation['lon']
        }
    else:
        # Ensure 'location' field is absent if geo data is incomplete,
        # or set to None/empty dict if a mapping strictly expects it.
        # For geo_point, typically omit if not fully present.
        pass # If lat/lon are missing, 'location' key will not be added

    # Retain other geolocation descriptive fields for filtering/display
    transformed_data['location_city'] = geolocation.get('city')
    transformed_data['location_region'] = geolocation.get('region')
    transformed_data['location_country'] = geolocation.get('country')
    transformed_data['location_country_code'] = geolocation.get('countryCode')

    # Extra - keep as a nested object (OpenSearch handles nested objects well)
    if extra:
        transformed_data['extra'] = extra
    else:
        transformed_data['extra'] = {} # Ensure it's an empty object if no extra

    # RawData - keep as a string, renamed for clarity in OpenSearch
    if rawData is not None:
        transformed_data['raw_data_string'] = rawData
    else:
        transformed_data['raw_data_string'] = None # Explicitly None if not present

    return transformed_data
