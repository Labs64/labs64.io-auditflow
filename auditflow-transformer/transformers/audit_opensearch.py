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

    Example Output (OpenSearch friendly):
    {
      "timestamp": "2025-07-04T10:00:00Z",
      "event_id": "fedcba98-7654-3210-fedc-ba9876543210",
      "event_type": "audit.action.performed",
      "source_system": "system-name/service-name",
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
      }
    }
    """
    transformed_data = {}

    geolocation = input_data.get('geolocation', {})
    extra = input_data.get('extra', {})

    # Top-level fields (previously in MetaInfo)
    transformed_data['timestamp'] = input_data.get('timestamp')
    transformed_data['event_id'] = input_data.get('eventId')
    transformed_data['event_type'] = input_data.get('eventType')
    transformed_data['source_system'] = input_data.get('sourceSystem')
    transformed_data['tenant_id'] = input_data.get('tenantId')

    # Action fields (now in extra) — only include if present
    if extra.get('action_name') is not None:
        transformed_data['action_name'] = extra.get('action_name')
    if extra.get('action_status') is not None:
        transformed_data['action_status'] = extra.get('action_status')
    if extra.get('action_message') is not None:
        transformed_data['action_message'] = extra.get('action_message')

    # Geolocation fields - Combined for OpenSearch geo_point
    if geolocation and geolocation.get('lat') is not None and geolocation.get('lon') is not None:
        transformed_data['location'] = {
            "lat": geolocation['lat'],
            "lon": geolocation['lon']
        }

    # Retain other geolocation descriptive fields for filtering/display (only if present)
    for key, field in [
        ('location_city', 'city'),
        ('location_region', 'region'),
        ('location_country', 'country'),
        ('location_country_code', 'countryCode'),
    ]:
        value = geolocation.get(field)
        if value is not None:
            transformed_data[key] = value

    # Extra — keep as a nested object, but strip the action fields already promoted above
    _ACTION_KEYS = {'action_name', 'action_status', 'action_message'}
    remaining_extra = {k: v for k, v in extra.items() if k not in _ACTION_KEYS}
    if remaining_extra:
        transformed_data['extra'] = remaining_extra

    # Drop any remaining None values from the top-level output
    return {k: v for k, v in transformed_data.items() if v is not None}
