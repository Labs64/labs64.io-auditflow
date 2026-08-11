"""The NetLicensing use-case layer must line up with its schema extension and its documentation.

This is the extensibility mechanism under test, not just one vendor's field list. A use-case layer
is three files that have to agree, and nothing at runtime checks that they do:

  * examples/netlicensing/audit_clickhouse_netlicensing.py  — which `extra` keys get promoted
  * examples/clickhouse/schema-netlicensing.sql             — the columns they are promoted into
  * examples/clickhouse/NETLICENSING_KPI.md                 — what each key means to a publisher

A key with no column silently vanishes at insert time (`input_format_skip_unknown_fields=1`); a
column with no key is always empty; an undocumented key is one no integrator can discover. Each of
those gets a test here.

The module is loaded from examples/ rather than imported from the package: it is deliberately not
shipped in the transformer image — docker-compose mounts it into `transformers_bootstrap`, the same
path an integrator uses for their own. Loading it the way the plugin registry would is part of what
this file verifies.
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
KPI_DOC = CLICKHOUSE_DIR / "NETLICENSING_KPI.md"


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
FULL_EVENT = {
    "timestamp": "2026-08-07T10:15:30Z",
    "eventTime": "2026-08-07T10:14:55Z",
    "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
    "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "eventType": "payment.succeeded",
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
        # core audit semantics — promoted by the generic layer this module builds on
        "userId": "customer123",
        "actionName": "payment.capture",
        "actionStatus": "SUCCESS",
        "actionMessage": "Payment captured",
        "sessionId": "sess456",
        "durationMs": 128,
        "responseStatus": 200,
        # customer
        "licenseeNumber": "LIC-789",
        "customerType": "B2B",
        "customerCountry": "DE",
        "customerSegment": "enterprise",
        "acquisitionChannel": "direct",
        "resellerNumber": "RES-1",
        # product / licensing
        "productNumber": "PROD-123",
        "moduleNumber": "MOD-456",
        "licenseNumber": "L-999",
        "licenseTemplateNumber": "LT-1",
        "licenseType": "SUBSCRIPTION",
        "licensingModel": "Subscription",
        "nodeId": "node-7",
        # entity state
        "entityStatus": "ACTIVE",
        "entityStatusPrev": "PENDING",
        # commerce
        "transactionNumber": "TR-123",
        "subscriptionNumber": "SUB-123",
        "paymentMethod": "card",
        "quantity": 3,
        "grossAmount": 119.0,
        "netAmount": 100.0,
        "discountAmount": 10.0,
        "taxAmount": 19.0,
        "currency": "EUR",
        "baseAmount": 100.0,
        "baseCurrency": "EUR",
        "fxRate": 1.0,
        # recurring
        "mrrDelta": 100.0,
        "billingPeriod": "MONTH",
        "billingPeriodCount": 1,
        "periodStart": "2026-08-01T00:00:00Z",
        "periodEnd": "2026-09-01T00:00:00Z",
        "isTrial": False,
    },
}

# One representative event per KPI family, in the sparse shape a real publisher emits: only the
# fields that event actually carries. Each entry is (event, {column: expected value}).
INDICATIVE_EVENTS = [
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventTime": "2026-08-01T09:00:00Z",
            "eventId": "event-payment",
            "eventType": "payment.succeeded",
            "sourceSystem": "netlicensing/core",
            "tenantId": "demo-vendor",
            "extra": {
                "licenseeNumber": "LIC-789",
                "licenseType": "SUBSCRIPTION",
                "productNumber": "PROD-123",
                "transactionNumber": "TR-1",
                "grossAmount": 99.99,
                "currency": "USD",
                "baseAmount": 92.5,
                "baseCurrency": "EUR",
                "fxRate": 0.925,
                "mrrDelta": 92.5,
                "billingPeriod": "MONTH",
            },
        },
        {
            "event_time": "2026-08-01T09:00:00Z",
            "gross_amount": 99.99,
            "base_amount": 92.5,
            "base_currency": "EUR",
            "mrr_delta": 92.5,
            "licensee_number": "LIC-789",
        },
    ),
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-refund",
            "eventType": "payment.refunded",
            "tenantId": "demo-vendor",
            "extra": {"licenseeNumber": "LIC-789", "grossAmount": 20.0,
                      "baseAmount": 18.5, "baseCurrency": "EUR", "currency": "USD"},
        },
        {"base_amount": 18.5, "event_type": "payment.refunded"},
    ),
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-sub-cancelled",
            "eventType": "subscription.cancelled",
            "tenantId": "demo-vendor",
            "extra": {"licenseeNumber": "LIC-789", "subscriptionNumber": "SUB-1",
                      "mrrDelta": -92.5, "baseCurrency": "EUR", "entityStatus": "CANCELLED"},
        },
        {"mrr_delta": -92.5, "entity_status": "CANCELLED", "subscription_number": "SUB-1"},
    ),
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-licensee-created",
            "eventType": "licensee.created",
            "tenantId": "demo-vendor",
            "geolocation": {"countryCode": "FR"},
            "extra": {"licenseeNumber": "LIC-789", "customerType": "B2B",
                      "customerCountry": "FR", "entityStatus": "ACTIVE"},
        },
        {"customer_type": "B2B", "customer_country": "FR", "geo_country_code": "FR",
         "entity_status": "ACTIVE"},
    ),
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-validation",
            "eventType": "validation.requested",
            "tenantId": "demo-vendor",
            "extra": {"productNumber": "PROD-123", "sessionId": "sess-abc", "nodeId": "node-1",
                      "actionStatus": "SUCCESS", "durationMs": 12},
        },
        {"product_number": "PROD-123", "session_id": "sess-abc", "node_id": "node-1",
         "duration_ms": 12, "action_status": "SUCCESS"},
    ),
    (
        {
            "timestamp": "2026-08-07T10:15:30Z",
            "eventId": "event-api-call",
            "eventType": "api.call",
            "tenantId": "demo-vendor",
            "extra": {"actionStatus": "SUCCESS", "actionName": "licensee.get",
                      "responseStatus": 200, "durationMs": 34},
        },
        {"action_name": "licensee.get", "response_status": 200, "duration_ms": 34},
    ),
]


def _schema_columns() -> set:
    return core_columns() | alter_table_columns(SCHEMA_NETLICENSING_SQL)


def test_extension_schema_parses_to_a_plausible_column_set():
    core, netlicensing = core_columns(), alter_table_columns(SCHEMA_NETLICENSING_SQL)
    assert {"licensee_number", "gross_amount", "base_amount", "mrr_delta"} <= netlicensing
    assert not core & netlicensing, "a column is declared in both schema files"
    assert "revenue_signed" not in _schema_columns(), "ALIAS columns must not be insertable"
    assert len(_schema_columns()) == 54


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
def test_indicative_kpi_events_promote_their_fields(event, expected):
    row = transform(event)

    # Sparse events emit a subset — an absent scalar is omitted so ClickHouse applies the DEFAULT.
    assert set(row).issubset(_schema_columns())
    assert {"timestamp", "event_time", "event_type", "tenant_id"} <= set(row)

    for column, value in expected.items():
        assert row[column] == value, f"{event['eventType']}: {column}"


def test_promoted_keys_are_documented_for_publishers():
    """A use-case layer publishes its own vocabulary — the generic OpenAPI contract deliberately
    does not carry it, so the KPI doc is where an integrator has to find these keys."""
    doc = KPI_DOC.read_text()
    undocumented = sorted(k for k in audit_clickhouse_netlicensing.PROMOTED if f"`{k}`" not in doc)
    assert not undocumented, f"promoted but undocumented in NETLICENSING_KPI.md: {undocumented}"
