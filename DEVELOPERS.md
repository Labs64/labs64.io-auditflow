# AuditFlow — Developer Guide

Everything you need to work with AuditFlow locally, test it, and troubleshoot issues.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Project Layout](#project-layout)
- [Running Locally](#running-locally)
- [Manual Verification (Smoke Test)](#manual-verification-smoke-test) — step-by-step, for testers
- [Testing](#testing)
- [Adding a New Sink](#adding-a-new-sink)
- [Adding a New Transformer](#adding-a-new-transformer)
- [Configuring Pipelines](#configuring-pipelines)
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
        │  publishes to RabbitMQ, answers 200 after the broker confirm
        ▼
    RabbitMQ  labs64-audit-topic
        │
        ▼  router: AuditService.processAuditEvent()
        │  one delivery message per ENABLED pipeline whose condition matches
    RabbitMQ  labs64-audit-delivery ◄── labs64-audit-delay.<5s…3h> (retry / defer)
        │                          ──► labs64-audit-dlq.<tenant>   (exhausted / poison)
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
  [Delivery model](#delivery-model-confirms-retries-backpressure-and-the-dlq).

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
rather than dead-lettering the event, and applying only `schema.sql` stays supported. That
tolerance is also why the pairing needs a test —
`auditflow-transformer/tests/test_audit_clickhouse_netlicensing.py` asserts that every promoted key
has a column, every column has a key, and every key is documented for publishers.

The NetLicensing event model — the field vocabulary, the event templates a publisher such as
NetLicensing or the Payment Gateway emits, and the queries built on them — is
[`examples/clickhouse/NETLICENSING_EVENTS.md`](examples/clickhouse/NETLICENSING_EVENTS.md).

Worth knowing when evaluating ClickHouse here:

- **Aggregate on `event_time`, not `timestamp`.** `timestamp` is when AuditFlow received the event;
  `event_time` is when the business action happened. The table is partitioned, sorted and expired on
  `event_time` for that reason. Group revenue on `timestamp` and a backfill of two years of history
  lands every euro of it in today's bucket.
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
with `input_format_skip_unknown_fields=1`, so a promoted key with no column is **silently dropped at
insert time**, not rejected — that is true of the module path and the config-only path equally;
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

`audit_opensearch` 2.0.0 promotes all 7 well-known keys instead of 3. `sessionId`, `durationMs` and
`responseStatus` now land in top-level `session_id`, `duration_ms` and `response_status` instead of
staying inside the `extra` object — update any query or dashboard reading `extra.sessionId` (etc.).
The index mapping gains **five** fields in total: those same three moved-out fields, plus
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
tenant's sink. See [Configuring Pipelines](#configuring-pipelines) for the tenant model.

**4. Confirm redaction and the ClickHouse round trip**

```bash
curl -s -X POST http://localhost:8080/audit/publish \
  -H "Content-Type: application/json" \
  -d '{"eventType":"payment.succeeded","sourceSystem":"smoke-test","tenantId":"demo",
       "extra":{"userId":"alice","apiKey":"super-secret","sessionId":"sess-1"}}'

just ch "SELECT user_id, session_id, has(extra,'apiKey') AS has_api_key
         FROM audit_events ORDER BY timestamp DESC LIMIT 1"
```
Expect `user_id = '***'` (masked), `has_api_key = 0` (dropped before the pipeline ever saw it),
and `session_id` intact. This confirms redaction runs in the backend, before the broker, and
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
[Dead Letter Queue filling up](#dead-letter-queue-filling-up), which walks through pointing a
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

3. Reference it from a tenant's pipeline (see [Configuring Pipelines](#configuring-pipelines)):

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

### Writing a batch in one call (optional)

A pipeline with `batch.enabled` sends its sink several events per call. A sink that only has `process`
gets them through it, up to 8 at a time. A sink whose destination takes many records per request
should also define `process_batch`:

```python
from auditflow_sdk import RejectedEvent, deliver_each

def process_batch(events: list, properties: dict) -> list:
    """Return ONE outcome per event, in input order: a result dict, or an Exception."""
    try:
        send_all(events, properties)               # one request to the destination
    except DestinationRefusedTheData:              # one event is bad: deliver the others
        return deliver_each(events, properties, process)
    except Exception as e:                         # nothing was stored
        return [e] * len(events)
    return [{"sent": True}] * len(events)
```

| Outcome of an event | What the backend does |
|---|---|
| a dict (or `None`) | delivered |
| an `Exception` that is a `ValueError` (`RejectedEvent` is one) | dead-lettered at once as poison: the destination refused this event's data |
| any other `Exception` | retried with the usual delays |
| `process_batch` itself raises | every event of the call is retried; use it for a missing property, which an operator can fix while the events wait |

Delivery is at-least-once. A batch whose answer is lost is sent again, and its events may then be
grouped with others. Make a repeat harmless where the destination allows it (a deterministic object
name, a key the destination de-duplicates on); otherwise readers de-duplicate by `eventId`.
`auditflow_sdk` has the helpers the shipped sinks use: `deliver_each`, `chunk_indexes` (split by count
and bytes), `batch_object_name` and `jsonl_body`. `GET /registry` shows `batch: true` for a sink with
`process_batch`.

### Available sinks (15)

`logging_sink`, `webhook_sink`, `syslog_sink`, `loki_sink`, `opensearch_sink`, `aws_s3_sink`, `aws_cloudwatch_sink`, `gcs_sink`, `azure_blob_sink`, `netlicensing_sink`, `datadog_sink`, `splunk_sink`, `snowflake_sink`, `clickhouse_sink`, `postgres_sink`

`GET /registry` on the sink service lists every module with its version and documented properties.

#### Table, column and database names

`postgres_sink`, `clickhouse_sink` and `snowflake_sink` put these names into the SQL text, where a
bound parameter is not possible. They come from the tenant file, never from an event, and are checked
before anything is sent: only a plain identifier is accepted (a letter or underscore, then letters,
digits and underscores). A name that needs quoting (a space, a dash, a quote, a keyword in quotes)
is refused with `Invalid table name '...'`; rename it or point the sink at a view.

| Sink | Property | Accepted |
|---|---|---|
| `postgres_sink` | `table` | `table` or `schema.table`, at most 63 characters per part |
| `clickhouse_sink` | `database`, `table` | one identifier each |
| `snowflake_sink` | `table` | `TABLE`, `SCHEMA.TABLE` or `DATABASE.SCHEMA.TABLE`; `$` allowed after the first character |
| `snowflake_sink` | `column` | one identifier; `$` allowed after the first character |

A refused name fails the delivery as retryable, like a missing property: the events wait in the retry
queues and go through once the tenant file is fixed. A sink of your own can use the same check,
`auditflow_sdk.sql_identifier`.

#### `clickhouse_sink`

Inserts over the ClickHouse HTTP interface. It expects a row whose keys are column names, so pair it
with the `audit_clickhouse` transformer; with `zero` every delivery fails. Turn on `batch.enabled` for
this sink: a batch is ONE `INSERT ... FORMAT JSONEachRow`, which is what MergeTree wants. Without it
every event is its own insert and the sink relies on server-side `async_insert` to merge them.
Properties, schema and tuning are in [ClickHouse (analytics sink)](#clickhouse-analytics-sink).

#### `postgres_sink`

Inserts the event as JSON into the `event_data` column of a table you create:

```sql
CREATE TABLE audit_events (id BIGSERIAL PRIMARY KEY, received_at TIMESTAMPTZ NOT NULL DEFAULT now(), event_data JSONB NOT NULL);
-- optional: a redelivered event becomes a no-op instead of a second row
CREATE UNIQUE INDEX audit_events_event_id ON audit_events ((event_data->>'eventId'));
```

```yaml
batch: {enabled: true, maxSize: 100}      # one multi-row INSERT per batch
sink:
  name: postgres_sink
  properties:
    host: postgres.example.internal
    port: "5432"                          # default 5432
    database: audit
    user: auditflow
    password: "${secretRef:postgresPassword}"
    table: audit_events                   # or schema.table
    connect-timeout: "10"                 # seconds, default 10
```

- **Table name.** A plain identifier, optionally `schema.table`
  (see [Table, column and database names](#table-column-and-database-names)). Anything else is refused
  before a connection is opened. The event is always a bound parameter, never part of the SQL text.
- **Connections are reused.** Up to 4 idle connections are kept per host, port, database, user and
  password in each sink process. A connection the server closed while it was idle is replaced and the
  insert repeated once.
- **Batch.** One `INSERT` with a row per event, in one transaction. If the database refuses one event's
  data (for example a `\u0000` in a string, which `jsonb` cannot store), the events are inserted one
  by one: the others are stored and the refused one is dead-lettered as poison. A constraint violation
  is handled the same way, but that event stays retryable.
- **Repeats.** The insert uses `ON CONFLICT DO NOTHING`. With the unique index above a redelivered
  event is skipped; without it a repeat adds a row.

Only `event_data` is written; the other columns must have defaults. The table is the system of record
here, AuditFlow itself still stores nothing.

On Kubernetes with the chart's NetworkPolicy enabled, both need a `networkPolicy.extraEgress` rule for
the destination port (ClickHouse 8123 or 8443, PostgreSQL 5432); only 443 is open by default.

#### `aws_s3_sink` object keys

One object per event: `<prefix>[tenant=<tenantId>/]<partition-format>/<yyyymmdd-HHMMSS>-<eventId>.json[.gz]`.
`partition-format` is a `strftime` pattern (default `year=%Y/month=%m/day=%d/`) and may also contain
`{field}` placeholders, a dotted path into the event, so event fields can become folders:

```yaml
prefix: tenants/
partition-format: "vendor_number={extra.vendorNumber}/year=%Y/month=%m/day=%d/eventType={eventType}/actionName={extra.actionName}/"
# -> tenants/tenant=acme/vendor_number=V1/year=2026/month=10/day=02/eventType=api.call/actionName=product_create/<ts>-<id>.json
```

Values are sanitized to `[A-Za-z0-9._-]` (every other character, such as `/` in `product/create`, becomes
`_`) and cut at 128 characters; a missing or null value becomes `unknown`. An `extra.<key>` placeholder
falls back to the top-level key, where a promoting transformer moves well-known keys. The format is applied
only when `partition-by-date` is `true`, and a mistyped placeholder is left in the key as literal text.

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

### Available transformers (4)

`zero` (pass-through), `audit_loki`, `audit_opensearch`, `audit_clickhouse`

Plus `audit_clickhouse_netlicensing`, mounted from `examples/netlicensing/` rather than shipped —
see [Extending it for a use case](#extending-it-for-a-use-case).

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

## Configuring Pipelines

Pipelines are configuration owned **per tenant** (silo model): every event routes only through the
pipeline set of its own tenant, and tenantless events belong to the reserved `_platform`
pseudo-tenant. The legacy global `auditflow.pipelines` list **fails startup by design** — move any
remaining global pipelines into `tenants/_platform.yaml`.

Tenant configs come from a pluggable source (`tenants.source.mode`):

| Mode | When | How |
|------|------|-----|
| `local-dir` (default) | Local/compose/bare-metal | `<tenantId>.yaml` files in `tenants.source.local-dir.path` (default `/config/tenants`; compose mounts `./tenants`), polled every 5s — drop a file to onboard live |
| `gitops-configmap` | Kubernetes (set by the Helm chart) | ConfigMaps labelled `auditflow.io/tenant`, watched via the Kubernetes API |

### Tenant file structure

```yaml
# tenants/<tenantId>.yaml — one file per tenant
tenantId: acme                    # required; ^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$
enabled: true                     # false = tenant disabled, ingest returns 403 TENANT_DISABLED
quota:                            # optional — per-tenant ingest rate limit (token bucket)
  rateLimitPerSec: 200            # over budget = 429 TENANT_RATE_LIMITED + Retry-After
  burst: 400
pipelines:
  - name: my-pipeline             # required, unique within the tenant
    enabled: true                 # enable/disable at runtime
    condition:                    # optional — omit to match every event
      match: all                  # "all" (AND) or "any" (OR)
      rules:
        - field: eventType        # dot notation supported: extra.userId
          operator: eq            # eq, neq, contains, startsWith, endsWith, in, notIn, exists,
                                  # notExists, regex, gt, gte, lt, lte, eqIgnoreCase, cidr,
                                  # notCidr, wildcard, notWildcard (see "Condition operators")
          value: "api.call"
    transformer:
      name: zero                  # optional — omit to pass through unchanged
    sink:
      name: logging_sink          # required
      properties:                 # optional, passed to the sink's process() function
        log-level: INFO
        api-key: "${secretRef:apiKey}"  # resolved from THIS tenant's secret store at delivery
      fallback:                   # optional — used when primary sink fails with retryable error
        name: webhook_sink
        properties:
          url: "https://hooks.example.com"
```

Sink credentials use `${secretRef:<key>}` indirection (`secretRef.resolver`): `env` (default) reads
`AUDITFLOW_TENANT_<TENANTID>_<KEY>`; `k8s-secret` (Helm) reads the tenant's own Secret
`auditflow-tenant-<tenantId>-creds`. A missing key fails that delivery as retryable (→ DLQ) —
never a blank, never another tenant's credential.

### Condition operators

| Operator | Description | Example |
|----------|-------------|---------|
| `eq`, `neq` | Equals / not equals | `field: eventType, value: api.call` |
| `eqIgnoreCase` | Case-insensitive equals | `field: eventType, value: API.CALL` |
| `contains` | String contains | `field: extra.message, value: error` |
| `startsWith`, `endsWith` | Prefix / suffix match | `field: sourceSystem, value: auth` |
| `in`, `notIn` | Value in comma-separated list | `value: security.alert,auth.failed` |
| `exists`, `notExists` | Field exists / doesn't exist | `field: extra.userId` |
| `regex` | Regular expression | `field: extra.email, value: .*@.*` |
| `gt`, `gte`, `lt`, `lte` | Numeric comparisons | `field: extra.statusCode, value: 400` |
| `cidr`, `notCidr` | IP address in one of the comma-separated networks (IPv4 and IPv6; a bare address is one host). Literals only, never resolved; an unparseable address matches neither | `field: extra.ip, value: "10.0.0.0/8, 2001:db8::/32"` |
| `wildcard`, `notWildcard` | Glob, `*` any run, `?` one character, comma-separated alternatives; case-sensitive, linear time | `field: extra.actionName, value: "admin/*, licensee/delete"` |

Field paths support dot notation (`extra.userId`) and array indices (`items[0].name`).

A rule with `match` and `rules` instead of `field`/`operator` is a **nested group**, combined with
its siblings like any rule (up to 8 levels; deeper groups never match):

```yaml
condition:
  match: all                 # eventType = api.call AND (status >= 400 OR actionStatus = FAILURE)
  rules:
    - {field: eventType, operator: eq, value: api.call}
    - match: any
      rules:
        - {field: extra.responseStatus, operator: gte, value: "400"}
        - {field: extra.actionStatus, operator: eq, value: FAILURE}
```

An unknown operator never matches; the dry run reports it as a warning.

### Pipeline dry run and tenant fixtures

Ask the running backend which of a tenant's pipelines an event would reach, without publishing
anything (internal admin surface, like the DLQ endpoint; port-forward in Kubernetes):

```bash
# deployed pipelines, effective retry/batch settings, configuration warnings
curl -s localhost:8080/actuator/pipelines/<tenantId> | python3 -m json.tool

# sample events: per pipeline matched or not and why; "transform": true also returns transformer output
curl -s -X POST localhost:8080/actuator/pipelines/<tenantId>/dry-run -H 'Content-Type: application/json' \
     -d '{"events": [{"eventType": "api.call", "extra": {"responseStatus": 500}}], "transform": true}'
```

**Fixtures** pin a tenant's routing: a list of cases, each an event plus the exact set of pipelines it
must reach. Send them as `"cases"` (optionally with `"tenant": {...}` to test a tenant document that
is not deployed yet); the answer is `status: failed` with a reason per failing case.

```yaml
# tenants/fixtures/<tenantId>.yaml
cases:
  - name: a failed call is archived and logged
    event: {eventType: api.call, extra: {responseStatus: 500}}
    expect: [archive, failed-calls]
```

The repository tenants in `tenants/` are checked by `TenantFixturesTest` on every build. Deployments
keep their own fixtures next to their tenant files and check them the same way.

### Tamper-evidence for the S3 archive

`aws_s3_sink` sends an S3-verified SHA-256 checksum with every object (`checksum: none` turns it off
for stores without checksum support). With `digest: "true"` it also writes, per object, a signed
digest record under `<prefix>tenant=<id>/_digests/chain=<id>/<sequence>.json`: object key, SHA-256,
size, event ids, the previous record's hash and an Ed25519 signature. Chains are per tenant and per
sink process; the sink needs no read access.

```bash
python3 auditflow-sink/scripts/verify_s3_digests.py --generate-key     # once: seed + public key
```

Store the seed as the tenant's secret (`k8s-secret` resolver: key `digestSigningKey` in
`auditflow-tenant-<tenantId>-creds`) and reference it, never as a literal:

```yaml
sink:
  name: aws_s3_sink
  properties: {bucket: ..., prefix: tenants/, digest: "true", digest-signing-key: "${secretRef:digestSigningKey}"}
```

An auditor verifies with the public key (needs `s3:ListBucket` and `s3:GetObject` on the prefix):

```bash
python3 auditflow-sink/scripts/verify_s3_digests.py --bucket <bucket> --prefix tenants/tenant=<id> \
        --public-key <base64> [--unattested]
```

It checks every signature, sequence and previous-record link, and every attested object's SHA-256,
and with `--unattested` also fails on objects no record covers. Not covered by the chain alone:
removing the newest records of a chain; enable S3 Object Lock on the bucket for that. Use it with `batch.enabled`: a digest record per
object doubles the PUTs of one-object-per-event pipelines.

### Local compose

`docker-compose.yml` mounts the repo's `tenants/` directory read-only at `/config/tenants`;
`tenants/_platform.yaml` carries the local platform pipelines. Edit or add tenant files while the
stack is running — the local-dir poller applies changes within ~5 seconds, no restart.

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

### Delivery model: confirms, retries, backpressure and the DLQ

Two stages on RabbitMQ, both in the backend:

1. **Ingest and route.** `POST /audit/publish` (or `/audit/publish/batch`) redacts the event and
   publishes it to `labs64-audit-topic`, then waits for the broker's **publisher confirm**: 200 means
   the broker stored it; no confirm within `auditflow.broker.confirm-timeout` (5 s) is a 503 the
   client retries with the same `eventId`. The router consumes the ingest queue and publishes **one
   delivery message per matching pipeline** to `labs64-audit-delivery`, again confirmed, before it
   acks the event.
2. **Deliver.** The delivery worker consumes `labs64-audit-delivery` in batches and settles every
   message one way before acking it:

| Outcome | When | What happens |
|---|---|---|
| delivered | transformer and sink succeeded | the pipeline is marked done for the event (a duplicate is skipped) |
| deferred | pipeline rate limit, tenant in-flight cap, full bulkhead | parked in the 5 s tier, **no attempt spent** |
| retried | retryable failure (5xx, timeout, open circuit, missing secretRef) | parked in the next delay tier: 5 s, 30 s, 2 min, 10 min, 30 min, 1 h, then 3 h |
| dead-lettered | poison (4xx, malformed transformer output), `maxAttempts` used up, `maxAge` passed, pipeline removed | one entry in `labs64-audit-dlq.<tenant>` with reason, attempts and last error |
| quarantined | unparseable event, or the tenant was removed or disabled after ingest | kept in `labs64-audit-quarantine.quarantine` with an `x-quarantine-reason` header; never delivered to a sink, not replayed by the DLQ endpoint |

Delays are queues with a fixed TTL that dead-letter back to the delivery exchange (`labs64-audit-delay.<seconds>s`),
so no broker plugin is needed and it works the same on Amazon MQ. Per pipeline, in the tenant file:

```yaml
pipelines:
  - name: archive
    retry:
      maxAttempts: 20     # default auditflow.delivery.retry.max-attempts
      maxAge: 24h         # default auditflow.delivery.retry.max-age; also PT24H, 30m, 2d
    batch:
      enabled: true       # off by default
      maxSize: 100        # upper bound of events per sink call, 1-1000
```

**Sink batching.** With `batch.enabled` the worker groups the deliveries it received for a pipeline
of a tenant and calls the sink's `POST /sink/<id>/batch` once per group. The sink answers per event,
and each event is settled on its own (delivered, retried, deferred or dead-lettered), so a refused
event never fails the others.

- **`batch.enabled`** is the switch; `batch.maxSize` alone does nothing. `maxSize` is 1 to 1000
  (default 100); a value outside that range rejects the tenant file.
- **The size that is reached.** A group is cut from ONE batch a consumer received, so it never holds
  more than `auditflow.delivery.batch-size` (50) events, and fewer when that batch mixes pipelines or
  tenants. `GET /actuator/pipelines/<tenantId>` shows it as `batch.effectiveMaxSize`. For larger sink
  batches raise `AUDITFLOW_DELIVERY_BATCH_SIZE` together with `maxSize`.
- **When a batch closes.** When it is full, or after `auditflow.delivery.batch-receive-timeout` (1 s)
  without new messages. Under light load batches are small and add up to 1 s of latency.
- **Never across tenants.** Two tenants with a pipeline of the same name get separate calls.
- **One slot, one call.** A batch takes one slot of the tenant's in-flight cap and is one call for
  the bulkhead. The call has the same 10 s response timeout as a single event.
- **Retries.** Only the failed events of a batch are retried, each on its own schedule. They may come
  back grouped with other events, so a sink cannot count on seeing the same group twice.
- **Fallback sink.** The events that failed with a retryable error go to the fallback in one call.

What a sink does with a batch:

| Sink | One batch becomes | A repeat after a lost answer |
|---|---|---|
| `aws_s3_sink`, `gcs_sink`, `azure_blob_sink` | one JSON Lines object per partition folder | the same group overwrites its object; a different grouping can repeat an event in a second object |
| `clickhouse_sink` | one `INSERT ... FORMAT JSONEachRow` | merged away by the `ReplacingMergeTree` key of the example schema |
| `postgres_sink` | one multi-row `INSERT` in one transaction | skipped with a unique index on `eventId`, otherwise a second row |
| `snowflake_sink` | one connection and one multi-row `INSERT` | a second row |
| `opensearch_sink` | one `_bulk` request, an outcome per document | indexed again (ids are generated) |
| `aws_cloudwatch_sink` | one `PutLogEvents` call per 10,000 events or 1 MB; the group and stream are checked once | stored again |
| `datadog_sink` | one request per 1000 entries or 5 MB | stored again |
| `splunk_sink` | one HEC request | indexed again |
| `loki_sink` | one push, equal label sets merged and ordered by time | dropped by Loki when stream, time and line are equal |
| `logging_sink`, `webhook_sink`, `syslog_sink`, `netlicensing_sink` | the events through `process`, up to 8 at a time | as for single events |

For every destination that stores a repeat, readers de-duplicate by `eventId`. Batching pays off most
for ClickHouse and Snowflake (one insert instead of one per event), then for the object stores (one
object instead of thousands of small ones). For a sink in the last row it only saves calls between
the backend and the sink service; keep `maxSize` small there, so that the events fit into the 10 s
call (`maxSize` x the destination's latency / 8).

**Throughput.** An event mostly waits on the transformer and sink, so concurrency, not CPU, sets the
rate (CPU-based autoscaling does not react to a backlog).

| Setting | Default | Purpose |
|---|---|---|
| `AUDITFLOW_DELIVERY_CONCURRENCY` (env) / `auditflow.delivery.concurrency` | `4` | Delivery consumers per pod |
| `AUDITFLOW_DELIVERY_BATCH_SIZE` (env) / `auditflow.delivery.batch-size` | `50` | Messages a consumer takes at once (also its prefetch) |
| `auditflow.delivery.unit-concurrency` | `16` | Deliveries of one received batch run in parallel |
| `auditflow.circuitbreaker.bulkhead-max-concurrent-calls` | `128` | Concurrent calls per transformer/sink target; keep >= concurrency x unit-concurrency |
| `tenants.consumer.max-in-flight-per-tenant` | `32` | One tenant's deliveries at once per pod (fairness); over it, a delivery waits up to `max-wait-millis` (2 s), then is deferred |
| `AUDITFLOW_CONSUMER_CONCURRENCY` (env) | `8` | Router threads on the ingest queue (light work) |
| `AUDITFLOW_BROKER_QUEUE_TYPE` (env) / `auditflow.broker.queue-type` | `classic` | `quorum` for replicated queues on a multi-node broker |

A local run (one pod, single-process transformer and sink) delivered 3,000 events, half of them
through a batching pipeline, in about 6 s end to end. Fairness between tenants comes first from the
ingest quota (`rateLimitPerSec`/`burst`), then from the in-flight cap.

**Metrics:** `auditflow.delivery.outcomes{tenant,pipeline,outcome}` (delivered, deferred, retried,
duplicate), `auditflow.delivery.deadlettered{tenant,pipeline,reason}`, `auditflow.delivery.batch.size`,
`auditflow.pipeline.duration{pipeline,outcome,mode}`. Alert on `deadlettered` > 0 and on the depth of
`labs64-audit-dlq.*` queues.

### Graceful Shutdown

The backend tracks in-flight events and drains them on shutdown (25s timeout). During shutdown, new events are rejected so in-flight work can complete.

---

## Troubleshooting

### Backend won't start

**Symptom:** `APPLICATION FAILED TO START` in logs.

1. **Check RabbitMQ is reachable:**
   ```bash
   docker logs auditflow-rabbitmq | tail -5
   curl -s http://localhost:15673/api/overview | head
   ```

2. **Check credentials:** `RABBITMQ_USERNAME` and `RABBITMQ_PASSWORD` must be set (no defaults by design).

3. **Check port conflicts:**
   ```bash
   lsof -i :8080 -i :8081 -i :8082 -i :5673 -i :15673
   ```

### Sink returns 422 Unprocessable Content

**Symptom:** Backend logs show `422 Unprocessable Content from POST http://sink:8082/sink/{name}`.

This usually means the request body format doesn't match what the sink expects. The backend sends:
```json
{"event_data": {...}, "properties": {...}}
```
The sink's `/sink/{name}` endpoint must accept this structure as a single JSON body.

### Sink returns 500 Internal Server Error

**Symptom:** Sink logs show `An unexpected error occurred in sink endpoint`.

Check the full traceback in sink logs:
```bash
just log sink
```

Common causes:
- Missing Python package in `requirements.txt`
- Sink module has a bug in its `process()` function
- External service (e.g., S3, Loki) is unreachable

### Transformer returns 500

Same approach — check `just log transformer` for the full traceback.

### Events not being consumed

**Symptom:** Events published but sink logs show nothing.

1. Check the consumer is subscribed:
   ```bash
   just log backend | grep "subscriber"
   ```

2. Check RabbitMQ queue depth:
   - Open http://localhost:15673
   - Go to Queues → `labs64-audit-topic.labs64.io-auditflow`
   - If Ready count keeps growing, the consumer is failing

3. Check for circuit breaker open state:
   ```bash
   curl -s http://localhost:8080/actuator/metrics | grep circuitbreaker
   ```

### Dead Letter Queue filling up

A delivery lands in its tenant's DLQ (`labs64-audit-dlq.<tenant>`) when it is poison, used up its
pipeline's `retry.maxAttempts`, passed `retry.maxAge`, or its pipeline was removed. Each entry is one
pipeline of one event, with `x-auditflow-dlq-reason` and `x-auditflow-last-error` headers. Entries
from before per-pipeline delivery, and events whose routing failed, sit in the legacy shared queue
`labs64-audit-topic.labs64.io-auditflow.dlq`; the same endpoint covers both. Every operation requires
a `{tenantId}` selector (use `_platform` for tenantless events); there is no un-scoped
`/actuator/dlq` path. Check the DLQ for a tenant (`byReason` tells poison from outages):

```bash
curl -s http://localhost:8080/actuator/dlq/<tenantId> | python3 -m json.tool
```

Once the cause is fixed, replay that tenant's entries (or one pipeline's):
```bash
curl -X POST http://localhost:8080/actuator/dlq/<tenantId> | python3 -m json.tool
curl -X POST http://localhost:8080/actuator/dlq/<tenantId> -H 'Content-Type: application/json' \
     -d '{"pipeline":"archive"}' | python3 -m json.tool
```

Purge (discard, don't replay) that tenant's entries, or one pipeline's — **irreversible**; every
discarded entry is logged with its event id:
```bash
curl -X DELETE http://localhost:8080/actuator/dlq/<tenantId> | python3 -m json.tool
curl -X DELETE "http://localhost:8080/actuator/dlq/<tenantId>?pipeline=archive" | python3 -m json.tool
```

### Container healthcheck failing

The healthchecks use `wget` to hit `/health` on each service. If a container shows unhealthy:

1. Check if the process is running:
   ```bash
   docker exec auditflow-transformer ps aux
   ```

2. Test the health endpoint from inside the container:
   ```bash
   docker exec auditflow-transformer wget -qO- http://127.0.0.1:8081/health
   ```

3. Check for import errors (e.g., missing `health.py` in the image):
   ```bash
   docker exec auditflow-transformer ls /home/l64user/health.py
   ```

### Python service hot-reload not working

When running `just run-local`, Uvicorn uses `--reload`. Ensure you're editing files in the service directory (not inside Docker). The reload watches the current directory.

### Maven build fails with "Java version not supported"

AuditFlow requires Java 25 and Maven 3.6.3+. Check your versions:

```bash
java --version    # must be 25+
mvn --version     # must be 3.6.3+
```

The `maven-enforcer-plugin` will fail the build if these are not met.

### Idempotency: events being dropped unexpectedly

The idempotency guard uses `eventId` as the dedup key. If you're sending events without an `eventId`, they won't be deduplicated — but they also won't be protected against redelivery duplicates.

Check the current claim TTL (default 5 minutes):
```bash
curl -s http://localhost:8080/actuator/metrics | grep deduplicated
```

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
