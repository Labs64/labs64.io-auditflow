"""The transformer's output keys must equal the columns of the canonical ClickHouse schema.

This is the only automated check on the transformer/sink pairing. `clickhouse_sink` is dumb
transport and never sees the column list, so a mismatch between `audit_clickhouse` and the DDL
operators actually run would otherwise only appear as a stream of DLQ entries.

The schema is parsed from examples/clickhouse/schema.sql rather than duplicated as a Python
constant, so there is exactly one source of truth for the column contract.
"""
from pathlib import Path

from transformers import audit_clickhouse

SCHEMA_SQL = Path(__file__).resolve().parents[2] / "examples" / "clickhouse" / "schema.sql"

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
        "action_name": "login",
        "action_status": "SUCCESS",
        "action_message": "User logged in successfully",
        "sessionId": "sess456",
    },
}


def _schema_columns() -> set:
    """Column names from the CREATE TABLE body of the canonical schema."""
    sql = SCHEMA_SQL.read_text()
    body = sql.split("CREATE TABLE", 1)[1]
    body = body[body.index("(") + 1:body.index("ENGINE")]

    columns = set()
    for line in body.splitlines():
        line = line.strip().rstrip(",")
        if not line or line.startswith("--") or line == ")":
            continue
        columns.add(line.split()[0])
    return columns


def test_schema_file_parses_to_the_expected_column_count():
    # Guards the parser itself: a silently-empty column set would make the contract test vacuous.
    assert len(_schema_columns()) == 18


def test_transformer_output_matches_the_schema_columns_exactly():
    row = audit_clickhouse.transform(FULL_EVENT)

    assert set(row) == _schema_columns()
