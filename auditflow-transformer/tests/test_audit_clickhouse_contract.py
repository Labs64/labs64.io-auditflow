"""The transformer's output keys must equal the insertable columns of the ClickHouse schema.

This is the only automated check on the transformer/sink pairing. `clickhouse_sink` is dumb
transport and never sees the column list, so a mismatch between `audit_clickhouse` and the DDL
operators actually run would otherwise only appear as a stream of DLQ entries.

Scope is the **generic** contract: examples/clickhouse/schema.sql and the domain-free
`audit_clickhouse`. Both must stay free of any one publisher's field names — a use-case layer is
covered by its own test (test_audit_clickhouse_netlicensing.py), which reuses the parser here.

The schema is parsed from the .sql file rather than duplicated as a Python constant, so there is
exactly one source of truth for the column contract. ALIAS and MATERIALIZED columns are excluded
on purpose: they are computed by ClickHouse and must never appear in an INSERT.
"""
import re
from pathlib import Path

from transformers import audit_clickhouse

CLICKHOUSE_DIR = Path(__file__).resolve().parents[2] / "examples" / "clickhouse"
SCHEMA_SQL = CLICKHOUSE_DIR / "schema.sql"
OPENAPI_SPEC = (CLICKHOUSE_DIR.parents[1] / "auditflow-api" / "src" / "main" / "resources"
                / "openapi" / "openapi-audit-v1.yaml")

# A publisher that fills in everything the generic contract knows about. Every key here must land
# in a column; anything unrecognised must land in the `extra` map instead.
FULL_EVENT = {
    "timestamp": "2026-08-07T10:15:30Z",
    "eventTime": "2026-08-07T10:14:55Z",
    "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
    "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "eventType": "api.call",
    "sourceSystem": "netlicensing/core",
    "tenantId": "V12345678",
    "geolocation": {
        "lat": 48.1264019,
        "lon": 11.5407647,
        "countryCode": "DE",
        "country": "Germany",
        "region": "Bavaria",
        "city": "Munich",
    },
    "extra": {
        "userId": "customer123",
        "actionName": "licensee.get",
        "actionStatus": "SUCCESS",
        "actionMessage": "Request served",
        "sessionId": "sess456",
        "durationMs": 128,
        "responseStatus": 200,
    },
}


def core_columns() -> set:
    """Insertable column names from the CREATE TABLE body of the core schema."""
    sql = SCHEMA_SQL.read_text()
    body = sql.split("CREATE TABLE", 1)[1]
    body = body[body.index("(") + 1:body.index("ENGINE")]

    columns = set()
    for line in body.splitlines():
        line = line.strip().rstrip(",")
        if not line or line.startswith("--") or line == ")" or line.startswith("INDEX "):
            continue
        columns.add(line.split()[0])
    return columns


def alter_table_columns(sql_path: Path) -> set:
    """Insertable column names added by an extension file's ALTER TABLE ADD COLUMN statements."""
    sql = sql_path.read_text()
    # Strip comments first so a column name mentioned in prose cannot be picked up.
    sql = "\n".join(line for line in sql.splitlines() if not line.strip().startswith("--"))

    columns = set()
    for match in re.finditer(
        r"ADD COLUMN IF NOT EXISTS\s+(\w+)\s+(.*?);", sql, re.DOTALL | re.IGNORECASE
    ):
        name, definition = match.group(1), match.group(2)
        if re.search(r"\b(ALIAS|MATERIALIZED)\b", definition, re.IGNORECASE):
            continue  # computed by ClickHouse — must never be sent in an INSERT
        columns.add(name)
    return columns


def extra_schema_text() -> str:
    """The `Extra` schema section of the published AuditEvent contract."""
    spec = OPENAPI_SPEC.read_text()
    return spec.split("    Extra:", 1)[1].split("\n    ErrorCode:", 1)[0]


def test_schema_file_parses_to_a_plausible_column_set():
    # Guards the parser itself: a silently-empty column set would make the contract test vacuous.
    core = core_columns()
    assert {"timestamp", "event_time", "event_id", "tenant_id", "extra"} <= core
    assert len(core) == 21


def test_transformer_output_matches_the_schema_columns_exactly():
    row = audit_clickhouse.transform(FULL_EVENT)
    assert set(row) == core_columns()


def test_full_event_leaves_nothing_in_the_extra_map():
    # Every key in FULL_EVENT.extra is part of the documented vocabulary, so all of them must have
    # been promoted. A leftover here means the promotion map and the test event have drifted.
    assert audit_clickhouse.transform(FULL_EVENT)["extra"] == {}


def test_unrecognised_extra_keys_stay_in_the_extra_map():
    event = dict(FULL_EVENT, extra={**FULL_EVENT["extra"], "invoiceRef": "INV-1", "retry": 2})
    row = audit_clickhouse.transform(event)
    assert row["extra"] == {"invoiceRef": "INV-1", "retry": "2"}


def test_event_time_falls_back_to_server_receipt_time():
    # Every report is grouped on event_time, so it must never be null — even when the publisher
    # omits eventTime and the only time available is AuditFlow's own receipt timestamp.
    event = {"timestamp": "2026-08-07T10:15:30Z", "eventId": "e", "eventType": "api.call"}
    assert audit_clickhouse.transform(event)["event_time"] == "2026-08-07T10:15:30Z"


def test_promoted_keys_are_documented_in_the_openapi_contract():
    """The `Extra` schema is the published vocabulary; a promoted key missing from it is a
    field no integrator can discover."""
    extra_schema = extra_schema_text()

    undocumented = sorted(k for k in audit_clickhouse.transform.promoted
                          if f"`{k}`" not in extra_schema)
    assert not undocumented, f"promoted but undocumented in the AuditEvent contract: {undocumented}"


def test_the_generic_contract_carries_no_domain_vocabulary():
    """AuditFlow is a domain-agnostic router. A use case extends the core layer (see
    make_transform + an ALTER TABLE script); it never widens it."""
    domain_columns = {"licensee_number", "mrr_delta", "gross_amount", "license_number",
                      "subscription_number", "customer_country"}
    leaked = sorted(domain_columns & core_columns())
    assert not leaked, f"domain columns leaked into the core schema: {leaked}"

    domain_keys = {"licenseeNumber", "mrrDelta", "grossAmount", "licenseNumber"}
    leaked = sorted(domain_keys & set(audit_clickhouse.transform.promoted))
    assert not leaked, f"domain keys leaked into the generic transformer: {leaked}"
