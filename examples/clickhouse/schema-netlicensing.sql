-- AuditFlow → ClickHouse licensing & monetization extension (NetLicensing use case).
--
-- Applied *after* schema.sql (docker-compose mounts this as 02-schema-netlicensing.sql). It adds
-- the dimensions a licensing/monetization KPI dashboard needs on top of the core audit columns,
-- plus one rollup for the high-volume operational metrics.
--
-- Why an extension rather than more columns in schema.sql: AuditFlow is a generic audit router.
-- The core contract has to stay domain-free, and a deployment that only wants an audit trail
-- should not carry MRR columns. This file is one third of a use-case layer; the other two are
-- the transformer that promotes the matching `extra` keys
-- (examples/netlicensing/audit_clickhouse_netlicensing.py) and the field/KPI documentation
-- (NETLICENSING_KPI.md). Copy the trio to model a different domain.
--
-- Applying only schema.sql stays supported: `clickhouse_sink` inserts with
-- `input_format_skip_unknown_fields=1`, so these keys are dropped at insert time instead of
-- failing the delivery.
--
-- All statements are idempotent (IF NOT EXISTS), so this file can also be run against an
-- existing table to upgrade it in place.
--
-- Field vocabulary — which `extra` key each column is promoted from, and what it means to a
-- publisher: the "Field vocabulary" section of NETLICENSING_KPI.md.

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Customer / licensee dimensions
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- The subject of the event: which customer it is about. Distinct from `user_id`, which is the
-- actor who performed it (often a vendor operator, not the customer).
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS licensee_number String;
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS customer_type LowCardinality(String);
-- Declared/billing country. `geo_country_code` is derived from the request IP, which for a
-- server-to-server licensing API is the *vendor's* infrastructure, not the customer's location —
-- do not build "customers by country" on the geo columns.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS customer_country LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS customer_segment LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS acquisition_channel LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS reseller_number LowCardinality(String);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Product / licensing dimensions
-- ─────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS product_number LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS module_number LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS license_number String;
-- The license template is what carries the price and the license type, so it is the dimension
-- that explains a revenue change caused by re-pricing rather than by volume.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS license_template_number LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS license_type LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS licensing_model LowCardinality(String);
-- Node/device a floating or node-locked license is bound to; the unit "concurrent usage" counts.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS node_id String;

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Entity state
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- The state the subject entity is in *after* this event (ACTIVE, SUSPENDED, EXPIRED, CANCELLED,
-- DELETED, ...). Deliberately separate from `action_status`: one event can be a SUCCESSful
-- action that moves a licensee to SUSPENDED, and collapsing the two makes both unusable.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS entity_status LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS entity_status_prev LowCardinality(String);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Commerce
-- ─────────────────────────────────────────────────────────────────────────────────────────────

ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS transaction_number String;
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS subscription_number String;
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS payment_method LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS quantity Nullable(UInt32);

-- Decimal, never Float: money summed as Float64 drifts, and a KPI report that disagrees with the
-- billing system by cents is a KPI report nobody trusts.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS gross_amount Nullable(Decimal(18, 4));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS net_amount Nullable(Decimal(18, 4));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS discount_amount Nullable(Decimal(18, 4));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS tax_amount Nullable(Decimal(18, 4));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS currency LowCardinality(String);

-- The same amount converted to the tenant's reporting currency at the rate that applied when the
-- transaction happened. Without this, every cross-product / cross-country revenue number is a sum
-- of mixed currencies — the single most common way a licensing dashboard lies. The publisher owns
-- the conversion: it knows the rate it actually billed at, and the rate must be frozen at event
-- time so historical reports stay reproducible.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS base_amount Nullable(Decimal(18, 4));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS base_currency LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS fx_rate Nullable(Decimal(18, 8));

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Recurring revenue
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- Signed change to normalized monthly recurring revenue caused by this event, in base currency:
-- positive for new/expansion, negative for contraction/churn, absent for one-off sales. MRR at
-- any date is then a running `sum(mrr_delta)` — an O(rows) scan instead of a point-in-time
-- reconstruction of every subscription's state. Only the publisher can compute it correctly
-- (it knows the billing period and the proration), which is why it is an emitted field and not
-- something derived downstream.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS mrr_delta Nullable(Decimal(18, 4));
-- DAY | WEEK | MONTH | YEAR | ONE_TIME — the period `gross_amount` covers.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS billing_period LowCardinality(String);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS billing_period_count Nullable(UInt16);
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS period_start Nullable(DateTime64(3, 'UTC'));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS period_end Nullable(DateTime64(3, 'UTC'));
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS is_trial Nullable(UInt8);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Derived
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- Revenue with refunds and chargebacks carried as negatives, so net revenue is a plain sum.
-- ALIAS, not MATERIALIZED: it costs no storage, and it is excluded from `SELECT *` and from
-- INSERT — which keeps the transformer's "output keys == insertable columns" contract intact.
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS revenue_signed Nullable(Decimal(18, 4))
    ALIAS multiIf(event_type IN ('payment.refunded', 'payment.chargeback'), -base_amount, base_amount);

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Indexes
-- ─────────────────────────────────────────────────────────────────────────────────────────────

-- Per-customer drilldown ("show me everything about LIC-1042") does not follow the sorting key.
ALTER TABLE audit.audit_events
    ADD INDEX IF NOT EXISTS idx_licensee licensee_number TYPE bloom_filter(0.01) GRANULARITY 4;
ALTER TABLE audit.audit_events
    ADD INDEX IF NOT EXISTS idx_product product_number TYPE set(1000) GRANULARITY 4;

-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- Operational rollup
-- ─────────────────────────────────────────────────────────────────────────────────────────────
--
-- Only the *operational* metrics are pre-aggregated. Validations and API calls outnumber payments
-- by orders of magnitude, and a duplicated row there is noise. Revenue and MRR are deliberately
-- NOT rolled up: a materialized view is an insert trigger, so it never sees the ReplacingMergeTree
-- deduplication that protects the raw table, and a DLQ replayed outside AuditFlow's idempotency
-- window would silently inflate the rollup. Money is queried from `audit_events FINAL`, which is
-- cheap precisely because payments are the low-volume events.

CREATE TABLE IF NOT EXISTS audit.ops_daily
(
    tenant_id      LowCardinality(String),
    day            Date,
    event_type     LowCardinality(String),
    action_name    LowCardinality(String),
    action_status  LowCardinality(String),
    product_number LowCardinality(String),
    module_number  LowCardinality(String),

    -- All the uniq states are conditional: an absent String column stores '', and counting the
    -- empty string as a distinct value would report "1 session" for every event type that has no
    -- sessions at all.
    events         AggregateFunction(count),
    licensees      AggregateFunction(uniqIf, String, UInt8),
    sessions       AggregateFunction(uniqIf, String, UInt8),
    nodes          AggregateFunction(uniqIf, String, UInt8),
    latency_ms     AggregateFunction(quantilesIf(0.5, 0.95, 0.99), UInt32, UInt8)
)
ENGINE = AggregatingMergeTree
PARTITION BY toYYYYMM(day)
ORDER BY (tenant_id, day, event_type, action_name, action_status, product_number, module_number)
TTL day + INTERVAL 1095 DAY;

CREATE MATERIALIZED VIEW IF NOT EXISTS audit.mv_ops_daily TO audit.ops_daily AS
SELECT
    tenant_id,
    toDate(event_time) AS day,
    event_type,
    action_name,
    action_status,
    product_number,
    module_number,
    countState()                                       AS events,
    uniqIfState(licensee_number, licensee_number != '') AS licensees,
    uniqIfState(session_id, session_id != '')           AS sessions,
    uniqIfState(node_id, node_id != '')                 AS nodes,
    quantilesIfState(0.5, 0.95, 0.99)(ifNull(duration_ms, 0), duration_ms IS NOT NULL) AS latency_ms
FROM audit.audit_events
GROUP BY tenant_id, day, event_type, action_name, action_status, product_number, module_number;
