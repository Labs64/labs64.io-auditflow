"""NetLicensing ClickHouse transformer — the generic one plus a NetLicensing API vocabulary.

This is a **worked example of extending a bundled transformer**, not part of the AuditFlow core.
`audit_clickhouse` stays domain-free; everything NetLicensing-specific lives here, in a module that
is mounted into the transformer's bootstrap directory rather than shipped in the image (see the
`transformer` service in docker-compose.yml).

    audit_clickhouse    core audit columns   examples/clickhouse/schema.sql
    + this module       + NetLicensing/PG    + examples/clickhouse/schema-netlicensing.sql

The three layers have to agree, and each has a counterpart in the other two: a key here needs a
column in schema-netlicensing.sql to land in, and a documented meaning in NETLICENSING_EVENTS.md
for an integrator to discover. `tests/test_audit_clickhouse_netlicensing.py` fails if they drift.

Applying only `schema.sql` is still supported: `clickhouse_sink` inserts with
`input_format_skip_unknown_fields=1`, so these keys are dropped at insert time rather than failing
the delivery.

To build your own vocabulary, copy this file, replace PROMOTED, and pair it with your own
ALTER TABLE script. Only transformer modules belong in this directory — the plugin registry scans
it for `*.py` files and reports anything without a `transform` entry point as a broken plugin.

Usage — reference the module id from a pipeline (see tenants/demo.yaml):

    transformer:
      name: audit_clickhouse_netlicensing

Field meanings, the event templates that emit them, and the queries built on top:
examples/clickhouse/NETLICENSING_EVENTS.md
"""
from audit_clickhouse import make_transform

__version__ = "1.0.0"

PROPERTIES = {}

# NetLicensing / Payment Gateway `extra` keys → columns added by
# examples/clickhouse/schema-netlicensing.sql. Layered on top of the generic audit-semantics
# vocabulary that `audit_clickhouse` promotes on its own (userId, action_*, sessionId, durationMs,
# responseStatus) — those are not repeated here.
PROMOTED = {
    # Standard API call. `actionMethod` is the wire endpoint (`licensee/validate`), which is a
    # different granularity from the generic `actionName` (the semantic action) — see the note in
    # schema-netlicensing.sql on why both columns exist.
    "actionMethod": "action_method",
    "statusCode": "status_code",

    # Licensing / validation. `licensee/validate` carries highly variable args; the prominent ones
    # are promoted, and anything left over stays in the `extra` map.
    "licenseeNumber": "licensee_number",
    "productNumber": "product_number",
    "moduleNumber": "module_number",
    "nodeId": "node_id",
    "isDryRun": "is_dry_run",
    # Multi-module validations keep their per-module args as a serialized JSON string: nothing is
    # lost, the row stays flat, and ClickHouse's JSON* functions can still read into it.
    "validationArgs": "validation_args",
    # The licensing verdict, and when the license lapses. NetLicensing reports expirations and
    # renewal warnings in the `licensee/validate` *response* rather than as their own API method,
    # so these two keys are what make expiry reporting queryable at all.
    "validationOutcome": "validation_outcome",
    "validUntil": "valid_until",

    # Payment Gateway. Flattened from the OpenAPI request: `purchaseOrder` totals become
    # gross/net/tax, `billingInfo.country` becomes customerCountry, `paymentProviderId` becomes
    # paymentMethod, and `recurrence.expression` (P1M) becomes billingPeriod (MONTH).
    "transactionNumber": "transaction_number",
    "paymentMethod": "payment_method",
    "grossAmount": "gross_amount",
    "netAmount": "net_amount",
    "taxAmount": "tax_amount",
    "currency": "currency",
    "customerCountry": "customer_country",
    "billingPeriod": "billing_period",
}

transform = make_transform(PROMOTED, module_id=__name__)
