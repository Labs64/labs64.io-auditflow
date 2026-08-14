-- Canonical AuditFlow → ClickHouse audit event schema (core).
--
-- Single source of truth for the core column contract. It is:
--   * mounted as the first docker-compose init script (see docker-compose.yml, default stack)
--   * parsed by auditflow-transformer/tests/test_audit_clickhouse_contract.py
--   * reproduced in the Labs64.IO docs
--
-- These columns are what the domain-free `audit_clickhouse` transformer emits. Keep both sides
-- generic: a use case adds its own columns in a sibling ALTER TABLE script applied after this one,
-- paired with a transformer module that promotes the matching `extra` keys. See
-- schema-netlicensing.sql + examples/netlicensing/audit_clickhouse_netlicensing.py for a worked
-- example. `clickhouse_sink` sends `input_format_skip_unknown_fields=1`, so a pipeline using an
-- extension against a table that only has these columns still works — the extra keys are dropped
-- at insert time rather than failing the delivery.
--
-- Tune PARTITION BY and TTL for your retention policy before production use; the values below
-- are illustrative defaults, not a Labs64 retention recommendation.

CREATE DATABASE IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS audit.audit_events
(
    -- ── Time ─────────────────────────────────────────────────────────────────────────────────
    -- `timestamp` is the server *receipt* time (AuditFlow assigns it, clients cannot).
    -- `event_time` is the *business* time the audited action happened at the source; the
    -- transformer falls back to `timestamp` when the publisher omits it. Analytics is keyed on
    -- event_time — a backfill or a broker backlog must not move revenue into the wrong month.
    timestamp        DateTime64(3, 'UTC'),
    event_time       DateTime64(3, 'UTC'),

    -- ── Identity ─────────────────────────────────────────────────────────────────────────────
    event_id         UUID,
    correlation_id   String,
    event_type       LowCardinality(String),
    source_system    LowCardinality(String),
    tenant_id        LowCardinality(String),

    -- ── Action ───────────────────────────────────────────────────────────────────────────────
    -- `action_status` is the outcome of the *action* (SUCCESS / FAILURE / DENIED). It is not an
    -- entity state: a SUCCESSful call can be what moves a subscription to CANCELLED, and a use
    -- case that needs the entity's state adds its own column for it rather than overloading this.
    -- `action_name` is the semantic action ("payment.capture"); a use case whose events are API
    -- calls may prefer its own endpoint column instead (see `action_method` in
    -- schema-netlicensing.sql).
    action_name      LowCardinality(String),
    action_status    LowCardinality(String),
    action_message   String,
    user_id          String,
    session_id       String,
    duration_ms      Nullable(UInt32),
    response_status  Nullable(UInt16),

    -- ── Geolocation ──────────────────────────────────────────────────────────────────────────
    -- Derived from the request IP, which for a server-to-server API is the *caller's*
    -- infrastructure, not the end customer's location. Do not build demographics on it; publish
    -- a declared country as an `extra` key promoted by a use-case extension instead.
    geo_lat          Nullable(Float64),
    geo_lon          Nullable(Float64),
    geo_country_code LowCardinality(String),
    geo_country      LowCardinality(String),
    geo_region       LowCardinality(String),
    geo_city         LowCardinality(String),

    extra            Map(LowCardinality(String), String),

    -- Forensic lookups ("what did we ingest between 09:00 and 09:05") do not follow the sorting
    -- key, which is ordered on business time. A minmax index keeps them from full-scanning.
    INDEX idx_ingest_time timestamp TYPE minmax GRANULARITY 4
)
-- ReplacingMergeTree, not MergeTree: AuditFlow is at-least-once. Its idempotency window is ~24h,
-- so a DLQ replayed after that would insert a second copy of an event — tolerable for a log,
-- fatal for revenue. `event_id` is in the sorting key and the row version is the ingest
-- `timestamp`, so the later copy wins. Deduplication happens on merge; use FINAL (or aggregate
-- through a rollup of your own) when exactness matters at query time.
ENGINE = ReplacingMergeTree(timestamp)
PARTITION BY toYYYYMM(event_time)
ORDER BY (tenant_id, event_type, event_time, event_id)
-- Three years, not one. Year-over-year needs at least 24 months on hand, and a TTL shorter than
-- the reporting window deletes the comparison period silently — the report does not fail, it just
-- starts showing an empty prior year. Raise or lower this to match your retention policy, but
-- check it against the longest window any dashboard looks back over first.
TTL toDateTime(event_time) + INTERVAL 1095 DAY;
