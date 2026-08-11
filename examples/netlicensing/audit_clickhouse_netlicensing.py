"""NetLicensing ClickHouse transformer — the generic one plus a licensing/monetization vocabulary.

This is a **worked example of extending a bundled transformer**, not part of the AuditFlow core.
`audit_clickhouse` stays domain-free; everything NetLicensing-specific lives here, in a module that
is mounted into the transformer's bootstrap directory rather than shipped in the image (see the
`transformer` service in docker-compose.yml).

    audit_clickhouse    core audit columns        examples/clickhouse/schema.sql
    + this module       + licensing/monetization  + examples/clickhouse/schema-netlicensing.sql

The three layers have to agree, and each has a counterpart in the other two: a key here needs a
column in schema-netlicensing.sql to land in, and a documented meaning in NETLICENSING_KPI.md for
an integrator to discover. `tests/test_audit_clickhouse_netlicensing.py` fails if they drift.

Applying only `schema.sql` is still supported: `clickhouse_sink` inserts with
`input_format_skip_unknown_fields=1`, so the licensing keys are dropped at insert time rather than
failing the delivery.

To build your own vocabulary, copy this file, replace PROMOTED, and pair it with your own
ALTER TABLE script. Only transformer modules belong in this directory — the plugin registry scans
it for `*.py` files and reports anything without a `transform` entry point as a broken plugin.

Usage — reference the module id from a pipeline (see tenants/demo.yaml):

    transformer:
      name: audit_clickhouse_netlicensing

Field meanings, the emitting event catalog and the KPI query book built on them:
examples/clickhouse/NETLICENSING_KPI.md
"""
from audit_clickhouse import make_transform

__version__ = "1.0.0"

PROPERTIES = {}

# Licensing / monetization `extra` keys → columns added by
# examples/clickhouse/schema-netlicensing.sql. Layered on top of the generic audit-semantics
# vocabulary that `audit_clickhouse` promotes on its own (userId, action_*, sessionId, durationMs,
# responseStatus) — those are not repeated here.
PROMOTED = {
    # Customer
    "licenseeNumber": "licensee_number",
    "customerType": "customer_type",
    "customerCountry": "customer_country",
    "customerSegment": "customer_segment",
    "acquisitionChannel": "acquisition_channel",
    "resellerNumber": "reseller_number",
    # Product / licensing
    "productNumber": "product_number",
    "moduleNumber": "module_number",
    "licenseNumber": "license_number",
    "licenseTemplateNumber": "license_template_number",
    "licenseType": "license_type",
    "licensingModel": "licensing_model",
    "nodeId": "node_id",
    # Entity state
    "entityStatus": "entity_status",
    "entityStatusPrev": "entity_status_prev",
    # Commerce
    "transactionNumber": "transaction_number",
    "subscriptionNumber": "subscription_number",
    "paymentMethod": "payment_method",
    "quantity": "quantity",
    "grossAmount": "gross_amount",
    "netAmount": "net_amount",
    "discountAmount": "discount_amount",
    "taxAmount": "tax_amount",
    "currency": "currency",
    "baseAmount": "base_amount",
    "baseCurrency": "base_currency",
    "fxRate": "fx_rate",
    # Recurring revenue
    "mrrDelta": "mrr_delta",
    "billingPeriod": "billing_period",
    "billingPeriodCount": "billing_period_count",
    "periodStart": "period_start",
    "periodEnd": "period_end",
    "isTrial": "is_trial",
}

transform = make_transform(PROMOTED, module_id=__name__)
