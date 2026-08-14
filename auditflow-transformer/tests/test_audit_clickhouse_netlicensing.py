"""The NetLicensing use-case layer must line up with its schema extension and its documentation.

This is the extensibility mechanism under test, not just one vendor's field list. A use-case layer
is three files that have to agree, and nothing at runtime checks that they do:

  * examples/netlicensing/audit_clickhouse_netlicensing.py  — which `extra` keys get promoted
  * examples/clickhouse/schema-netlicensing.sql             — the columns they are promoted into
  * examples/clickhouse/NETLICENSING_EVENTS.md              — what each key means to a publisher

A key with no column silently vanishes at insert time (`input_format_skip_unknown_fields=1`); a
column with no key is always empty; an undocumented key is one no integrator can discover. Each of
those gets a test here.

The module is loaded from examples/ rather than imported from the package: it is deliberately not
shipped in the transformer image — docker-compose mounts it into `transformers_bootstrap`, the same
path an integrator uses for their own. Loading it the way the plugin registry would is part of what
this file verifies.

The event shapes below are the templates from the NetLicensing API audit event spec
(`actionMethod` / `statusCode` taxonomy), so a drift in the spec surfaces as a failure here.
"""
import importlib.util
import sys
from pathlib import Path

import pytest

from test_audit_clickhouse_contract import (
    CLICKHOUSE_DIR,
    alter_table_columns,
    core_columns,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "examples" / "netlicensing" / "audit_clickhouse_netlicensing.py"
SCHEMA_NETLICENSING_SQL = CLICKHOUSE_DIR / "schema-netlicensing.sql"
EVENTS_DOC = CLICKHOUSE_DIR / "NETLICENSING_EVENTS.md"


def _load_bootstrap_module():
    """Import the example the way the plugin registry does: by file, off the shipped path.

    The module does `from audit_clickhouse import make_transform`, which resolves because the
    service puts `transformers/` on sys.path at startup (transformer.py) — mirrored here.
    """
    transformers_dir = str(REPO_ROOT / "auditflow-transformer" / "transformers")
    if transformers_dir not in sys.path:
        sys.path.append(transformers_dir)

    spec = importlib.util.spec_from_file_location("audit_clickhouse_netlicensing", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


audit_clickhouse_netlicensing = _load_bootstrap_module()
transform = audit_clickhouse_netlicensing.transform

# A publisher that fills in everything both layers know about. Every key must land in a column.
# `actionName`/`responseStatus` (generic) and `actionMethod`/`statusCode` (this layer) both appear
# because they are different granularities — the semantic action vs. the API endpoint that carried
# it. No real event carries both; this one does so the "every column is fillable" check is total.
FULL_EVENT = {
    "timestamp": "2026-08-07T10:15:30Z",
    "eventTime": "2026-08-07T10:14:55Z",
    "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
    "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "eventType": "api.call",
    "sourceSystem": "netlicensing/payment-gateway",
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
        # core audit semantics — promoted by the generic layer this module builds on
        "userId": "customer123",
        "actionName": "payment.capture",
        "actionStatus": "SUCCESS",
        "actionMessage": "Payment captured",
        "sessionId": "sess456",
        "durationMs": 128,
        "responseStatus": 200,
        # standard API call
        "actionMethod": "payments/pay",
        "statusCode": 200,
        # licensing / validation
        "licenseeNumber": "LIC-789",
        "productNumber": "PROD-123",
        "moduleNumber": "MOD-456",
        "nodeId": "node-7",
        # a JSON bool, exactly as the spec templates emit it: ClickHouse parses true/false into
        # the Nullable(UInt8) column, so the wire format does not have to pre-encode 1/0.
        "isDryRun": True,
        "validationArgs": "[]",
        "validationOutcome": "VALID",
        "validUntil": "2026-09-01T00:00:00Z",
        # payment gateway
        "transactionNumber": "TR-123",
        "paymentMethod": "card",
        "grossAmount": 119.0,
        "netAmount": 100.0,
        "taxAmount": 19.0,
        "currency": "EUR",
        "customerCountry": "DE",
        "billingPeriod": "MONTH",
    },
}

# One event per template in the spec, in the sparse shape a real publisher emits: only the fields
# that event actually carries. Each entry is (event, {column: expected value}).
INDICATIVE_EVENTS = [
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventTime": "2026-08-01T09:00:00Z",
            "eventId": "event-payment",
            "eventType": "api.call",
            "sourceSystem": "netlicensing/payment-gateway",
            "tenantId": "demo-vendor",
            "extra": {
                "actionMethod": "payments/pay",
                "actionStatus": "SUCCESS",
                "statusCode": 200,
                "transactionNumber": "TR-1",
                "paymentMethod": "stripe",
                "grossAmount": 99.99,
                "netAmount": 84.03,
                "taxAmount": 15.96,
                "currency": "USD",
                "customerCountry": "US",
                "billingPeriod": "MONTH",
            },
        },
        {
            "event_time": "2026-08-01T09:00:00Z",
            "gross_amount": 99.99,
            "action_method": "payments/pay",
            "status_code": 200,
            "transaction_number": "TR-1",
            "payment_method": "stripe",
            "customer_country": "US",
        },
    ),
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-validate-dry-run",
            "eventType": "api.call",
            "sourceSystem": "netlicensing/core",
            "tenantId": "demo-vendor",
            "extra": {"actionMethod": "licensee/validate", "actionStatus": "SUCCESS",
                      "statusCode": 200, "licenseeNumber": "IMONITORING",
                      "productNumber": "PMONITORING", "isDryRun": True},
        },
        {"product_number": "PMONITORING", "licensee_number": "IMONITORING",
         "action_method": "licensee/validate", "is_dry_run": True},
    ),
    (
        # Scenario B — node-locked: `nodeSecret` maps to nodeId, `productModuleNumber` to
        # moduleNumber. No productNumber at all; the column must stay unset, not be invented.
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-validate-node-locked",
            "eventType": "api.call",
            "sourceSystem": "netlicensing/core",
            "tenantId": "demo-vendor",
            "extra": {"actionMethod": "licensee/validate", "actionStatus": "SUCCESS",
                      "statusCode": 200, "licenseeNumber": "bparicio@power-electronics.com",
                      "moduleNumber": "MYIYHX9W9",
                      "nodeId": "Ab6uKqR5IN72Stkv_Nn1pzdWp7dTnvOkWve4SOem5OI"},
        },
        {"module_number": "MYIYHX9W9", "node_id": "Ab6uKqR5IN72Stkv_Nn1pzdWp7dTnvOkWve4SOem5OI",
         "licensee_number": "bparicio@power-electronics.com"},
    ),
    (
        # Scenario C — multi-module: the per-module args stay a serialized JSON string so nothing
        # is lost, and the row stays flat. Queried with ClickHouse's JSON* functions, not a column.
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-validate-multi-module",
            "eventType": "api.call",
            "sourceSystem": "netlicensing/core",
            "tenantId": "demo-vendor",
            "extra": {"actionMethod": "licensee/validate", "actionStatus": "SUCCESS",
                      "statusCode": 200, "licenseeNumber": "skaraborgstraforadling",
                      "validationArgs": '[{"nodeSecret":"0050B6E5D7AF",'
                                        '"productModuleNumber":"MI7C7QC3E"}]'},
        },
        {"validation_args": '[{"nodeSecret":"0050B6E5D7AF","productModuleNumber":"MI7C7QC3E"}]'},
    ),
    (
        # An expiring license. The call SUCCEEDs — the API answered correctly — while the licensing
        # verdict is EXPIRING_SOON. The two must land in different columns or neither is queryable.
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-validate-expiring",
            "eventType": "api.call",
            "sourceSystem": "netlicensing/core",
            "tenantId": "demo-vendor",
            "extra": {"actionMethod": "licensee/validate", "actionStatus": "SUCCESS",
                      "statusCode": 200, "licenseeNumber": "LIC-42",
                      "validationOutcome": "EXPIRING_SOON",
                      "validUntil": "2026-09-01T00:00:00Z"},
        },
        {"action_status": "SUCCESS", "validation_outcome": "EXPIRING_SOON",
         "valid_until": "2026-09-01T00:00:00Z"},
    ),
    (
        # A failed call: actionStatus and statusCode have to disagree with the success path, and
        # the layer must not swallow the failure into an empty row.
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-api-failure",
            "eventType": "api.call",
            "sourceSystem": "netlicensing/SHOP",
            "tenantId": "demo-vendor",
            "extra": {"actionMethod": "token/generate", "actionStatus": "FAILURE",
                      "statusCode": 500},
        },
        {"action_method": "token/generate", "action_status": "FAILURE", "status_code": 500},
    ),
]


def _schema_columns() -> set:
    return core_columns() | alter_table_columns(SCHEMA_NETLICENSING_SQL)


def test_extension_schema_parses_to_a_plausible_column_set():
    core, netlicensing = core_columns(), alter_table_columns(SCHEMA_NETLICENSING_SQL)
    assert {"licensee_number", "gross_amount", "action_method", "validation_args"} <= netlicensing
    assert not core & netlicensing, "a column is declared in both schema files"
    assert len(_schema_columns()) == 39


def test_transformer_output_matches_both_schema_layers_exactly():
    assert set(transform(FULL_EVENT)) == _schema_columns()


def test_every_promoted_key_has_a_column_and_every_column_a_key():
    """The two halves of the layer are each other's contract — a key with no column is dropped
    at insert time, a column with no key is always empty."""
    columns = set(audit_clickhouse_netlicensing.PROMOTED.values())
    schema = alter_table_columns(SCHEMA_NETLICENSING_SQL)
    assert sorted(columns - schema) == [], "promoted keys with no column in the extension schema"
    assert sorted(schema - columns) == [], "extension columns no promoted key ever fills"


def test_extension_layers_on_the_generic_vocabulary_without_replacing_it():
    from transformers import audit_clickhouse

    assert set(audit_clickhouse.transform.promoted) <= set(transform.promoted)
    # ...and layering must not leak back: importing this module must leave the generic one generic.
    assert "licenseeNumber" not in audit_clickhouse.transform.promoted


def test_full_event_leaves_nothing_in_the_extra_map():
    assert transform(FULL_EVENT)["extra"] == {}


def test_unrecognised_extra_keys_stay_in_the_extra_map():
    event = dict(FULL_EVENT, extra={**FULL_EVENT["extra"], "invoiceRef": "INV-1", "retry": 2})
    assert transform(event)["extra"] == {"invoiceRef": "INV-1", "retry": "2"}


@pytest.mark.parametrize("event,expected", INDICATIVE_EVENTS,
                         ids=[e["eventId"] for e, _ in INDICATIVE_EVENTS])
def test_indicative_events_promote_their_fields(event, expected):
    row = transform(event)

    # Sparse events emit a subset — an absent scalar is omitted so ClickHouse applies the DEFAULT.
    assert set(row).issubset(_schema_columns())
    assert {"timestamp", "event_time", "event_type", "tenant_id"} <= set(row)

    for column, value in expected.items():
        assert row[column] == value, f"{event['eventId']}: {column}"


def test_promoted_keys_are_documented_for_publishers():
    """A use-case layer publishes its own vocabulary — the generic OpenAPI contract deliberately
    does not carry it, so the events doc is where an integrator has to find these keys. Read
    unguarded: a deleted doc must fail this test, not silently satisfy it."""
    doc = EVENTS_DOC.read_text()
    undocumented = sorted(k for k in audit_clickhouse_netlicensing.PROMOTED if f"`{k}`" not in doc)
    assert not undocumented, f"promoted but undocumented in NETLICENSING_EVENTS.md: {undocumented}"
