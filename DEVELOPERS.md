# AuditFlow — Developer Guide

Everything you need to work with AuditFlow locally, test it, and troubleshoot issues.

---

## Table of Contents

- [Architecture Overview](#architecture-overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Project Layout](#project-layout)
- [Running Locally](#running-locally)
- [Testing](#testing)
- [Adding a New Sink](#adding-a-new-sink)
- [Adding a New Transformer](#adding-a-new-transformer)
- [Configuring Pipelines](#configuring-pipelines)
- [Health & Observability](#health--observability)
  - [Observability Stack (OTel / Grafana / Prometheus / Loki / Tempo)](#observability-stack)
- [Troubleshooting](#troubleshooting)
- [Manual Verification Plan](#manual-verification-plan)

---

## Architecture Overview

AuditFlow is a microservices-based audit-logging pipeline:

```
POST /api/v1/audit/publish
        │
        ▼
    Backend (Java / Spring Boot :8080)
        │  publishes to RabbitMQ topic
        ▼
    RabbitMQ  labs64-audit-topic
        │
        ▼  consumer (same backend, different thread)
    AuditService.processAuditEvent()
        │  for each ENABLED pipeline whose condition matches:
        ├─► Transformer (Python / FastAPI :8081)  POST /transform/{name}
        └─► Sink        (Python / FastAPI :8082)  POST /sink/{name}
```

Three independently deployable services:

| Service | Stack | Port | Role |
|---------|-------|------|------|
| `auditflow-be/` | Java 25, Spring Boot 4, Maven | 8080 | REST API, broker publish/consume, pipeline orchestration |
| `auditflow-transformer/` | Python 3.13, FastAPI, Uvicorn | 8081 | Dynamically-loaded transform modules |
| `auditflow-sink/` | Python 3.13, FastAPI, Uvicorn | 8082 | Dynamically-loaded sink/delivery modules |

Key design decisions:
- **Pipelines are configuration, not code.** Define them in `application.yml` or via `JAVA_OPTS` environment variables.
- **Sink/transformer resolution is dynamic.** A request to `/sink/{name}` does `importlib.import_module(name)` — drop a `.py` file in `sinks/` and it becomes available immediately.
- **Idempotency/dedup** prevents duplicate event processing. Default store is Redis; the lite stack uses in-memory.
- **Circuit breakers + retry** guard all outbound HTTP calls to transformer/sink services.

---

## Prerequisites

| Requirement | Check command | Minimum version |
|---|---|---|
| Java (Temurin) | `java --version` | 25 |
| Maven | `mvn --version` | 3.6.3+ |
| Python | `python3 --version` | 3.13 |
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

```bash
# 1. Build and start the full stack
just up

# 2. Send a test event
just e2e

# 3. Check it arrived in the sink
just log sink
# Ctrl+C to stop tailing

# 4. When done
just down
```

That's it. The `e2e` recipe publishes an event through the `zero` (pass-through) transformer to the `logging_sink`. You should see an "Audit Event Logged" entry in the sink output.

---

## Project Layout

```
labs64.io-auditflow/
├── auditflow-be/            # Java backend (Spring Boot)
│   ├── src/main/java/       # Service code
│   ├── src/test/java/       # JUnit tests
│   └── src/main/resources/
│       ├── application.yml  # Main configuration
│       └── openapi/         # OpenAPI spec (source of truth for API contract)
├── auditflow-transformer/   # Python transformer service
│   ├── transformer.py       # FastAPI app
│   ├── transformers/        # Built-in transformers (zero, audit_loki, audit_opensearch)
│   ├── transformers_bootstrap/  # Mounted at runtime for custom transformers
│   └── tests/
├── auditflow-sink/          # Python sink service
│   ├── sink.py              # FastAPI app
│   ├── sinks/               # Built-in sinks (13 available)
│   ├── sinks_bootstrap/     # Mounted at runtime for custom sinks
│   └── tests/
├── docker-compose.yml              # Full stack (3 services + RabbitMQ + Redis)
├── docker-compose-lite.yml         # Lite stack (3 services + RabbitMQ, in-memory dedup)
├── docker-compose-observability.yml # Observability overlay (OTel Collector + Tempo + Loki + Prometheus + Grafana)
├── docker-compose-verify.yml       # Verification overlay (2 test pipelines + redaction)
├── docker-compose-infra.yml        # RabbitMQ + Redis only (for host-based dev)
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

### Full Stack (Docker)

Includes everything: all 3 services + RabbitMQ + Redis.

```bash
just up        # build JAR + Docker images, start everything
just logs      # tail all service logs (Ctrl+C to stop)
just down      # stop containers (keeps images)
just clean     # stop + remove volumes (full reset)
```

### Lite Stack (Docker, faster)

No Redis. Uses in-memory idempotency store. Faster startup, fewer containers.

```bash
just up-lite   # build and start the lite stack
just down      # same stop command works for both
```

### Observability Stack

The observability overlay adds OTel Collector, Tempo (traces), Loki (logs), Prometheus (metrics), and Grafana (dashboards) to any base stack. See [Health & Observability](#health--observability) for full details.

```bash
just obs-up        # full stack + observability overlay
just obs-up-lite   # lite stack + observability overlay (fastest)
just obs-down      # stop observability containers only
just open-obs      # open Grafana, Prometheus, and RabbitMQ in your browser
```

### Host-Based Development

Run infrastructure in Docker, services directly on your machine for faster iteration:

```bash
# 1. Start only RabbitMQ + Redis
just infra-up

# 2. In separate terminals, run each service with hot-reload:
cd auditflow-transformer && just run-local   # http://localhost:8081
cd auditflow-sink        && just run-local   # http://localhost:8082

# 3. Run the backend with Maven:
cd auditflow-be
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.rabbitmq.host=localhost -Dspring.rabbitmq.port=5673"
```

### Verification Stack

Extends the lite stack with two test pipelines and PII redaction for manual verification:

```bash
just verify    # start the verification stack
# Follow the [Manual Verification Plan](#manual-verification-plan) below (TC-1 through TC-4)
```

---

## Testing

### Run All Tests

```bash
just test      # runs backend + transformer + sink tests
```

### Backend (Java)

```bash
just test-be
# or directly:
mvn -B verify --file auditflow-be/pom.xml
```

Tests live in `auditflow-be/src/test/java/` using JUnit + Spring Boot Test + Mockito. Key test classes:
- `AuditServiceTest` — pipeline orchestration, idempotency, error classification
- `ConditionEvaluatorTest` — all condition operators (eq, regex, in, exists, etc.)
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
just e2e       # publishes a test event, check sink logs for confirmation
just log sink  # watch for "Audit Event Logged"
```

### Regression Notebook (stack must be running)

`tests/regression.ipynb` automates TC-0 preflight through TC-4 idempotency with pass/fail
assertions and a summary results table.

**Prerequisites:** `pip install jupyter requests`

```bash
just verify    # start the verification stack
just notebook  # open the notebook in your browser
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

3. Reference it from a pipeline:

```yaml
# application.yml
auditflow:
  pipelines:
    - name: my-pipeline
      enabled: true
      sink:
        name: my_sink
        properties:
          api-key: "${MY_API_KEY}"
```

No backend code changes needed — the name is resolved dynamically at runtime.

### Available sinks (13)

`logging_sink`, `webhook_sink`, `syslog_sink`, `loki_sink`, `opensearch_sink`, `aws_s3_sink`, `aws_cloudwatch_sink`, `gcs_sink`, `azure_blob_sink`, `netlicensing_sink`, `datadog_sink`, `splunk_sink`, `snowflake_sink`

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

3. Reference it from a pipeline:

```yaml
auditflow:
  pipelines:
    - name: my-pipeline
      enabled: true
      transformer:
        name: my_transformer
      sink:
        name: logging_sink
```

### Available transformers (3)

`zero` (pass-through), `audit_loki`, `audit_opensearch`

### Multi-stage transformer chains

Pipelines support chaining multiple transformers in sequence:

```yaml
auditflow:
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

Pipelines are defined under `auditflow.pipelines` in `application.yml` or via `JAVA_OPTS` environment variables.

### Pipeline structure

```yaml
auditflow:
  pipelines:
    - name: my-pipeline           # required, unique
      enabled: true               # enable/disable at runtime
      condition:                  # optional — omit to match every event
        match: all                # "all" (AND) or "any" (OR)
        rules:
          - field: eventType      # dot notation supported: extra.userId
            operator: eq          # eq, neq, contains, startsWith, endsWith, in, notIn,
                                  # exists, notExists, regex, gt, gte, lt, lte, eqIgnoreCase
            value: "api.call"
      transformer:
        name: zero                # optional — omit to pass through unchanged
      sink:
        name: logging_sink        # required
        properties:               # optional, passed to the sink's process() function
          log-level: INFO
        fallback:                 # optional — used when primary sink fails with retryable error
          name: webhook_sink
          properties:
            url: "https://hooks.example.com"
```

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

Field paths support dot notation (`extra.userId`) and array indices (`items[0].name`).

### Configuring via JAVA_OPTS

In `docker-compose.yml`, pipelines are passed as JVM system properties:

```yaml
environment:
  JAVA_OPTS: >-
    -Dauditflow.pipelines[0].name=logs
    -Dauditflow.pipelines[0].enabled=true
    -Dauditflow.pipelines[0].sink.name=logging_sink
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
| `GET /actuator/metrics/auditflow.pipeline.outcomes` | Per-pipeline SUCCESS/POISON/RETRYABLE counts |
| `GET /actuator/metrics/auditflow.pipeline.duration` | Per-pipeline processing duration |
| `GET /actuator/dlq` | Dead Letter Queue info (GET) and retry (POST) |
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

Start with `just obs-up` or `just obs-up-lite`. The overlay adds five containers to the base stack:

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
just obs-up          # full stack + obs overlay (RabbitMQ + Redis + 3 services + 5 obs containers)
# or:
just obs-up-lite     # lite stack (no Redis, in-memory dedup) + obs overlay — fastest for local iteration

# 2. Wait ~60 s for all containers to reach healthy state
docker compose ps    # all rows should show "healthy" or "Up"

# 3. Send a test event to trigger traces/logs/metrics
just e2e

# 4. Open observability UIs
just open-obs        # opens Grafana :3000 and Prometheus :9090 in the browser
```

> **Tip:** `just obs-up` already stops any previously-running stack before starting, so you do not need to run `just down` first.

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

Rate-limited events are treated as retryable failures and will be redelivered by the broker.

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

Events land in the DLQ when they exhaust all retries. Check the DLQ:

```bash
curl -s http://localhost:8080/actuator/dlq | python3 -m json.tool
```

Retry all DLQ messages:
```bash
curl -X POST http://localhost:8080/actuator/dlq | python3 -m json.tool
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

## Manual Verification Plan

Step-by-step instructions to manually verify core AuditFlow functionality on the lite local stack. Covers four test cases run from a single verification stack.

### Preconditions

| Requirement | Check command | Expected |
|---|---|---|
| Docker Engine 24+ | `docker --version` | `Docker version 24+` |
| Docker Compose v2 | `docker compose version` | `v2.x` |
| `just` task runner | `just --version` | any |
| `curl` | `curl --version` | any |
| `jq` (optional, pretty-print) | `jq --version` | any |
| Ports free: 5673, 8080, 8081, 8082, 15673 | `lsof -i :8080 -i :8081 -i :8082 -i :5673 -i :15673` | no output |

### Starting the Verification Stack

The `docker-compose-verify.yml` override extends the lite stack with:

- **Pipeline 0 (`e2e-logging`)** — no condition, logs at `INFO` → used by TC-1 and TC-4
- **Pipeline 1 (`security-alerts`)** — condition: `eventType = security.alert`, logs at `WARN` → used by TC-2
- **Redaction enabled** — `extra.userId` is masked (`***`), `extra.sessionId` is dropped → used by TC-3

```bash
docker compose -f docker-compose-lite.yml -f docker-compose-verify.yml up --build -d
```

Wait ~60 s for all containers to become healthy:

```bash
docker compose -f docker-compose-lite.yml ps
# All four rows should show "healthy" in the STATUS column
```

Confirm all three services respond:

```bash
curl -s http://localhost:8080/actuator/health | jq -r .status   # UP
curl -s http://localhost:8081/registry | jq '[.transformers[].id]'  # ["audit_loki","audit_opensearch","zero"]
curl -s http://localhost:8082/registry | jq '[.sinks[].id]'        # ["aws_cloudwatch_sink","aws_s3_sink",...]
```

### TC-1 — Happy-Path Event Flow

**What it verifies:** An event published via REST travels through RabbitMQ, is picked up by the consumer, passes through the `zero` (pass-through) transformer, and is delivered to the `logging_sink`.

#### Publish the event

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0001-000000000001",
    "eventType":   "user.login",
    "sourceSystem": "auth-service",
    "tenantId":    "T-VERIFY-001",
    "extra": {
      "userId":        "alice",
      "sessionId":     "sess-abc-123",
      "action_status": "SUCCESS"
    }
  }'
```

#### Where to verify

**Expected HTTP response:** `200 OK`, body `Audit event published successfully`

**Sink logs** — event delivered:

```bash
just log sink
# Ctrl+C to stop tailing
```

Look for a line containing `"eventType": "user.login"`. Because redaction is active, `extra.userId` will be `***` and `extra.sessionId` will be absent — this is expected behaviour.

**RabbitMQ UI** — http://localhost:15673 (guest / guest):

- *Exchanges* → `labs64-audit-topic` → message rate briefly spikes then returns to 0
- *Queues* → `labs64-audit-topic.labs64.io-auditflow` → **Ready** count = 0 (message was consumed)

#### Pass criteria

- [x] `200 OK` from the publish endpoint
- [x] Sink log contains an entry with `eventType: user.login`
- [x] RabbitMQ queue depth returns to 0

### TC-2 — Pipeline Condition Filtering

**What it verifies:** Pipeline 1 (`security-alerts`) only processes events where `eventType` equals `security.alert`. Other event types are handled by pipeline 0 only.

#### Step 2a — Send a non-matching event (pipeline 0 only)

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0002-000000000001",
    "eventType":   "api.call",
    "sourceSystem": "backend-service"
  }'
```

Check sink logs:

```bash
just log sink
```

**Expected:** exactly **one** new log entry at level `INFO`. No `WARNING` entry for this event.

#### Step 2b — Send a matching event (both pipelines)

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0002-000000000002",
    "eventType":   "security.alert",
    "sourceSystem": "auth-service",
    "extra": {
      "action_status": "FAILURE"
    }
  }'
```

Check sink logs:

```bash
just log sink
```

**Expected:** **two** new log entries for this event — one at `INFO` (pipeline 0) and one at `WARNING` (pipeline 1 / security-alerts).

#### Pass criteria

- [x] Non-matching `api.call` event → 1 sink log entry (INFO), no WARNING
- [x] Matching `security.alert` event → 2 sink log entries (INFO + WARNING)

### TC-3 — PII / Sensitive Data Redaction

**What it verifies:** `extra.userId` is replaced with `***` and `extra.sessionId` is dropped before the event enters RabbitMQ. Raw PII never reaches the message broker or any downstream sink.

Redaction applies globally at ingest (inside `AuditPublisherService`, before `StreamBridge.send()`), so it affects every pipeline.

#### Publish an event with PII fields

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0003-000000000001",
    "eventType":   "user.profile.updated",
    "sourceSystem": "profile-service",
    "extra": {
      "userId":    "alice",
      "sessionId": "sess-secret-999",
      "action":    "update_email"
    }
  }'
```

#### Where to verify

**Sink logs** — the event delivered to the sink must show the redacted payload:

```bash
just log sink
```

Look for the entry with `eventType: user.profile.updated` and confirm:

| Field | Raw value sent | Expected in log |
|---|---|---|
| `extra.userId` | `alice` | `***` |
| `extra.sessionId` | `sess-secret-999` | absent (dropped) |
| `extra.action` | `update_email` | `update_email` (unchanged) |

**RabbitMQ UI** — if you can capture a message in-flight (pause consumption or raise prefetch to 0), inspect the payload and confirm `alice` does not appear.

#### Pass criteria

- [x] Sink log shows `extra.userId` = `***`
- [x] Sink log has no `extra.sessionId` field
- [x] `extra.action` is unchanged (`update_email`)
- [x] The raw value `alice` and `sess-secret-999` do not appear anywhere in sink logs

### TC-4 — Idempotency (Duplicate Event Suppression)

**What it verifies:** Sending the same `eventId` twice within the 5-minute claim window results in the second event being silently suppressed at the consumer. Only one delivery reaches the sink.

The in-memory idempotency store uses claim-ttl = 5 minutes and done-ttl = 24 hours.

#### Step 4a — First publish (accepted)

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0004-000000000001",
    "eventType":   "order.created",
    "sourceSystem": "order-service",
    "tenantId":    "T-VERIFY-004"
  }'
```

**Expected:** `200 OK`, body `Audit event published successfully`

#### Step 4b — Duplicate publish (suppressed)

Run the **identical** command immediately:

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0004-000000000001",
    "eventType":   "order.created",
    "sourceSystem": "order-service",
    "tenantId":    "T-VERIFY-004"
  }'
```

**Expected:** `200 OK` — the API always accepts the request; deduplication happens asynchronously at the consumer level.

#### Where to verify

**Backend logs** — look for a dedup/idempotency rejection message for event `00000000-0000-0000-0004-000000000001`:

```bash
just log backend
```

Search for `duplicate`, `already`, `idempotent`, or the eventId string. The second occurrence should produce a rejection log, not a pipeline run.

**Sink logs** — the `order.created` event must appear exactly **once**:

```bash
just log sink
```

No matter how many times step 4b is repeated, the count in the sink stays at 1.

#### Pass criteria

- [x] Both publishes return `200 OK`
- [x] Backend log shows a dedup rejection for the second event
- [x] Sink log contains exactly one entry with `eventType: order.created` and `tenantId: T-VERIFY-004`

### Teardown

```bash
just down     # stop containers, keep images and build cache
# or
just clean    # stop containers AND remove volumes (full reset)
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
| `just up` | Build and start the full stack |
| `just up-lite` | Build and start the lite stack (faster) |
| `just obs-up` | Full stack + observability overlay |
| `just obs-up-lite` | Lite stack + observability overlay (fastest) |
| `just obs-down` | Stop observability containers only |
| `just open-obs` | Open Grafana, Prometheus, RabbitMQ in browser |
| `just verify` | Start the verification stack for manual testing |
| `just e2e` | Publish a test event (stack must be running) |
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

### Event ID conventions (Manual Verification)

| TC | eventId suffix | eventType |
|---|---|---|
| TC-1 | `0001-000000000001` | `user.login` |
| TC-2a | `0002-000000000001` | `api.call` |
| TC-2b | `0002-000000000002` | `security.alert` |
| TC-3 | `0003-000000000001` | `user.profile.updated` |
| TC-4 | `0004-000000000001` | `order.created` |
