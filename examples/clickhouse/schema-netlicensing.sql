-- AuditFlow → ClickHouse NetLicensing extension (NetLicensing API + Payment Gateway use case).
--
-- Applied *after* schema.sql (docker-compose mounts this as 02-schema-netlicensing.sql). It adds
-- the dimensions the NetLicensing API audit events carry on top of the core audit columns.
--
-- Why an extension rather than more columns in schema.sql: AuditFlow is a generic audit router.
-- The core contract has to stay domain-free, and a deployment that only wants an audit trail
-- should not carry licensing columns. This file is one third of a use-case layer; the other two
-- are the transformer that promotes the matching `extra` keys
-- (examples/netlicensing/audit_clickhouse_netlicensing.py) and the field/query documentation
-- (NETLICENSING_EVENTS.md). Copy the trio to model a different domain.
--
-- Applying only schema.sql stays supported: `clickhouse_sink` inserts with
-- `input_format_skip_unknown_fields=1`, so these keys are dropped at insert time instead of
-- failing the delivery.
--
-- All statements are idempotent (IF NOT EXISTS), so this file can also be run against an
-- existing table to upgrade it in place.
--
-- Field vocabulary — which `extra` key each column is promoted from, and what it means to a
-- publisher: the "Field vocabulary" section of NETLICENSING_EVENTS.md.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Standard API call
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- The API endpoint that was called (`licensee/validate`, `payments/pay`). Deliberately separate
-- from the core `action_name`, which is the *semantic* action ("payment.capture"): a NetLicensing
-- event knows its wire method, a hand-instrumented event knows its business action, and folding
-- the two into one column makes neither queryable. An event normally fills one or the other.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS action_method LowCardinality(String);
-- Mirrors the core `response_status` for publishers that emit the spec's `statusCode` taxonomy.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS status_code Nullable(UInt16);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Licensing / validation
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- The subject of the event: which customer it is about. Distinct from `user_id`, which is the
-- actor who performed it (often a vendor operator, not the customer).
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS licensee_number String;
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS product_number LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS module_number LowCardinality(String);
-- Node/device a floating or node-locked license is bound to; the unit "concurrent usage" counts.
-- Mapped from the request's `nodeSecret`.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS node_id String;
-- A dry run validates without consuming or binding anything. Kept separate so usage metrics can
-- exclude it — counting dry runs as real validations overstates every consumption number.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS is_dry_run Nullable(UInt8);
-- Multi-module validations, kept as the serialized JSON the publisher sent. A variable-length
-- array of per-module args has no flat column shape; storing the string loses nothing and stays
-- queryable through ClickHouse's JSON* functions.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS validation_args String;

-- The licensing verdict the validation returned: VALID | EXPIRING_SOON | EXPIRED | GRACE_PERIOD |
-- INVALID. Deliberately separate from `action_status`, which is the outcome of the *call*: a
-- validation that correctly reports an expired license is a SUCCESSful API call with an EXPIRED
-- outcome, and collapsing the two makes both unusable. This is what turns expirations and renewal
-- warnings into a query — NetLicensing carries them in the validation response, not as their own
-- API method, so there is no `licensee/expire` event to count instead.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS validation_outcome LowCardinality(String);
-- When the validated license stops being valid. Lets "who expires in the next 30 days" be answered
-- from the audit stream rather than by querying the licensing system itself.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS valid_until Nullable(DateTime64(3, 'UTC'));

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Payment Gateway
-- ─────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS transaction_number String;
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS payment_method LowCardinality(String);

-- Decimal, never Float: money summed as Float64 drifts, and a report that disagrees with the
-- billing system by cents is a report nobody trusts.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS gross_amount Nullable(Decimal(18, 4));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS net_amount Nullable(Decimal(18, 4));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS tax_amount Nullable(Decimal(18, 4));
-- Amounts are stored in the currency they were billed in. Summing across currencies is therefore
-- wrong — group by `currency`, or have the publisher emit a converted amount if you need one
-- number. (This example stays deliberately short of a reporting-currency model.)
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS currency LowCardinality(String);
-- Declared billing country. `geo_country_code` is derived from the request IP, which for a
-- server-to-server API is the *vendor's* infrastructure, not the customer's location — do not
-- build "customers by country" on the geo columns.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS customer_country LowCardinality(String);
-- DAY | WEEK | MONTH | YEAR | ONE_TIME — the period `gross_amount` covers, translated by the
-- publisher from the OpenAPI `recurrence.expression` (P1M → MONTH).
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS billing_period LowCardinality(String);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Indexes
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- Per-customer drilldown ("show me everything about LIC-1042") does not follow the sorting key.
ALTER TABLE audit.audit_events
    ADD INDEX IF NOT EXISTS idx_licensee licensee_number TYPE bloom_filter(0.01) GRANULARITY 4;
ALTER TABLE audit.audit_events
    ADD INDEX IF NOT EXISTS idx_product product_number TYPE set(1000) GRANULARITY 4;
