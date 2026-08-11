"""Loki transformer: reshapes an AuditFlow event into a Grafana Loki push payload.

`extra` is an OPEN map — the well-known keys are a convention, not a schema — so this module holds
to four rules:

* **Absent is absent.** A missing key yields an omitted label/field, never `"unknown"` or `"N/A"`.
  A fabricated label value is indistinguishable from a publisher that really sent it, and it
  pollutes the label index with a value nobody can filter meaningfully. An `extra` value that is
  explicitly `null` counts as absent and is omitted too; falsy-but-present values (`0`, `""`,
  `false`) are real and are kept.
* **Nothing is dropped.** Every `extra` key that is not a label lands in structured metadata, so a
  deployment's own field names always survive the trip to Loki.
* **Derived metadata is never overwritten.** `eventId`, `correlationId`, `level` and the geolocation
  fields come from the event, not from `extra`. An `extra` key that would land on one of those names
  is emitted as `extra_<name>` instead — both values survive, and the derived one keeps the plain
  name. Without that guard `extra: {"eventId": ..., "level": "INFO"}` would let a publisher rewrite
  its own entry's identity and mask a FAILURE as INFO.
* **Labels stay a closed set.** Only `action_name` and `action_status` are promoted out of `extra`
  into stream labels. Labels are an index dimension in Loki, and promoting operator-defined keys
  into them is an unbounded-cardinality failure.

Extending it for a use case
---------------------------
Same two paths as `audit_clickhouse`. Either build a module on this one::

    from audit_loki import make_transform
    transform = make_transform({"invoiceRef": "invoice_ref"}, module_id=__name__)

or, with no new module, set ``AUDITFLOW_PROMOTED_KEYS='{"invoiceRef": "invoice_ref"}'`` on the
transformer container. Promotion renames the **structured-metadata** field only; it never creates a
label. Two mappings are therefore rejected at import rather than silently ignored:

* a mapping for `actionName` or `actionStatus`, whose values are stream labels and cannot be
  renamed (their names are the label index, so remapping them is a cardinality decision, not a
  formatting one);
* a mapping whose target is a name this module derives itself (`eventId`, `correlationId`, `level`,
  a geolocation field, or a label name).

Example input:
    {
      "eventTime": "2026-08-07T10:14:55Z",
      "timestamp": "2026-08-07T10:15:30Z",
      "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
      "eventType": "api.call",
      "sourceSystem": "netlicensing/core",
      "tenantId": "V12345678",
      "geolocation": {"lat": 48.1264019, "lon": 11.5407647, "countryCode": "DE"},
      "extra": {"userId": "customer123", "actionName": "licensee/validate",
                "actionStatus": "SUCCESS", "actionMessage": "Validation completed",
                "invoiceRef": "INV-1"}
    }

Example output:
    {
      "streams": [{
        "stream": {"job": "auditflow", "service_name": "netlicensing/core",
                   "tenant_id": "V12345678", "event_type": "api.call",
                   "action_name": "licensee/validate", "action_status": "SUCCESS"},
        "values": [["1754561695000000000", "Validation completed",
                    {"eventId": "fedcba98-7654-3210-fedc-ba9876543210", "level": "INFO",
                     "userId": "customer123", "country_code": "DE",
                     "latitude": "48.1264019", "longitude": "11.5407647",
                     "invoiceRef": "INV-1"}]]
      }]
    }
"""
from datetime import datetime, timezone

from auditflow_sdk import WELL_KNOWN_EXTRA, resolve_promoted, stringify_value

__version__ = "2.0.0"

PROPERTIES = {}

# `actionStatus` -> Loki level. The contract's outcome vocabulary is SUCCESS / FAILURE / DENIED;
# PENDING is retained for publishers that emit it. An unrecognised status maps to nothing rather
# than to "UNKNOWN" — see get_log_level.
STATUS_TO_LEVEL = {
    "SUCCESS": "INFO",
    "FAILURE": "ERROR",
    "DENIED": "WARN",
    "PENDING": "WARN",
}

# Top-level event fields that become stream labels.
_TOP_LEVEL_LABELS = {
    "sourceSystem": "service_name",
    "tenantId": "tenant_id",
    "eventType": "event_type",
}

# The only `extra` keys allowed to become stream labels — see the label rule in the module
# docstring. Everything else, promoted or not, goes to structured metadata.
_LABEL_KEYS = {
    "actionName": "action_name",
    "actionStatus": "action_status",
}

# Geolocation is a closed sub-object in the AuditEvent contract, so it is not an extension point.
_GEO = {
    "countryCode": "country_code",
    "country": "country",
    "region": "region",
    "city": "city",
    "lat": "latitude",
    "lon": "longitude",
}

# Loki structured metadata is not a column namespace, so the well-known keys keep their camelCase
# contract spelling here rather than the snake_case names audit_clickhouse uses for columns.
_PROMOTED = {key: key for key in WELL_KNOWN_EXTRA}

# Names this module writes itself: derived from the event, never from `extra`. Two guards use it — no
# promotion target may be one of these (a promoted key would otherwise replace the derived value,
# since the promotion loop runs last), and an unpromoted `extra` key landing on one is renamed to
# `extra_<name>`. Derived from the dicts above so it cannot drift from them.
_RESERVED_FIELDS = (
    set(_TOP_LEVEL_LABELS.values()) | set(_LABEL_KEYS.values()) | set(_GEO.values())
    | {"eventId", "correlationId", "level"}
)


def get_log_level(status):
    """Map an `actionStatus` to a Loki level, or None when there is nothing to map.

    Returns None — not "UNKNOWN" — for an absent or unrecognised status: `actionStatus` is an
    optional convention, and a level the publisher never expressed must not be invented.
    """
    if not isinstance(status, str) or not status:
        return None
    return STATUS_TO_LEVEL.get(status.upper())


def _unix_nano(iso_timestamp):
    """ISO 8601 -> Loki's nanosecond timestamp string; None when absent or unparseable."""
    if not isinstance(iso_timestamp, str) or not iso_timestamp:
        return None
    try:
        parsed = datetime.fromisoformat(iso_timestamp.replace("Z", "+00:00"))
    except ValueError:
        return None
    return str(int(parsed.timestamp() * 1_000_000_000))


def make_transform(extra_promoted=None, module_id=None):
    """Build a transform function that also renames a domain's own `extra` keys in metadata.

    :param extra_promoted: ``{extra key: metadata field}`` for one use case, layered on top of the
        generic vocabulary.
    :param module_id: this module's own id, enabling its scoped ``AUDITFLOW_PROMOTED_KEYS_<ID>``
        env mapping. Pass ``__name__`` from a domain module built on this one.
    :returns: the ``transform(input_data) -> dict`` entry point, carrying the effective mapping as
        ``transform.promoted``.
    :raises ValueError: if the deployment's env promotion mapping is malformed.
    """
    promoted = resolve_promoted({**_PROMOTED, **(extra_promoted or {})}, module_id)

    def transform(input_data: dict) -> dict:
        """Reshape a canonical AuditEvent into a Loki push payload."""
        geolocation = input_data.get("geolocation") or {}
        extra = input_data.get("extra") or {}

        # ── Stream labels: only what the event actually carries.
        stream = {"job": "auditflow"}
        for source_key, label in _TOP_LEVEL_LABELS.items():
            value = input_data.get(source_key)
            if value is not None:
                stream[label] = stringify_value(value)
        for source_key, label in _LABEL_KEYS.items():
            value = extra.get(source_key)
            if value is not None:
                stream[label] = stringify_value(value)

        # ── Structured metadata: everything else, so no key is ever dropped.
        metadata = {}
        for source_key, field in (("eventId", "eventId"), ("correlationId", "correlationId")):
            value = input_data.get(source_key)
            if value is not None:
                metadata[field] = stringify_value(value)

        level = get_log_level(extra.get("actionStatus"))
        if level is not None:
            metadata["level"] = level

        for source_key, field in _GEO.items():
            value = geolocation.get(source_key)
            if value is not None:
                metadata[field] = stringify_value(value)

        # Promoted keys under their configured names, then every remaining key verbatim. Keys that
        # are already labels are not repeated in metadata.
        for source_key, field in promoted.items():
            if source_key in _LABEL_KEYS:
                continue
            value = extra.get(source_key)
            if value is not None:
                metadata[field] = stringify_value(value)
        for key, value in extra.items():
            if key in promoted or key in _LABEL_KEYS or value is None:
                continue
            metadata[key] = stringify_value(value)

        # ── Log line: the publisher's own description when there is one, else the event
        # classification. Never a placeholder — this is the only human-readable field in the entry.
        line = extra.get("actionMessage")
        if line is None:
            line = input_data.get("eventType")
        line = "" if line is None else stringify_value(line)

        # eventTime is the business time reports group on; fall back to server receipt time. Loki
        # rejects a zero timestamp as out of range, so an event carrying neither gets now().
        nano = _unix_nano(input_data.get("eventTime")) or _unix_nano(input_data.get("timestamp"))
        if nano is None:
            nano = str(int(datetime.now(timezone.utc).timestamp() * 1_000_000_000))

        return {"streams": [{"stream": stream, "values": [[nano, line, metadata]]}]}

    transform.promoted = promoted
    return transform


transform = make_transform()
