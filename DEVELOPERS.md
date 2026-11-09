# AuditFlow — Developer Guide

Everything you need to work with AuditFlow locally, test it, and troubleshoot issues.

> Consult the [AuditFlow reference documentation](https://labs64.io/docs/modules/auditflow/) first. It covers configuring, running and operating AuditFlow. This guide covers working on the code: layout, running locally, testing, and adding sinks and transformers.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Time fields: event time and receipt time](#time-fields-event-time-and-receipt-time)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Project Layout](#project-layout)
- [Running Locally](#running-locally)
- [Manual Verification (Smoke Test)](#manual-verification-smoke-test) — step-by-step, for testers
- [Testing](#testing)
- [Adding a New Sink](#adding-a-new-sink)
- [Adding a New Transformer](#adding-a-new-transformer)
- [Health & Observability](#health--observability)
  - [Observability Stack (OTel / Grafana / Prometheus / Loki / Tempo)](#observability-stack)
- [Troubleshooting](#troubleshooting)
- [Getting-Started Notebook](#getting-started-notebook-stack-must-be-running)

---

## Architecture Overview

AuditFlow is a microservices-based audit-logging pipeline:

```
POST /audit/publish  (direct; via gateway: /auditflow/api/v1/audit/publish)
        │
        ▼
    Backend (Java / Spring Boot :8080)
        │  publishes to the broker, answers 200 once the broker stored it
        ▼
    Broker  ingest           (RabbitMQ labs64-audit-topic · SQS <prefix>ingest)
        │
        ▼  router: AuditService.processAuditEvent()
        │  one delivery message per ENABLED pipeline whose condition matches
    Broker  delivery ◄── delay 5s…3h (retry / defer)
        │            ──► tenant DLQ    (exhausted / poison)
        │  RabbitMQ: labs64-audit-delivery, labs64-audit-delay.<n>s, labs64-audit-dlq.<tenant>
        │  SQS:      <prefix>delivery (per-message delays), <prefix>dlq-<tenant>
        ▼  DeliveryWorker (batched) → PipelineExecutor
        ├─► Transformer (Python / FastAPI :8081)  POST /transform/{name}
        └─► Sink        (Python / FastAPI :8082)  POST /sink/{name}[/batch]
```

Three independently deployable services:

| Service | Stack | Port | Role |
|---------|-------|------|------|
| `auditflow-be/` | Java 25, Spring Boot 4, Maven | 8080 | REST API, broker publish/consume, pipeline orchestration |
| `auditflow-transformer/` | Python 3.14, FastAPI, Uvicorn | 8081 | Dynamically-loaded transform modules |
| `auditflow-sink/` | Python 3.14, FastAPI, Uvicorn | 8082 | Dynamically-loaded sink/delivery modules |

Key design decisions:
- **Pipelines are configuration, not code.** Define them in `application.yml` or via `JAVA_OPTS` environment variables.
- **Sink/transformer resolution is dynamic.** A request to `/sink/{name}` does `importlib.import_module(name)` — drop a `.py` file in `sinks/` and it becomes available immediately.
- **Idempotency/dedup** prevents duplicate event processing. Default store is Redis (Valkey); the local compose stack uses it too.
- **Circuit breakers + retry** guard all outbound HTTP calls to transformer/sink services; delivery
  retries over hours, backpressure and the per-tenant DLQ are described in
  [Delivery model](https://labs64.io/docs/modules/auditflow/delivery-model/).

---

## Time fields: event time and receipt time

Every event has exactly two times. They mean different things, and everything downstream uses them the
same way.

| | **Event time** | **Receipt time** |
|---|---|---|
| Field | `eventTime` (`event_time` in ClickHouse and OpenSearch, `eventtime` in Athena) | `timestamp` |
| Means | when the audited action **happened** at the source | when AuditFlow **received** the event |
| Set by | the publisher; AuditFlow fills in the receipt time when it is omitted | AuditFlow only (a client value is ignored) |
| Use it for | reports, trends, "when did it happen" | arrival order, deduplication (the later copy wins), an archive's date folders |
| Trust | the publisher's claim, kept as sent | AuditFlow's own clock |

They are equal for a publisher that reports as it acts, and differ for a **backfill**, a queued or
retried publish, or a publisher whose clock is off. **Report on event time, never on receipt time:** a
backfill of two years of history would land in today's bucket. **Lay storage out by receipt time:** a
publisher must not be able to choose where its data lands. No third time is part of an event: delivery to a
sink happens later (hours later with retries, days after a DLQ replay) and is never recorded in it.

### What ingest does

- `timestamp` is stamped with the server clock; any client value is ignored.
- `eventTime` is kept as sent. When it is omitted it becomes the receipt time, so **every consumer finds
  it set** and needs no fallback of its own.
- An `eventTime` outside a window around the receipt time is rejected with `400 VALIDATION_ERROR` (in a
  batch: that event only, and it spends no quota), so a wrong clock cannot create stray partitions or rows
  that expire at once. The window is `auditflow.event-time.max-future` (default `PT5M`, clock skew) and
  `max-age` (default `P1095D`, the shipped ClickHouse retention); keep `max-age` at or below the shortest
  retention among your tenants' sinks.
- A 400 says which limit was hit. Fix the publisher's clock, or **omit `eventTime`** to use the receipt time.

Implemented once, in `EventTimePolicy` (backend); the contract text is in `openapi-audit-v1.yaml`.

### Which time each destination uses

| Destination | Time | Notes |
|---|---|---|
| ClickHouse (`audit_clickhouse` + `clickhouse_sink`) | `event_time` = event time: partition, sort, TTL, every report. `timestamp` = receipt time: dedup version only | |
| OpenSearch (`audit_opensearch` + `opensearch_sink`) | both, as `event_time` and `timestamp` | build dashboards on `event_time` |
| Loki (`audit_loki`, `loki_sink`) | the line sits at the event time | Loki rejects samples older than its `reject_old_samples_max_age` |
| Splunk (`splunk_sink`) | HEC `time` = event time | Splunk indexes the event at when it happened |
| Syslog (`syslog_sink`) | header time = event time (UTC) | |
| CloudWatch Logs (`aws_cloudwatch_sink`) | entry at the **receipt** time of the newest event of the call | CloudWatch refuses entries older than 14 days and unordered calls; `eventTime` stays in the message. An event replayed after 13 days is placed 13 days back |
| S3, GCS, Azure Blob (`aws_s3_sink`, `gcs_sink`, `azure_blob_sink`) | date folders and object names = **receipt** day (UTC) | append-only layout a publisher cannot influence; a backfill lands in today's folder. `eventTime` is inside the object; object metadata carries both (`received-at`, `event-time`) |
| PostgreSQL (`postgres_sink`) | both inside `event_data`; `inserted_at` is the delivery time | never report on `inserted_at` |
| Datadog, webhook, logging, Snowflake, NetLicensing sinks | the event as it is, both times inside | the destination's own date handling applies: to date a Datadog log by event time, point its Log Date remapper at `eventTime` |

Reading the S3 archive (Athena): the `year/month/day` partitions are the **receipt** day; `eventtime` is the
event time. A report "per day it happened" selects the receipt days it needs (a late arrival is in a later
folder) and filters `eventtime` itself.

### For authors of transformers and sinks

Never read `timestamp` or `eventTime` directly and never read the clock to stamp an entry. Use
`auditflow_sdk.event_time(event)` for "the time of this entry" and, in a sink, `received_at(event)` for storage
layout (the transformer SDK has the same two names, returning the ISO string). Tests enforce this for every
module in `transformers/` and `sinks/` (`tests/test_time_fields.py`): a new module is covered on arrival. A
destination that stamps its own arrival time (a `DEFAULT now()` column) says so in its name (`inserted_at`).

**Rolling upgrade.** An event queued before ingest started setting `eventTime` has none; the SDK helpers fall back
to its receipt time, so such events still land correctly. After the queues drain the fallback is never used.

---

## Prerequisites

| Requirement | Check command | Minimum version |
|---|---|---|
| Java (Temurin) | `java --version` | 25 |
| Maven | `mvn --version` | 3.6.3+ |
| Python | `python3 --version` | 3.14 |
| Docker Engine | `docker --version` | 24+ |
| Docker Compose v2 | `docker compose version` | v2.x |
| `just` task runner | `just --version` | any |
| `curl` | `curl --version` | any |

Optional but useful:
- `jq` — pretty-print JSON in shell commands
- `gh` — GitHub CLI for PR/issue workflows

**Credentials:** The stack uses `guest`/`guest` by default. Copy `.env.example` to `.env` only if you need custom credentials:

```bash
cp .env.example .env
```

---

## Quick Start

Four steps, each with the command and what you should see. Takes about a minute.

**1. Build and start the full stack**

```bash
just up
```
Builds the backend JAR, builds all three Docker images, and starts the stack. Wait for the URL
banner it prints at the end — that's your signal everything is up.

**2. Publish a test event**

```bash
curl -s -X POST http://localhost:8080/audit/publish \
  -H "Content-Type: application/json" \
  -d '{"eventType":"user.login","sourceSystem":"test","tenantId":"demo"}'
```
Expect the response body `Audit event published successfully`.

**3. Check it arrived in the sink**

```bash
just log sink
# Ctrl+C to stop tailing
```
Expect a `POST /sink/logging_sink` log line carrying the event you just sent — that's the full
publish → broker → transform → sink round trip working.

**4. Tear down**

```bash
just down
```

Ready to go further? [Manual Verification (Smoke Test)](#manual-verification-smoke-test) walks
through the rest of the core paths — tenant isolation, redaction, the DLQ — the same way.

---

## Project Layout

```
labs64.io-auditflow/
├── auditflow-api/           # Java client library + canonical OpenAPI spec
│   └── src/main/resources/openapi/openapi-audit-v1.yaml  # Single source of truth for API contract
├── auditflow-be/            # Java backend (Spring Boot)
│   ├── src/main/java/       # Service code
│   ├── src/test/java/       # JUnit tests
│   └── src/main/resources/
│       └── application.yml  # Main configuration (tenant source, broker, OTel endpoints)
├── auditflow-transformer/   # Python transformer service
│   ├── transformer.py       # FastAPI app
│   ├── transformers/        # Built-in transformers (zero, audit_loki, audit_opensearch, audit_clickhouse)
│   ├── transformers_bootstrap/  # Mounted at runtime for custom transformers (compose mounts examples/netlicensing/)
│   └── tests/
├── auditflow-sink/          # Python sink service
│   ├── sink.py              # FastAPI app
│   ├── sinks/               # Built-in sinks (13 available)
│   ├── sinks_bootstrap/     # Mounted at runtime for custom sinks
│   └── tests/
├── docker-compose.yml              # Local stack (3 services + RabbitMQ + Valkey + Cerbos + ClickHouse)
├── docker-compose-observability.yml # Observability overlay (OTel Collector + Tempo + Loki + Prometheus + Grafana)

├── examples/                       # Runnable examples, not part of any image
│   ├── getting-started.ipynb       # Hands-on walkthrough (section 6 = ClickHouse round trip)
│   ├── clickhouse/                 # schema.sql (core) + the NetLicensing layer's DDL, KPI book, seeder
│   └── netlicensing/               # Use-case transformer, mounted into transformers_bootstrap/
├── observability/                  # Config for the observability overlay
│   ├── otel-collector/config.yaml  # OTel Collector: receivers, processors, exporters
│   ├── prometheus/prometheus.yml   # Prometheus scrape targets
│   ├── loki/loki.yaml              # Loki log storage config
│   ├── tempo/tempo.yaml            # Tempo trace storage config
│   └── grafana/                    # Grafana provisioning (datasources + dashboards)
├── justfile                        # Top-level task runner
└── .env.example                    # RabbitMQ credentials template
```

---

## Running Locally

### Docker (recommended)

All 3 services + RabbitMQ + Valkey + Cerbos + ClickHouse — the Kubernetes stack minus the gateway, with Redis-backed (Valkey) idempotency and rate limiting like the chart.

```bash
just up        # build JAR + Docker images, start everything
just up obs    # start with observability overlay
just logs      # tail all service logs (Ctrl+C to stop)
just down      # stop containers (keeps images)
just clean     # stop + remove volumes (full reset)
```

### ClickHouse (analytics sink)

ClickHouse is part of the default stack so the local setup delivers events to a **queryable**
destination, not just a log line. The `clickhouse-analytics` pipeline is enabled for both the
`demo` and `_platform` tenants, and two init scripts are applied on first start:

| File | Contains |
|---|---|
| `examples/clickhouse/schema.sql` | Core audit columns — the generic, domain-free contract. |
| `examples/clickhouse/schema-netlicensing.sql` | The NetLicensing example layer: the columns the NetLicensing API and Payment Gateway events carry. |

They are layered rather than merged so a deployment that only wants an audit trail is not forced to
carry licensing columns — see [Extending it for a use case](#extending-it-for-a-use-case) below.

**Try it, from the shell** (stack must be running — `just up`):

**1. Seed a dataset.** Publishes ~2000 events over 30 days — NetLicensing entity CRUD, licensee
and license lifecycle, `licensee/validate` traffic with its expiry verdicts, and correlated
Payment Gateway lifecycles (create → pay → close, cancellations, renewals):
```bash
just ch-seed
just ch-seed "--events 20000 --days 90"   # bigger stream
just ch-seed "--seed 7"                   # reproducible
```

**2. Look at what landed:**
```bash
just ch-events        # 20 most recent stored events
just ch-stats         # counts grouped by tenant / API method / status
```

**3. Run your own SQL**, or drop into an interactive shell:
```bash
just ch "SELECT count() FROM audit_events WHERE tenant_id = 'demo'"
just ch-shell          # interactive clickhouse-client
```

Also reachable at http://localhost:8123/play (`auditflow` / `auditflow`), and over the native
protocol on `localhost:9000` for JDBC/ODBC drivers and BI tools.

For the fully asserted round trip — publish → poll → `SELECT` → check every column — see
**section 6 of `examples/getting-started.ipynb`**, run via `just notebook-getting-started`. For a
tour of the event vocabulary and the queries built on the seeded data, see
[`examples/clickhouse/NETLICENSING_EVENTS.md`](examples/clickhouse/NETLICENSING_EVENTS.md).

#### Extending it for a use case

`audit_clickhouse` and `schema.sql` are deliberately domain-free: they promote only the generic
audit-semantics keys published in the `Extra` schema of the AuditEvent contract, and leave
everything else in the `extra` `Map(String, String)` column. A domain usually wants its own keys as
real columns — a column store rewards a dedicated column wherever a key is a `GROUP BY` dimension —
so it **layers** on top rather than widening the core:

```python
# examples/netlicensing/audit_clickhouse_netlicensing.py
from audit_clickhouse import make_transform

PROMOTED = {"licenseeNumber": "licensee_number", "actionMethod": "action_method", ...}
transform = make_transform(PROMOTED)
```

```sql
-- examples/clickhouse/schema-netlicensing.sql, applied after schema.sql
ALTER TABLE audit.audit_events ADD COLUMN IF NOT EXISTS licensee_number String;
```

```yaml
# tenants/demo.yaml
transformer:
  name: audit_clickhouse_netlicensing
```

A transformer takes no pipeline properties, so the **module id is what selects a vocabulary** — one
module per domain, chosen per pipeline. Two pipelines can therefore write different column subsets
to the same table, which is exactly what the compose stack demonstrates: `_platform` uses the
generic transformer, `demo` uses the NetLicensing layer.

The example is **mounted, not shipped** — compose bind-mounts `examples/netlicensing/` onto the
transformer's `transformers_bootstrap` directory, the same runtime extension point (a ConfigMap in
Kubernetes) an integrator uses for their own module. Only transformer modules belong in that
directory; the registry reports any other `.py` file as a broken plugin.

Nothing fails if a layer is only half-applied: the sink inserts with
`input_format_skip_unknown_fields=1`, so a promoted key with no column is dropped at insert time
rather than dead-lettering the event, and applying only `schema.sql` stays supported. A deployment that would
rather hear about it sets the sink property `unknown-columns: reject`: the insert then fails, and the
event is retried and dead-lettered, replayable once the column exists. That tolerance is also why the
pairing needs a test —
`auditflow-transformer/tests/test_audit_clickhouse_netlicensing.py` asserts that every promoted key
has a column, every column has a key, and every key is documented for publishers.

The NetLicensing event model — the field vocabulary, the event templates a publisher such as
NetLicensing or the Payment Gateway emits, and the queries built on them — is
[`examples/clickhouse/NETLICENSING_EVENTS.md`](examples/clickhouse/NETLICENSING_EVENTS.md).

Worth knowing when evaluating ClickHouse here:

- **Aggregate on `event_time`, not `timestamp`.** `timestamp` is when AuditFlow received the event;
  `event_time` is when the action happened. The table is partitioned, sorted and expired on
  `event_time` for that reason. Group revenue on `timestamp` and a backfill of two years of history
  lands every euro of it in today's bucket. (Both times: [Time fields](#time-fields-event-time-and-receipt-time).)
- `tenant_id` is the **leading `ORDER BY` column**. A tenantless event stores an empty string —
  routing maps a null tenant to `_platform`, but the event *body* the transformer reads keeps its
  null `tenantId`. Publish with an explicit `tenantId` for representative partition pruning.
- `audit_events` is a **`ReplacingMergeTree`** keyed on `event_id`: AuditFlow is at-least-once with
  a ~24h idempotency window, so a DLQ replayed later would otherwise double-count revenue. Dedup
  happens on merge, so exact money queries use `FINAL`.
- Redaction runs in the **backend**, before the pipeline, so it is visible in the stored row: the
  compose `JAVA_OPTS` mask `extra.userId` (→ `user_id = '***'`) and drop `extra.apiKey`. They
  deliberately avoid keys that are promoted to reporting dimensions — a redacted dimension makes a
  dashboard look empty rather than broken.
- The sink relies on server-side `async_insert` with `wait_for_async_insert=1`, so a delivery
  succeeds only once the part is flushed — expect a sub-second delay before a `SELECT` sees the row.
  With `batch.enabled` on the pipeline the rows of a batch are one `INSERT`, which is the better way
  to feed MergeTree; `async_insert` stays on and merges batches that arrive close together.
- The init scripts only run on an **empty data dir**. After editing either schema file, run
  `just clean && just up` — a plain restart keeps the old table.
- **Truncating the table is not the same as a clean slate.** `TRUNCATE TABLE audit_events` empties
  ClickHouse, but the backend's idempotency store (Valkey) still remembers
  every event ID as already delivered. Republishing the same events (e.g. re-running `just
  ch-seed`) then reports success at the HTTP layer but silently delivers **zero** rows — the
  consumer sees only duplicates. `docker compose exec redis sh -c 'valkey-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHALL'` clears that state; `just clean
  && just up` does both at once.

### Extending the `extra` vocabulary

Promoting an `extra` key into a queryable field works the same way on any promoting transformer, not
just `audit_clickhouse` — two paths, same effect:

```python
# 1. A transformer module: per-pipeline, one module per domain.
#    Drop it into transformers_bootstrap/ (compose mounts it) or ship it as a wheel.
from audit_clickhouse import make_transform
transform = make_transform({"orderRef": "order_ref", "carrier": "carrier"}, module_id=__name__)
```

```yaml
# 2. Configuration only: deployment-wide, no code.
environment:
  AUDITFLOW_PROMOTED_KEYS: '{"orderRef": "order_ref", "carrier": "carrier"}'
```

Either path needs the same second half: a matching column in the sink schema. ClickHouse inserts
with `input_format_skip_unknown_fields=1` by default, so a promoted key with no column is **silently
dropped at insert time**, not rejected, unless the sink sets `unknown-columns: reject` — that is true of the module path and the config-only path equally;
neither one is schema-agnostic on its own.

Precedence, lowest to highest: the module's built-in vocabulary → its `make_transform(extra_promoted)`
argument → `AUDITFLOW_PROMOTED_KEYS` → `AUDITFLOW_PROMOTED_KEYS_<MODULE_ID>`. Code declares the
domain and the operator overrides the code.

A malformed `AUDITFLOW_PROMOTED_KEYS*` value — invalid JSON, a non-object, or a target name that
isn't a plain identifier — raises `ValueError` at import. That is handled exactly like any other
broken plugin: `PluginRegistry` excludes the module rather than crashing the service, `GET
/registry` lists it under its errors, and a pipeline still pointed at that `transformer.name` gets a
404 — never a silently missing column.

Target field names land in SQL identifier position (a ClickHouse column name), so they are
constrained to `^[a-zA-Z_][a-zA-Z0-9_]*$` — anything else would need quoting, or could inject SQL if
a target name were ever interpolated instead of bound.

#### Migrating from audit_opensearch 1.x

`audit_opensearch` 2.0.0 promotes all 6 well-known keys instead of 4. `durationMs` and `responseStatus` now land in
top-level `duration_ms` and `response_status` instead of staying inside the `extra` object — update
any query or dashboard reading `extra.durationMs` (etc.). The index mapping gains **four** fields in
total: those same two moved-out fields, plus
`event_time` and `correlation_id`, which are genuinely new — 1.x never emitted an `eventTime` or
`correlationId` top-level field at all, promoted or not. `audit_loki` 2.0.0 additionally stops
emitting the placeholder labels `action_name="unknown_action"` / `action_status="unknown_status"`
and the log line `"N/A"` — a panel filtering on those was matching fabricated data.

### Observability Stack

The observability overlay adds OTel Collector, Tempo (traces), Loki (logs), Prometheus (metrics), and Grafana (dashboards) to any base stack. See [Health & Observability](#health--observability) for full details.

```bash
just up obs         # stack + observability overlay
```

### Host-Based Development

Run infrastructure in Docker, services directly on your machine for faster iteration:

```bash
# 1. Start only RabbitMQ + Redis (Valkey)
docker compose up rabbitmq redis -d

# 2. In separate terminals, run each service with hot-reload:
cd auditflow-transformer && just run-local   # http://localhost:8081
cd auditflow-sink        && just run-local   # http://localhost:8082

# 3. Run the backend with Maven:
cd auditflow-be
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.rabbitmq.host=localhost -Dspring.rabbitmq.port=5673"
```

---

## Manual Verification (Smoke Test)

A scripted checklist for confirming a build works end-to-end — before signing off on a change, or
as a first "does this even work" pass if you're new to the repo. Each step is one action and one
expected result, so a pass/fail is unambiguous at every step. Takes about 10 minutes; assumes
nothing is running yet.

This is the manual counterpart to [Testing](#testing) below: the automated suites check units and
individual endpoints, this checklist checks the paths a real integrator or auditor cares about —
delivery, tenant isolation, redaction, and the DLQ safety net — by actually driving the running
stack.

**1. Start clean**

```bash
just clean   # only if a previous stack is still around — also removes volumes
just up
just status  # repeat until every row shows "healthy"
```
Expect all six containers (`backend`, `transformer`, `sink`, `rabbitmq`, `cerbos`, `clickhouse`)
showing `Up ... (healthy)`.

**2. Publish a plain event and confirm delivery**

```bash
curl -s -X POST http://localhost:8080/audit/publish \
  -H "Content-Type: application/json" \
  -d '{"eventType":"user.login","sourceSystem":"smoke-test","tenantId":"demo"}'
```
Expect `Audit event published successfully`. Then:
```bash
just log sink   # Ctrl+C once you see it
```
Expect a `POST /sink/logging_sink HTTP/1.1" 200 OK` log line carrying the event you just sent.

**3. Confirm tenant isolation**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/audit/publish \
  -H "Content-Type: application/json" \
  -d '{"eventType":"user.login","sourceSystem":"smoke-test","tenantId":"does-not-exist"}'
```
Expect `403` — an unprovisioned tenant is rejected at ingest, never silently routed to another
tenant's sink. See [Configuring Pipelines](https://labs64.io/docs/modules/auditflow/configuring-pipelines/) for the tenant model.

**4. Confirm redaction and the ClickHouse round trip**

```bash
curl -s -X POST http://localhost:8080/audit/publish \
  -H "Content-Type: application/json" \
  -d '{"eventType":"payment.succeeded","sourceSystem":"smoke-test","tenantId":"demo",
       "extra":{"userId":"alice","apiKey":"super-secret","durationMs":12}}'

just ch "SELECT user_id, duration_ms, has(extra,'apiKey') AS has_api_key
         FROM audit_events ORDER BY event_time DESC LIMIT 1"
```
Expect `user_id = '***'` (masked), `has_api_key = 0` (dropped before the pipeline ever saw it),
and `duration_ms` intact. This confirms redaction runs in the backend, before the broker, and
deliberately leaves reporting dimensions alone — see the redaction rules in `docker-compose.yml`'s
backend `JAVA_OPTS` and [Extending it for a use case](#extending-it-for-a-use-case).

For the fully asserted round trip (publish → broker → transform → sink → `SELECT`, checked column
by column), run `just notebook-getting-started` and read section 6.

**5. Confirm the DLQ is reachable and empty on a healthy run**

```bash
curl -s http://localhost:8080/actuator/dlq/demo
```
Expect `{"messageCount":0,"tenantId":"demo","status":"available"}` — no messages waiting after the
steps above. To actually exercise a failure — inspect, replay, purge — see
[Dead Letter Queue filling up](https://labs64.io/docs/modules/auditflow/troubleshooting/), which walks through pointing a
pipeline at an unreachable destination and recovering from it.

**6. Tear down**

```bash
just down     # keeps images; `just clean` also removes volumes
```

If all six steps matched their expected result, the core paths — ingest, routing, tenant
isolation, redaction, delivery, and the DLQ safety net — all work.

---

## Testing

### Run All Tests

```bash
just test      # runs all tests: API client + backend + transformer + sink
```

### Backend (Java)

```bash
just test-be
# or directly:
mvn -B verify --file auditflow-be/pom.xml
```

Tests live in `auditflow-be/src/test/java/` using JUnit + Spring Boot Test + Mockito. Key test classes:
- `AuditServiceTest` — router: matching pipelines, idempotency, quarantine, confirmed enqueue
- `PipelineExecutorTest` — transformer chain, sink + fallback, poison/retryable/throttled, batches
- `DeliveryWorkerTest` — delivered/deferred/retried/dead-lettered, retry policy, batch grouping, acks
- `ConfirmingPublisherTest` / `RetryPolicyTest` — publisher confirms, delay tiers and limits
- `ConditionEvaluatorTest` / `ConditionEvaluatorExtensionsTest` — all condition operators, `cidr`/`wildcard`, nested groups, explanations
- `PipelineDryRunTest` / `PipelinesEndpointTest` — dry run, fixture cases, warnings, the admin endpoint
- `TenantFixturesTest` — every repository tenant in `tenants/` against its `tenants/fixtures/<tenant>.yaml`
- `DlqEndpointTest` — DLQ counts, inspection with entries, replay and purge
- `SinkServiceTest` / `TransformationServiceTest` — HTTP calls, circuit breaker, retry
- `DeliveryErrorsTest` — error classification (poison vs retryable)
- `RedisIdempotencyServiceTest` / `InMemoryIdempotencyServiceTest` — dedup stores

### Tenant fixtures

The repository tenants in `tenants/` are checked by `TenantFixturesTest` on every build. Deployments
keep their own fixtures next to their tenant files and check them the same way.

### Python Services

```bash
just test-transformer
just test-sink
# or from each directory:
cd auditflow-transformer && python3 -m pytest -v
cd auditflow-sink        && python3 -m pytest -v
```

Tests use `pytest` + `httpx` (TestClient). They cover plugin registry, endpoint routing, and error handling.

### End-to-End (stack must be running)

```bash
just log sink                  # watch for "Audit Event Logged"
just notebook-getting-started  # section 6 asserts the ClickHouse round trip
```

### Getting-Started Notebook (stack must be running)

`examples/getting-started.ipynb` walks through the core AuditFlow features: health checks,
plugin registries, publishing events, data redaction, and idempotency.

**Prerequisites:** `pip install jupyter requests`

```bash
just up                        # start the stack (default)
just notebook-getting-started  # open the getting-started notebook in your browser
just notebook-load-test        # open the load-testing notebook
# Run all cells with Kernel → Restart & Run All
```

---

## Adding a New Sink

1. Create `auditflow-sink/sinks/my_sink.py`:

```python
def process(event_data: dict, properties: dict) -> dict:
    """Required entry point. Called for every event routed to this sink."""
    # event_data: the audit event JSON
    # properties: pipeline-specific config from sink.properties
    api_key = properties.get("api-key", "")
    # ... send to your destination ...
    return {"sent": True}
```

2. If it needs new Python packages, add them to `auditflow-sink/requirements.txt`.

3. Reference it from a tenant's pipeline (see [Configuring Pipelines](https://labs64.io/docs/modules/auditflow/configuring-pipelines/)):

```yaml
# tenants/_platform.yaml (or tenants/<tenantId>.yaml)
tenantId: _platform
enabled: true
pipelines:
  - name: my-pipeline
    enabled: true
    sink:
      name: my_sink
      properties:
        api-key: "${secretRef:apiKey}"   # resolved from the tenant's own secret store
```

No backend code changes needed — the name is resolved dynamically at runtime.

---

## Adding a New Transformer

1. Create `auditflow-transformer/transformers/my_transformer.py`:

```python
def transform(input_data: dict) -> dict:
    """Required entry point. Receives the event, returns the transformed event."""
    # input_data: the audit event JSON
    # return: the transformed event (must be valid JSON)
    input_data["transformed"] = True
    return input_data
```

2. If it needs new Python packages, add them to `auditflow-transformer/requirements.txt`.

3. Reference it from a tenant's pipeline:

```yaml
# tenants/<tenantId>.yaml
tenantId: acme
enabled: true
pipelines:
  - name: my-pipeline
    enabled: true
    transformer:
      name: my_transformer
    sink:
      name: logging_sink
```

### Multi-stage transformer chains

Pipelines support chaining multiple transformers in sequence:

```yaml
pipelines:
  - name: enriched-pipeline
    enabled: true
    transformers:
      - name: audit_loki
      - name: zero
    sink:
      name: loki_sink
```

---

## Health & Observability

### Actuator Endpoints (Backend)

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Liveness + readiness |
| `GET /actuator/metrics` | All registered metrics |
| `GET /actuator/metrics/auditflow.consumer.events.processed` | Total events processed |
| `GET /actuator/metrics/auditflow.consumer.events.inflight` | Events currently in-flight |
| `GET /actuator/metrics/auditflow.pipeline.outcomes` | Router: per-pipeline ENQUEUED/SKIPPED counts |
| `GET /actuator/metrics/auditflow.delivery.outcomes` | Delivery: delivered/deferred/retried/duplicate per tenant and pipeline |
| `GET /actuator/metrics/auditflow.delivery.deadlettered` | DLQ entries written, per tenant, pipeline and reason |
| `GET /actuator/metrics/auditflow.delivery.latency` | Delivery delay, from the router enqueuing a delivery to its sink accepting it (retries included), per tenant and pipeline; fixed buckets 1 s, 10 s, 1 min, 5 min, 15 min, 1 h, 3 h |
| `GET /actuator/metrics/auditflow.pipeline.duration` | Per-pipeline processing duration |
| `GET /actuator/dlq/{tenantId}[?limit=N&pipeline=<name>]` | Tenant DLQ inspect: total, counts by pipeline and by reason, legacy share; with `limit` (max 100) also the entries with event, reason, attempts, last error and timestamps; non-destructive |
| `GET /actuator/pipelines/{tenantId}` | Deployed pipelines with effective retry/batch settings and configuration warnings |
| `POST /actuator/pipelines/{tenantId}/dry-run` | Pipeline dry run: which pipelines events reach and why; fixture `cases` pass or fail |
| `POST /actuator/dlq/{tenantId}` | Tenant DLQ replay: each entry back to its pipeline as a fresh delivery (attempts and age reset); body `{"pipeline":"<name>"}` limits it to one pipeline |
| `DELETE /actuator/dlq/{tenantId}?pipeline=<name>` | Tenant DLQ purge — **irreversible**; discards entries instead of replaying them, optionally one pipeline only (all three ops touch only that tenant's queue; use `_platform` for tenantless events) |
| `GET /actuator/metrics/auditflow.tenant.events` | Per-tenant lifecycle outcomes (routed/delivered/quarantined/rejected:*) |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |

### Python Service Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /health` | Basic health check (UP/DOWN) |
| `GET /ready` | Readiness probe (Kubernetes) |
| `GET /live` | Liveness probe |
| `GET /info` | Service metadata |
| `GET /registry` | List available sinks/transformers |

### Observability Stack

Start with `just up obs`. The overlay adds five containers to the base stack:

| Component | URL | Credentials | Purpose |
|-----------|-----|-------------|---------|
| Grafana | http://localhost:3000 | admin / admin | Dashboards (pre-provisioned) |
| Prometheus | http://localhost:9090 | — | Metrics query UI |
| Tempo | http://localhost:3200 | — | Trace storage (API only; use Grafana) |
| Loki | http://localhost:3100 | — | Log storage (API only; use Grafana) |
| OTel Collector | localhost:4317 / 4318 | — | OTLP receiver (gRPC / HTTP) |

#### Architecture

```
Backend (Java)              Python services
    │  traces → OTLP/HTTP       │  traces+logs+metrics → OTLP/HTTP
    │  logs   → OTLP/HTTP       │
    │  metrics→ OTLP/HTTP       │
    │  metrics→ /actuator/prometheus (Prometheus scrape)
    └──────────────┬────────────┘
                   ▼
         OTel Collector (:4318 HTTP, :4317 gRPC)
           │   │   │
           │   │   └─► Prometheus exporter (:8889) ◄── Prometheus scrapes
           │   └─────► Loki (:3100)                    (logs)
           └─────────► Tempo (:3200 via gRPC:4317)     (traces)

Prometheus (:9090)
  ├── scrapes backend:8080/actuator/prometheus
  └── scrapes otel-collector:8889  (metrics from Python services)

Grafana (:3000)
  ├── datasource: Prometheus → http://prometheus:9090
  ├── datasource: Loki       → http://loki:3100
  └── datasource: Tempo      → http://tempo:3200
```

#### How signals flow from the Java backend

- **Traces** — `micrometer-tracing-bridge-otel` auto-configures the OTel SDK. Spans are exported via `management.otlp.tracing.endpoint` (OTLP/HTTP → `http://otel-collector:4318/v1/traces`).
- **Logs** — `logback-spring.xml` declares an `OpenTelemetryAppender`. Spring Boot's `spring-boot-opentelemetry` auto-configuration installs it on startup and wires it to the managed `OpenTelemetry` bean. Log records (including `traceId` and `spanId` fields for Grafana trace-to-log correlation) are exported via OTLP/HTTP to the collector, which forwards them to Loki via its native OTLP ingestion endpoint (`/otlp/v1/logs`).
- **Metrics** — exported via two paths:
  1. `micrometer-registry-prometheus` → `/actuator/prometheus` → Prometheus scrapes directly (job `auditflow-backend`).
  2. `micrometer-registry-otlp` → OTLP push to `http://otel-collector:4318/v1/metrics` every 30 s → OTel Collector prometheus exporter (port 8889) → Prometheus.

#### How signals flow from Python services

Python services use the `opentelemetry-sdk` with OTLP exporters configured via env vars in `docker-compose-observability.yml`:
- `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`
- `OTEL_LOGS_EXPORTER=otlp`, `OTEL_METRICS_EXPORTER=otlp`

#### Pre-provisioned Grafana dashboard

The **AuditFlow Overview** dashboard (`observability/grafana/dashboards/auditflow-overview.json`) is auto-loaded. It shows:
- Request rate and 5xx error rate (from Prometheus)
- Recent traces (from Tempo)
- Log stream (from Loki)

Datasource UIDs are pinned (`prometheus`, `loki`, `tempo`) so cross-signal linking works: clicking a trace in Tempo shows the correlated logs in Loki.

#### Verify the stack is working

```bash
# 1. Check OTel Collector received data (metrics exposed on port 8889)
curl -s http://localhost:8889/metrics | grep auditflow | head -5

# 2. Check Prometheus has backend targets UP
curl -s http://localhost:9090/api/v1/targets | python3 -m json.tool | grep '"health"'

# 3. Check Loki received logs
curl -s 'http://localhost:3100/loki/api/v1/query?query={app="auditflow-backend"}' | python3 -m json.tool

# 4. Check Tempo received traces (after sending at least one event)
curl -s http://localhost:3200/api/search | python3 -m json.tool | head -20
```

#### Startup sequence (full observability)

```bash
# 1. Build the Spring Boot JAR and all Docker images, start everything
just up obs           # stack + obs overlay (RabbitMQ + 3 services + 5 obs containers)

# 2. Wait ~60 s for all containers to reach healthy state
docker compose ps    # all rows should show "healthy" or "Up"
```

> **Tip:** `just up obs` already stops any previously-running stack before starting, so you do not need to run `just down` first.

#### Troubleshooting: no data in Grafana / Prometheus

0. **Check the OTel Collector is running first** — all other issues are secondary if the collector is down:
   ```bash
   docker ps --filter name=auditflow-otel-collector --format '{{.Status}}'
   # should show "Up ..." — if it shows "Exited (1)..." the collector crashed on startup
   docker logs auditflow-otel-collector 2>&1 | tail -20
   ```
   If the collector exited with a config parse error, inspect `observability/otel-collector/config.yaml`.
   **Known issue:** the `loki` exporter was removed from `otel-collector-contrib` ≥ 0.114.0. Use
   `otlp_http/loki` with `endpoint: http://loki:3100/otlp` instead (already fixed in this repo).

1. **Prometheus targets not UP** — open http://localhost:9090/targets and look for `backend:8080` and `otel-collector:8889`. If `backend` is down, the Spring Boot app may not have started yet (wait ~30 s and refresh).

2. **No logs in Loki** — the OTel Logback appender is initialized after the Spring context fully starts. Send at least one request to the backend, then query Loki. If still empty, check backend logs for `OpenTelemetryAppender` or OTLP export errors.

3. **No traces in Tempo** — tracing samples 100% in dev (`management.tracing.sampling.probability: 1.0`). Send a request and check Grafana → Explore → Tempo → Search. If empty, verify OTel Collector is healthy: `docker logs auditflow-otel-collector | tail -20`.

4. **Collector pipeline errors** — `docker logs auditflow-otel-collector 2>&1 | grep -i error`

### Circuit Breaker States

The circuit breaker metrics expose state as a gauge: `0` = CLOSED, `1` = OPEN, `2` = HALF_OPEN.

Circuit breaker is configurable via `auditflow.circuitbreaker.*` in `application.yml`.

### Rate Limiting

Per-pipeline rate limiting is available but disabled by default. Enable via:

```yaml
auditflow:
  ratelimit:
    enabled: true
    default-limit-for-period: 1000
    default-limit-refresh-period: PT1S
```

A rate-limited delivery is deferred (back in a few seconds) without spending a retry attempt.

### Message broker: RabbitMQ or Amazon SQS

`auditflow.broker.type` (`AUDITFLOW_BROKER_TYPE`) picks the broker: `rabbitmq` (default) or `sqs`.
The code outside `auditflow-be/.../broker/` only knows the port `MessageBroker`; each broker is an
adapter package with its own consumers (`broker/rabbit/`, `broker/sqs/`). Users' setup (queues, IAM
policy, Helm values, limits): the public docs, [Choose a message broker](https://labs64.io/docs/modules/auditflow/deployment/#choose-a-message-broker).

| | RabbitMQ | SQS |
|---|---|---|
| Settings | `spring.rabbitmq.*`, `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` | `auditflow.broker.sqs.*`: `region`, `queue-prefix`, `create-queues`, `endpoint`, `wait-time`, visibility timeouts; credentials from the AWS SDK default chain |
| Topology | Declared on startup (`RabbitTopology`) | `<prefix>ingest`, `-ingest-dlq`, `-delivery`, `-quarantine`, `-dlq-<tenant>`; missing ones created when `create-queues` |
| Confirm | Publisher confirm + mandatory return | `SendMessageBatch` result per entry |
| Delays | One TTL queue per tier | Per-message `DelaySeconds`; over 15 min a `not-before` header and 15-minute hops |
| Headers | AMQP headers | One JSON message attribute (`x-auditflow-headers`): SQS allows 10 attributes |
| Event size | Gateway limit | 1 MiB per message: ingest rejects an event above 1 MiB minus 16 KiB with 413 |
| DLQ scan | `basic.get` + nack | Receive with a visibility timeout, delete or release |
| Retention | Until consumed | At most 14 days, DLQ entries included |

Try the SQS adapter locally with `just up sqs`: the backend runs against ElasticMQ
(`docker-compose-sqs.yml`, `localhost:9324`) and creates its queues on first use:

```bash
aws --endpoint-url http://localhost:9324 --region eu-west-1 sqs list-queues
```

A new broker is a new adapter: implement `MessageBroker` (publish with a durable confirm, tenant DLQ
creation, a non-destructive scan), feed `IngestConsumer.consume` and `DeliveryWorker.process` from its
consumers, and put both behind `@ConditionalOnProperty(name = "auditflow.broker.type", havingValue = "<type>")`.
### Graceful Shutdown

The backend tracks in-flight events and drains them on shutdown (25s timeout). During shutdown, new events are rejected so in-flight work can complete.

---

## Troubleshooting

### Python service hot-reload not working

When running `just run-local`, Uvicorn uses `--reload`. Ensure you're editing files in the service directory (not inside Docker). The reload watches the current directory.

### Maven build fails with "Java version not supported"

AuditFlow requires Java 25 and Maven 3.6.3+. Check your versions:

```bash
java --version    # must be 25+
mvn --version     # must be 3.6.3+
```

The `maven-enforcer-plugin` will fail the build if these are not met.

---

## Quick Reference

### Service URLs

| URL | Purpose |
|---|---|
| http://localhost:8080/swagger-ui.html | Interactive API — publish events from the browser |
| http://localhost:8080/actuator/health | Backend liveness / readiness |
| http://localhost:8080/actuator/metrics | Metrics (filter with `?name=auditflow.*`) |
| http://localhost:8080/actuator/prometheus | Prometheus scrape endpoint |
| http://localhost:8080/actuator/dlq | DLQ management |
| http://localhost:8081/docs | Transformer FastAPI docs + transformer list |
| http://localhost:8082/docs | Sink FastAPI docs + sink list |
| http://localhost:15673 | RabbitMQ Management UI (guest / guest) |

### Observability URLs (obs stack only)

| URL | Purpose |
|---|---|
| http://localhost:3000 | Grafana dashboards (admin / admin) |
| http://localhost:9090 | Prometheus query UI |
| http://localhost:3100 | Loki API (use Grafana for UI) |
| http://localhost:3200 | Tempo API (use Grafana for UI) |
| http://localhost:8889/metrics | OTel Collector prometheus exporter |

### Useful commands

| Command | Purpose |
|---|---|
| `just up` | Build and start the stack (default) |
| `just up obs` | Stack + observability overlay |
| `just log backend` | Tail backend (Java) logs |
| `just log sink` | Tail sink (Python) logs |
| `just log transformer` | Tail transformer (Python) logs |
| `just log rabbitmq` | Tail RabbitMQ broker logs |
| `just logs` | Tail all service logs |
| `just status` | Show container health |
| `just test` | Run all tests (backend + transformer + sink) |
| `just test-be` | Run Java backend unit tests |
| `just down` | Stop the stack |
| `just clean` | Stop + remove volumes (full reset) |
