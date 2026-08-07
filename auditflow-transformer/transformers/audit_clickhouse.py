"""ClickHouse transformer: flattens an AuditFlow event into a single ClickHouse row.

The output keys are exactly the column names in ``examples/clickhouse/schema.sql``. That identity
is the contract between this module and ``clickhouse_sink``, which is dumb transport and never
sees the column list — see ``tests/test_audit_clickhouse_contract.py``.

Example input:
    {
      "timestamp": "2026-08-07T10:15:30Z",
      "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
      "eventType": "api.call",
      "sourceSystem": "netlicensing/core",
      "tenantId": "V12345678",
      "geolocation": {"lat": 48.1264019, "lon": 11.5407647, "countryCode": "DE"},
      "extra": {"userId": "customer123", "action_name": "login", "sessionId": "sess456"}
    }

Example output (one ClickHouse row):
    {
      "timestamp": "2026-08-07T10:15:30Z",
      "event_time": "2026-08-07T10:15:30Z",
      "event_id": "fedcba98-7654-3210-fedc-ba9876543210",
      "event_type": "api.call",
      "source_system": "netlicensing/core",
      "tenant_id": "V12345678",
      "action_name": "login",
      "user_id": "customer123",
      "geo_country_code": "DE",
      "geo_lat": 48.1264019,
      "geo_lon": 11.5407647,
      "extra": {"sessionId": "sess456"}
    }
"""
import json

__version__ = "1.0.0"

PROPERTIES = {}

# Well-known audit-semantics keys promoted out of `extra` into dedicated columns.
# Keep this set consistent with audit_opensearch, which promotes the same keys.
_PROMOTED = {
    "action_name": "action_name",
    "action_status": "action_status",
    "action_message": "action_message",
    "userId": "user_id",
}

_GEO = {
    "countryCode": "geo_country_code",
    "country": "geo_country",
    "region": "geo_region",
    "city": "geo_city",
}


def _stringify(value):
    """Map(String, String) values must all be strings; JSON-encode anything that is not scalar."""
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        # Checked before int — bool is a subclass of int, and "True" is not valid JSON.
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return json.dumps(value, separators=(",", ":"), sort_keys=True)


def transform(input_data: dict) -> dict:
    """Flatten a canonical AuditEvent into one ClickHouse row keyed by column name."""
    geolocation = input_data.get("geolocation") or {}
    extra = input_data.get("extra") or {}
    timestamp = input_data.get("timestamp")

    row = {
        "timestamp": timestamp,
        # eventTime is the client-supplied action time; fall back to server receipt time.
        "event_time": input_data.get("eventTime") or timestamp,
        "event_id": input_data.get("eventId"),
        "correlation_id": input_data.get("correlationId"),
        "event_type": input_data.get("eventType"),
        "source_system": input_data.get("sourceSystem"),
        "tenant_id": input_data.get("tenantId"),
    }

    for source_key, column in _PROMOTED.items():
        row[column] = extra.get(source_key)

    for source_key, column in _GEO.items():
        row[column] = geolocation.get(source_key)

    # Omit absent scalars so ClickHouse applies the column DEFAULT via
    # input_format_defaults_for_omitted_fields. Emitting null would fail a non-Nullable column.
    row = {key: value for key, value in row.items() if value is not None}

    # geo_lat/geo_lon are Nullable(Float64): (0, 0) is a real coordinate, so "missing" cannot be
    # encoded as zero. Always emitted, explicitly null when absent.
    row["geo_lat"] = geolocation.get("lat")
    row["geo_lon"] = geolocation.get("lon")

    row["extra"] = {
        key: _stringify(value) for key, value in extra.items() if key not in _PROMOTED
    }

    return row
