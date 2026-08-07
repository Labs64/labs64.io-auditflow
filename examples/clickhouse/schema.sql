-- Canonical AuditFlow → ClickHouse audit event schema.
--
-- Single source of truth for the column contract. It is:
--   * mounted as the docker-compose init script (see docker-compose.yml, profile `full`)
--   * parsed by auditflow-transformer/tests/test_audit_clickhouse_contract.py
--   * reproduced in the Labs64.IO docs
--
-- Tune PARTITION BY and TTL for your retention policy before production use; the values below
-- are illustrative defaults, not a Labs64 retention recommendation.

CREATE DATABASE IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS audit.audit_events
(
    timestamp        DateTime64(3, 'UTC'),
    event_time       DateTime64(3, 'UTC'),
    event_id         UUID,
    correlation_id   String,

    event_type       LowCardinality(String),
    source_system    LowCardinality(String),
    tenant_id        LowCardinality(String),

    action_name      LowCardinality(String),
    action_status    LowCardinality(String),
    action_message   String,
    user_id          String,

    geo_lat          Nullable(Float64),
    geo_lon          Nullable(Float64),
    geo_country_code LowCardinality(String),
    geo_country      LowCardinality(String),
    geo_region       LowCardinality(String),
    geo_city         LowCardinality(String),

    extra            Map(LowCardinality(String), String)
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(timestamp)
ORDER BY (tenant_id, event_type, timestamp)
TTL toDateTime(timestamp) + INTERVAL 365 DAY;
