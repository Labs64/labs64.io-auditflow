"""ClickHouse transformer: flattens an AuditFlow event into a single ClickHouse row.

The output keys are exactly the insertable column names in ``examples/clickhouse/schema.sql``.
That identity is the contract between this module and ``clickhouse_sink``, which is dumb transport
and never sees the column list — see ``tests/test_audit_clickhouse_contract.py``.

This module is **domain-free**: it promotes only the generic audit-semantics keys, the vocabulary
published in the ``Extra`` schema of the AuditEvent contract
(``auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml``). Anything else stays in the
``extra`` map column. Keep it that way — a generic audit router must not carry one publisher's
field names.

Extending it for a use case
---------------------------
A domain (licensing, commerce, IoT, …) usually wants its own keys as real columns, because a
column store rewards a dedicated column wherever a key is a GROUP BY dimension. Do that by
layering, not by editing this file:

1. Add the columns with an ``ALTER TABLE ... ADD COLUMN IF NOT EXISTS`` script applied after
   ``schema.sql``.
2. Write a transformer module that reuses this one through :func:`make_transform`::

       from audit_clickhouse import make_transform

       PROMOTED = {"licenseeNumber": "licensee_number", "mrrDelta": "mrr_delta"}
       transform = make_transform(PROMOTED)

3. Point the pipeline at that module (``transformer.name: <module>``).

A deployment that only needs a few extra columns and no new module can skip step 2 entirely and set
``AUDITFLOW_PROMOTED_KEYS='{"invoiceRef": "invoice_ref"}'`` on the transformer container instead.
The env mapping is applied on top of whatever the module declares, and a malformed value fails the
module's import rather than silently dropping the column. A target naming one of this module's own
columns — an envelope field such as ``tenant_id``/``event_id``/``timestamp``, a ``geo_*`` field, or
``extra`` — is rejected the same way: promotion runs after the envelope, so it would otherwise let a
publisher's ``extra`` value replace the event's identity.

An ``extra`` value that is explicitly ``null`` is treated as ABSENT and omitted, like a key the
publisher never sent. Falsy-but-present values (``0``, ``""``, ``false``) are real and are kept.

``examples/netlicensing/audit_clickhouse_netlicensing.py`` is a worked example, paired with
``examples/clickhouse/schema-netlicensing.sql``.

A transformer takes no pipeline properties, so the *module id* is what selects a vocabulary — one
module per domain, chosen per pipeline. Two pipelines may therefore write different column subsets
into the same table; absent scalars are omitted so ClickHouse applies the column DEFAULT, and the
sink inserts with ``input_format_skip_unknown_fields=1``, so a column a deployment never created
is dropped at insert time rather than failing the delivery.

Example input:
    {
      "timestamp": "2026-08-07T10:15:30Z",
      "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
      "eventType": "api.call",
      "sourceSystem": "netlicensing/core",
      "tenantId": "V12345678",
      "geolocation": {"lat": 48.1264019, "lon": 11.5407647, "countryCode": "DE"},
      "extra": {"userId": "customer123", "actionName": "login", "sessionId": "sess456",
                "durationMs": 34, "invoiceRef": "INV-1"}
    }

Example output (one ClickHouse row, absent scalars omitted):
    {
      "timestamp": "2026-08-07T10:15:30Z",
      "event_time": "2026-08-07T10:15:30Z",
      "event_id": "fedcba98-7654-3210-fedc-ba9876543210",
      "event_type": "api.call",
      "source_system": "netlicensing/core",
      "tenant_id": "V12345678",
      "action_name": "login",
      "user_id": "customer123",
      "session_id": "sess456",
      "duration_ms": 34,
      "geo_country_code": "DE", "geo_lat": 48.1264019, "geo_lon": 11.5407647,
      "extra": {"invoiceRef": "INV-1"}
    }
"""
from auditflow_sdk import WELL_KNOWN_EXTRA, resolve_promoted, stringify_value

__version__ = "2.1.0"

PROPERTIES = {}

# The generic audit-semantics keys promoted out of `extra` into dedicated columns, sourced from the
# shared vocabulary so this module and audit_opensearch cannot drift from each other or from the
# `Extra` schema in the AuditEvent contract. `audit_loki` promotes the same keys but keeps their
# camelCase spelling, because Loki structured metadata is not a column namespace.
_PROMOTED = dict(WELL_KNOWN_EXTRA)

# Envelope fields, as {event field: column}. The event's own identity — never an extension point.
_ENVELOPE = {
    "timestamp": "timestamp",
    "eventTime": "event_time",
    "eventId": "event_id",
    "correlationId": "correlation_id",
    "eventType": "event_type",
    "sourceSystem": "source_system",
    "tenantId": "tenant_id",
}

# Geolocation is a closed sub-object in the AuditEvent contract, so it is not an extension point.
_GEO = {
    "countryCode": "geo_country_code",
    "country": "geo_country",
    "region": "geo_region",
    "city": "geo_city",
}

# Columns this module writes itself, so no promotion may target one: the row is a flat namespace and
# the promotion loop runs after the envelope loop, so a colliding target would let a publisher's
# `extra` value REPLACE the envelope value. That is not cosmetic — `tenant_id` leads the table's
# ORDER BY and is the tenant-isolation dimension, `event_id` is the ReplacingMergeTree dedup key,
# and `timestamp` is its version column. Derived from the dicts above so it cannot drift from them;
# `test_audit_clickhouse_contract.py` asserts the row never carries a column outside this set plus
# the promotion targets.
_RESERVED_COLUMNS = (
    set(_ENVELOPE.values()) | set(_GEO.values()) | {"geo_lat", "geo_lon", "extra"}
)


def make_transform(extra_promoted=None, module_id=None):
    """Build a transform function that also promotes a domain's own `extra` keys.

    :param extra_promoted: ``{extra key: column name}`` for one use case, layered on top of the
        generic vocabulary. A key repeated here overrides the generic mapping, which is what lets
        a domain retarget a column without forking this module.
    :param module_id: this module's own id, enabling its scoped ``AUDITFLOW_PROMOTED_KEYS_<ID>``
        env mapping. Pass ``__name__`` from a domain module built on this one.
    :returns: the ``transform(input_data) -> dict`` entry point, carrying the effective mapping as
        ``transform.promoted`` so tests and the registry can introspect what a module promotes.
    :raises ValueError: if the deployment's env promotion mapping is malformed, or if it targets one
        of this module's reserved columns — see ``auditflow_sdk.resolve_promoted``.
    """
    promoted = resolve_promoted({**_PROMOTED, **(extra_promoted or {})}, module_id,
                                reserved=_RESERVED_COLUMNS)

    def transform(input_data: dict) -> dict:
        """Flatten a canonical AuditEvent into one ClickHouse row keyed by column name."""
        geolocation = input_data.get("geolocation") or {}
        extra = input_data.get("extra") or {}
        timestamp = input_data.get("timestamp")

        row = {column: input_data.get(source_key) for source_key, column in _ENVELOPE.items()}
        # eventTime is the client-supplied business time and the axis every report is grouped on;
        # fall back to server receipt time so the column is never null.
        row["event_time"] = input_data.get("eventTime") or timestamp

        for source_key, column in promoted.items():
            row[column] = extra.get(source_key)

        for source_key, column in _GEO.items():
            row[column] = geolocation.get(source_key)

        # Omit absent scalars so ClickHouse applies the column DEFAULT via
        # input_format_defaults_for_omitted_fields. Emitting null would fail a non-Nullable column.
        row = {key: value for key, value in row.items() if value is not None}

        # geo_lat/geo_lon are Nullable(Float64): (0, 0) is a real coordinate, so "missing" cannot
        # be encoded as zero. Always emitted, explicitly null when absent.
        row["geo_lat"] = geolocation.get("lat")
        row["geo_lon"] = geolocation.get("lon")

        # An explicitly null `extra` value is treated as ABSENT and omitted, exactly like a key the
        # publisher never sent — a null carries no information, and stringifying it would fabricate
        # the literal "null". Falsy-but-present values (0, "", false) are real and are kept.
        row["extra"] = {
            key: stringify_value(value) for key, value in extra.items()
            if key not in promoted and value is not None
        }

        return row

    transform.promoted = promoted
    return transform


transform = make_transform()
