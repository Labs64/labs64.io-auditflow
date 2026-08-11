"""OpenSearch transformer: flattens an AuditFlow event into an OpenSearch-friendly document.

Transforms an AuditEvent JSON object into a flattened, OpenSearch-friendly format.

Example Input:
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
    "actionName": "LOGIN_SUCCESS",
    "actionStatus": "SUCCESS",
    "actionMessage": "User logged in successfully",
    "sessionId": "sess456",
    "durationMs": 234,
    "responseStatus": 200
  }
}

Example Output:
{
  "timestamp": "2025-07-04T10:00:00Z",
  "event_id": "fedcba98-7654-3210-fedc-ba9876543210",
  "event_type": "audit.action.performed",
  "source_system": "system-name/service-name",
  "tenant_id": "tenant-001",
  "action_name": "LOGIN_SUCCESS",
  "action_status": "SUCCESS",
  "action_message": "User logged in successfully",
  "user_id": "user123",
  "session_id": "sess456",
  "duration_ms": 234,
  "response_status": 200,
  "location": {
    "lat": 48.1351,
    "lon": 11.5820
  },
  "location_city": "Munich",
  "location_region": "Bavaria",
  "location_country": "Germany",
  "location_country_code": "DE",
  "extra": {
    "browser": "Chrome"
  }
}

Migration from 1.x
------------------
`sessionId`, `durationMs` and `responseStatus` are now promoted to top-level `session_id`,
`duration_ms` and `response_status` instead of remaining inside the `extra` object, making the
promotion set symmetric with `audit_clickhouse`. Queries and dashboards reading `extra.sessionId`
(etc.) must move to the top-level field, and the index mapping gains three fields.
"""

from auditflow_sdk import WELL_KNOWN_EXTRA, resolve_promoted

__version__ = "2.0.0"

PROPERTIES = {}

# The generic audit-semantics keys promoted out of `extra` into top-level document fields, sourced
# from the shared vocabulary so this module and audit_clickhouse cannot drift. Unlike the ClickHouse
# Map(String, String), a document keeps native JSON types, so no stringification happens here.
_PROMOTED = dict(WELL_KNOWN_EXTRA)

# Geolocation is a closed sub-object in the AuditEvent contract, so it is not an extension point.
_GEO_DESCRIPTIVE = {
    "city": "location_city",
    "region": "location_region",
    "country": "location_country",
    "countryCode": "location_country_code",
}

_TOP_LEVEL = {
    "timestamp": "timestamp",
    "eventTime": "event_time",
    "eventId": "event_id",
    "correlationId": "correlation_id",
    "eventType": "event_type",
    "sourceSystem": "source_system",
    "tenantId": "tenant_id",
}


def make_transform(extra_promoted=None, module_id=None):
    """Build a transform function that also promotes a domain's own `extra` keys.

    :param extra_promoted: ``{extra key: document field}`` for one use case, layered on top of the
        generic vocabulary.
    :param module_id: this module's own id, enabling its scoped ``AUDITFLOW_PROMOTED_KEYS_<ID>``
        env mapping. Pass ``__name__`` from a domain module built on this one.
    :returns: the ``transform(input_data) -> dict`` entry point, carrying the effective mapping as
        ``transform.promoted``.
    :raises ValueError: if the deployment's env promotion mapping is malformed.
    """
    promoted = resolve_promoted({**_PROMOTED, **(extra_promoted or {})}, module_id)

    def transform(input_data: dict) -> dict:
        """Flatten a canonical AuditEvent into an OpenSearch-friendly document."""
        geolocation = input_data.get("geolocation") or {}
        extra = input_data.get("extra") or {}

        document = {}
        for source_key, field in _TOP_LEVEL.items():
            value = input_data.get(source_key)
            if value is not None:
                document[field] = value

        # Promoted keys become top-level fields; absent ones are simply omitted.
        for source_key, field in promoted.items():
            value = extra.get(source_key)
            if value is not None:
                document[field] = value

        # geo_point needs both coordinates, so it is all-or-nothing.
        if geolocation.get("lat") is not None and geolocation.get("lon") is not None:
            document["location"] = {"lat": geolocation["lat"], "lon": geolocation["lon"]}
        for source_key, field in _GEO_DESCRIPTIVE.items():
            value = geolocation.get(source_key)
            if value is not None:
                document[field] = value

        # Everything not promoted stays in a nested object — never dropped.
        remaining = {key: value for key, value in extra.items() if key not in promoted}
        if remaining:
            document["extra"] = remaining

        return document

    transform.promoted = promoted
    return transform


transform = make_transform()
