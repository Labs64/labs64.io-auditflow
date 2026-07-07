<p align="center"><img src="https://raw.githubusercontent.com/Labs64/.github/refs/heads/master/assets/labs64-io-ecosystem.png"></p>

# Labs64.IO :: AuditFlow

**Audit logging for microservices — publish once, route anywhere, lose nothing.**

[![CI](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml/badge.svg)](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml)
[![Docker Image Version](https://img.shields.io/docker/v/labs64/auditflow?logo=docker&logoColor=%23E14817&color=%23E14817)](https://hub.docker.com/r/labs64/auditflow)
[![License: LGPL v3](https://img.shields.io/badge/License-LGPL_v3-blue.svg)](LICENSE)
[![📖 Documentation](https://img.shields.io/badge/📖-Documentation-AB6543.svg)](https://github.com/Labs64/labs64.io-docs)

AuditFlow is an open-source pipeline for capturing audit events from your services and delivering them — reliably, with sensitive data stripped out at the door — to wherever they need to live: a search index, cold storage, a SIEM, or all of them at once. Your services publish events with a single REST call; you describe the routing in YAML. AuditFlow takes care of the buffering, redaction, deduplication, retries, and fan-out.

**Contents**

- [What is AuditFlow?](#what-is-auditflow)
- [Usage scenarios](#usage-scenarios) — find the one closest to yours
- [Features](#features)
- [Architecture](#architecture)
- [Quick start](#quick-start)
- [Deployment](#deployment) — Docker Compose · Kubernetes · cloud-managed
- [Configuring pipelines](#configuring-pipelines)
- [Built-in sinks and transformers](#built-in-sinks-and-transformers)
- [Developer guide](#developer-guide)
- [Contributing](#contributing)

---

## What is AuditFlow?

Most teams end up reinventing audit logging in every service — one writes to a database table, another POSTs to a logging API, a third does nothing until an auditor comes asking. The rules for *what* to capture, *where* it goes, and *how* to keep sensitive fields out of your logs get copy-pasted from service to service and quietly drift apart.

AuditFlow pulls that responsibility into one place. A service emits an audit event with a single REST call; AuditFlow buffers it on a message broker, redacts the fields that shouldn't be stored, evaluates it against your configured **pipelines**, and delivers the result to one or more destinations (called **sinks**). Want audit data to flow somewhere new? Edit YAML — don't redeploy every service.

```bash
curl -X POST http://localhost:8080/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventType":    "user.login",
    "sourceSystem": "auth-service",
    "tenantId":     "acme",
    "extra": { "userId": "alice", "ip": "203.0.113.7" }
  }'
```

That single call is all a service needs to know about. Everything after it — routing, transformation, redaction, retries, fan-out — is **configuration, not code**. Sinks and transformers are plain Python modules you drop into a folder; they load dynamically at runtime, so adding a destination never means touching the backend.

**AuditFlow is a good fit when:**

- You need to answer "who did what, when, and from where" — reliably, across multiple services — and you're tired of each service solving this differently (or not at all).
- You need events delivered to more than one destination — a search index for ops, cold storage for compliance, an alerting channel for security — without wiring that up by hand.
- Sensitive fields (user IDs, session tokens, health data) must never reach your log storage. Redaction at the API boundary, before the broker, is a hard requirement.
- Delivery guarantees matter: you can't afford silent event loss, duplicate processing, or a sink outage silently dropping your audit trail.
- You want to add or reroute destinations without touching service code or redeploying the backend.

**AuditFlow complements OpenTelemetry — it does not replace it.** They look similar (both capture events from your services and ship them somewhere), but they answer different questions and serve different audiences:

| | OpenTelemetry / Observability | AuditFlow |
|---|---|---|
| **Question answered** | *Is my system healthy?* — latency, error rates, throughput, resource usage | *Who did what, when, and with what outcome?* — intentional actions by users and services |
| **Primary consumer** | SREs, platform engineers debugging behaviour | Compliance, security, and legal — plus auditors |
| **Data character** | High-volume, sampled, short retention, disposable | Every event matters, lossless, long retention, often immutable |
| **When it's wrong** | A dropped span is a gap in a graph | A dropped audit event is a compliance failure |
| **Typical question** | "Why was the checkout API slow at 14:00?" | "Prove user X never accessed tenant Y's records." |

Both belong in a production system. AuditFlow is built around the assumption that **every event matters** — hence idempotency, the dead-letter queue, retries, and ingest-time redaction. That durability-first posture is exactly what general observability pipelines trade away for volume and speed.

**AuditFlow is probably not what you need if:**

- You want distributed tracing, metrics, or infrastructure logs only — reach for OpenTelemetry, Prometheus, or your observability platform of choice.
- You want to intercept raw HTTP traffic at the proxy or sidecar layer — AuditFlow is event-driven; your services decide what to publish.

---

## Usage Scenarios

The fastest way to tell whether AuditFlow fits is to find the situation closest to yours. Each scenario below ends with a short, concrete proof-of-concept you can run on the local stack in an afternoon.

- [**Compliance audit trail**](#compliance-audit-trail-gdpr-soc-2-iso-27001-hipaa) — GDPR, SOC 2, ISO 27001, HIPAA
- [**Security event alerting**](#security-event-alerting-and-siem-integration) — route alerts to your SIEM in near real-time
- [**Multi-tenant SaaS audit logs**](#multi-tenant-saas-audit-logs) — a pipeline per tenant, isolated and throttled
- [**Centralised audit hub**](#centralised-audit-hub-across-microservices) — one pipeline serving many services
- [**Observability reference**](#microservices-observability-reference-implementation) — OpenTelemetry across a polyglot stack
- [**Developer sandbox**](#developer-sandbox-and-local-integration-testing) — run the whole thing locally in a minute

### Compliance audit trail (GDPR, SOC 2, ISO 27001, HIPAA)

You need a verifiable record of who did what and when, stored somewhere tamper-resistant and queryable during an audit. AuditFlow publishes events from your services via a single REST call, redacts PII or health data at ingest (before it ever hits the broker), and delivers clean audit records to OpenSearch for full-text search, S3/GCS/Azure Blob for long-term immutable archival, or both simultaneously via fan-out pipelines.

The event payload is yours to define — user identity, action, resource, outcome, timestamp — and the schema is enforced via OpenAPI. Each event carries a correlation ID for cross-service traceability.

**What to try in a POC:**
- Enable PII redaction on `extra.userId` / `extra.email`
- Create two pipelines: one to `opensearch_sink` (searchable), one to `aws_s3_sink` (archival)
- Verify that redacted fields are absent from both destinations

---

### Security event alerting and SIEM integration

Your services generate security-relevant events (login failures, privilege escalations, suspicious access patterns) mixed in with routine operational traffic. You need the security events routed to your SIEM or alerting channel in near real-time, without burdening the core service or slowing down routine calls.

Use a conditional pipeline with `eventType eq security.alert` (or a `regex` on your event schema). Only matching events reach your `splunk_sink`, `datadog_sink`, or `webhook_sink` alert handler. Routine events flow to a separate pipeline targeting cheaper storage. The circuit breaker and retry guard against SIEM downtime — events queue in the broker and replay automatically when the SIEM recovers.

**What to try in a POC:**
- Define two pipelines: `security-alerts` (condition: `eventType = security.alert`) → Splunk; `audit-archive` (no condition) → S3
- Send a mix of event types and confirm only security events reach Splunk
- Take the webhook endpoint offline briefly and confirm events are retried, not lost

---

### Multi-tenant SaaS audit logs

Your SaaS platform serves multiple tenants. Each tenant may have different audit data residency or delivery requirements — one wants logs in their own S3 bucket, another in their Splunk instance. AuditFlow lets you define a pipeline per tenant, each with its own condition (`tenantId eq ACME`), sink, and properties (bucket name, API key). Adding a new tenant means adding a YAML stanza — no code change, no deploy.

Combined with per-pipeline rate limiting, noisy tenants can be throttled without affecting others.

**What to try in a POC:**
- Create two pipelines differentiated by `tenantId`
- Point each at a different `webhook_sink` URL to simulate distinct tenant endpoints
- Confirm events for tenant A never appear in tenant B's stream

---

### Centralised audit hub across microservices

You have many services — each currently solving audit logging ad hoc (some write to their own DB, some call a shared log API, some do nothing). You want a single, consistent audit pipeline without refactoring every service.

Each service publishes events to AuditFlow's REST endpoint. From there, one set of pipelines handles routing, transformation, and delivery for all of them. New services onboard by making one REST call — the pipeline logic stays in one place.

**What to try in a POC:**
- Stand up AuditFlow alongside two existing services
- Have each service publish events with a distinct `sourceSystem` field
- Use `sourceSystem`-based conditions to route each service's events to a different sink

---

### Microservices observability reference implementation

You're building or evaluating a microservices platform and want a reference that shows how to wire distributed tracing, structured logging, and metrics end-to-end across a polyglot stack (Java + Python). AuditFlow ships with a full OpenTelemetry observability stack: one command brings up OTel Collector, Tempo (traces), Loki (logs), Prometheus (metrics), and a pre-wired Grafana dashboard.

Traces flow from the Java backend through the Python transformer and sink services. Log records carry trace IDs so clicking a trace in Grafana shows correlated logs in Loki. All three signal types are auto-configured — no hand-written OTel SDK beans, no manual appender wiring.

**What to try in a POC:**
- Run `just up obs` and open Grafana at http://localhost:3000
- Explore the AuditFlow Overview dashboard — click a trace, then follow it to its correlated logs

---

### Developer sandbox and local integration testing

You're building a service that publishes audit events and want to verify end-to-end behaviour locally without standing up a full production stack. The local stack (three services + RabbitMQ, in-memory dedup) starts in under a minute. Pipelines and redaction rules are configured via `JAVA_OPTS` in `docker-compose.yml`.

**What to try:**
- `just up` — starts the stack
- Walk through [DEVELOPERS.md](DEVELOPERS.md#manual-verification-plan)

---

## Features

Everything below ships in the box and works today — this is a description of the software as it stands, not a roadmap.

### Pipeline-as-configuration
Define where events go — and under what conditions — entirely in YAML or environment variables. Multiple pipelines evaluate independently for each event; one failing pipeline never stops the others. No backend code changes, no rebuilds, no restarts required to add or reroute a destination.

### Intelligent event routing
Route events selectively using rich field-level condition rules on any JSON field, including nested paths (`extra.userId`) and array indices (`items[0].name`). Combine rules with AND / OR logic. Supported operators: `eq`, `neq`, `eqIgnoreCase`, `contains`, `startsWith`, `endsWith`, `in`, `notIn`, `exists`, `notExists`, `regex`, `gt`, `gte`, `lt`, `lte`. Unrelated traffic never reaches sinks that don't need it.

### Asynchronous, decoupled processing
Every audit event is buffered on the message broker before processing. Your calling service gets an immediate acknowledgement and continues — the pipeline runs on a separate consumer thread. API latency stays flat under burst load, and the broker absorbs traffic spikes without back-pressure reaching upstream services.

### Broker-agnostic transport
The event backbone is built on Spring Cloud Stream, so the message broker is a configuration choice rather than a baked-in dependency. RabbitMQ is the default and Kafka ships on the classpath — switch with a single `default-binder` property, or plug in any other Spring Cloud Stream binder. Your pipelines, conditions, sinks, and transformers behave identically no matter what's moving the events underneath.

### Multi-destination fan-out
A single event can be delivered to multiple sinks simultaneously — log it to Loki, archive it to S3, and alert via webhook, all from one publish call. Each pipeline is independent: different conditions, different transformers, different destinations, evaluated in parallel.

### Rich sink catalogue
Deliver audit events without writing glue code. Destinations include: log output (for local dev), HTTP webhook, RFC 5424 syslog, Grafana Loki, OpenSearch / Elasticsearch, Amazon S3, Amazon CloudWatch Logs, Google Cloud Storage, Azure Blob Storage, Datadog Logs API, Splunk HEC, Snowflake, and Labs64 NetLicensing. Sink properties (URLs, API keys, bucket names) are declared per-pipeline and resolved from environment variables — no credentials in configuration files.

### Transformer pipeline with chaining
Shape or enrich an event before delivery. Transformers are Python modules loaded dynamically at runtime. The built-in set covers pass-through, Loki-optimised labels, and OpenSearch indexing conventions. Chain multiple transformers in sequence within a single pipeline to compose richer transformations without coupling them together.

### PII and sensitive data redaction
Declare which fields to mask (`***`) or drop entirely. Redaction runs at ingest — before the event is published to the broker — so sensitive values never reach the message broker, never appear in broker logs, and are never forwarded to any downstream sink. Rules are fine-grained, per field, with independent strategies per rule.

### Idempotent event processing
Each event carries an `eventId`. The consumer checks a deduplication store before processing: duplicate deliveries from broker redelivery, network retries, or at-least-once producers are silently suppressed. Uses Redis in production for distributed dedup; an in-memory store for single-process development. Claim TTL and completion TTL are independently configurable.

### Resilience and fault tolerance
Every outbound HTTP call to a transformer or sink service is guarded by a **circuit breaker** and **retry with backoff**. Events that exhaust all retries land in a **dead-letter queue** with full payload preservation — nothing is silently discarded. The DLQ is queryable and replayable via the `/actuator/dlq` endpoint. **Per-pipeline rate limiting** throttles inbound event volume without affecting other pipelines. **Graceful shutdown** drains in-flight events (configurable timeout) before the process exits, preventing data loss during rolling restarts.

### Sink fallback routing
Designate a fallback sink per pipeline. If the primary sink returns a retryable error (network failure, timeout, 5xx), AuditFlow automatically routes the event to the fallback rather than the DLQ — keeping delivery continuity during planned maintenance windows or transient dependency outages.

### Secure plugin sandboxing
Transformer and sink module IDs are validated against an allow-list regex (`^[a-zA-Z0-9_]+$`) at the HTTP boundary, preventing path traversal and arbitrary module injection. Custom plugin directories (`sinks_bootstrap/`, `transformers_bootstrap/`) are separate from built-in modules and can be mounted at runtime via ConfigMap or volume without modifying the image. Each service exposes a `/registry` endpoint that lists all available modules with their version, description, and documented properties — also used as the Docker healthcheck.

### Full OpenTelemetry observability
Distributed traces (OTLP → Tempo), structured logs (OTLP → Loki), and metrics (Prometheus scrape + OTLP push) are wired end-to-end across all three services. A pre-provisioned Grafana dashboard surfaces request rate, error rate, recent traces, and live log streams out of the box — one command starts the full observability stack. Cross-signal linking is built in: clicking a trace in Tempo opens the correlated logs in Loki.

### Pluggable service discovery
Switch between `local` (a configured base URL) and `kubernetes` (fabric8 `KubernetesClient` resolves the Service ClusterIP at runtime) with a single property. No code paths change; only the discovery implementation is swapped. The same configuration file works identically in local Docker Compose and in a Kubernetes cluster.

### OpenAPI-first contract
The public API contract lives in a single YAML spec. Java models and the controller interface are generated at build time — generated sources are never committed and never hand-edited. Changing the API means editing the YAML. The Swagger UI ships with the backend for interactive exploration at `/swagger-ui.html`.

---

## Architecture

```
POST /audit/publish  (direct; via gateway: /auditflow/api/v1/audit/publish)
        │
        ▼
  Backend  (Java · Spring Boot · :8080)
        │  redact PII → publish to broker topic
        ▼
  Message broker  labs64-audit-topic        (RabbitMQ by default · Kafka-ready)
        │
        ▼  consumer (same backend, separate thread)
  AuditService.processAuditEvent()
        │  for each ENABLED pipeline whose condition matches:
        ├─► Transformer  (Python · FastAPI · :8081)   POST /transform/{name}
        └─► Sink         (Python · FastAPI · :8082)   POST /sink/{name}
```

Three independently deployable services:

| Service | Stack | Port | Role |
|---------|-------|------|------|
| `auditflow-be` | Java 25, Spring Boot 4, Maven | 8080 | REST API, broker, pipeline orchestration |
| `auditflow-transformer` | Python 3.13, FastAPI | 8081 | Dynamically-loaded transform modules |
| `auditflow-sink` | Python 3.13, FastAPI | 8082 | Dynamically-loaded sink/delivery modules |

![AuditFlow architecture diagram](https://github.com/user-attachments/assets/ca5f0c0e-81dc-439d-a855-95ebc2fc50ed)

Key design decisions:

- The backend is **both producer and consumer** of the same broker topic, providing decoupling and buffering without a separate ingestion service.
- The broker is reached through **Spring Cloud Stream binders** — RabbitMQ by default, Kafka on the classpath — so the transport can change without touching pipeline logic.
- **Pipelines are independent** — a failure in one pipeline (e.g., an unreachable sink) is logged and skipped; other pipelines for the same event continue normally.
- **Python services are stateless plugins** — they do not hold broker connections or pipeline state; they receive a request, run a module function, and return a result.
- A **dead-letter queue** captures events that exhaust retries, with a management API for inspection and replay.

---

## Quick Start

Three commands and you have the full stack — backend, broker, transformer, and sink — publishing and delivering events on your machine. You'll need Docker, Docker Compose v2, and [`just`](https://github.com/casey/just).

```bash
# Clone
git clone https://github.com/Labs64/labs64.io-auditflow.git
cd labs64.io-auditflow

# Build images and start the full stack (3 services + RabbitMQ + Redis)
just up

# Watch the sink receive it
just log sink
# Look for: "Audit Event Logged" — then Ctrl+C

# Tear down
just down
```

Local URLs once the stack is running:

| URL | Purpose |
|-----|---------|
| http://localhost:8080/swagger-ui.html | Interactive REST API |
| http://localhost:8081/docs | Transformer registry + API docs |
| http://localhost:8082/docs | Sink registry + API docs |
| http://localhost:15673 | RabbitMQ Management UI (`guest` / `guest`) |

---

## Deployment

### Docker Compose

Two Compose profiles cover local iteration and observability:

| Command | Stack | When to use |
|---------|-------|-------------|
| `just up` | 3 services + RabbitMQ | Fastest start; in-memory dedup |
| `just up obs` | Stack + OTel Collector + Tempo + Loki + Prometheus + Grafana | Full telemetry, fast iteration |

```bash
cp .env.example .env   # optional — only needed for custom RabbitMQ credentials
just up obs            # recommended starting point
```

Observability overlay URLs:

| URL | Credentials | Purpose |
|-----|-------------|---------|
| http://localhost:3000 | admin / admin | Grafana — pre-provisioned AuditFlow Overview dashboard |
| http://localhost:9090 | — | Prometheus query UI |
| http://localhost:3100 | — | Loki API (use Grafana for UI) |
| http://localhost:3200 | — | Tempo API (use Grafana for UI) |

### Kubernetes

AuditFlow supports Kubernetes-native service discovery. Set `discovery.mode: kubernetes` and the backend resolves transformer and sink service IPs via the fabric8 `KubernetesClient` at runtime — no hardcoded URLs in Deployment specs.

```yaml
# application.yml or JAVA_OPTS in your Deployment
transformer:
  discovery:
    mode: kubernetes
    namespace: auditflow
    service-name: auditflow-transformer

sink:
  discovery:
    mode: kubernetes
    namespace: auditflow
    service-name: auditflow-sink
```

Health probes are ready to use out of the box:

```yaml
livenessProbe:
  httpGet:
    path: /live       # Python services; use /actuator/health for the Java backend
    port: 8081
readinessProbe:
  httpGet:
    path: /ready
    port: 8081
```

Custom sinks and transformers can be injected at runtime via a ConfigMap or volume — no image rebuild required:

```yaml
volumeMounts:
  - name: custom-sinks
    mountPath: /home/l64user/sinks_bootstrap
volumes:
  - name: custom-sinks
    configMap:
      name: my-sink-plugins
```

> Helm charts and production Kubernetes manifests are on the roadmap. Contributions are welcome — see [Contributing](#contributing).

### Cloud-managed infrastructure

Swap any self-hosted component for a managed cloud service — pipeline behaviour is unchanged:

| Component | Cloud alternatives |
|-----------|--------------------|
| Message broker (RabbitMQ / Kafka) | Amazon MQ, CloudAMQP, Confluent Cloud, Amazon MSK, Azure Service Bus |
| Redis (idempotency) | ElastiCache, Azure Cache for Redis, Memorystore |
| Log storage | Amazon OpenSearch Service, Grafana Cloud Loki |
| Archive storage | Amazon S3, Google Cloud Storage, Azure Blob Storage |
| Metrics / traces | Grafana Cloud, Datadog, Honeycomb |

The cloud sinks (`aws_s3_sink`, `aws_cloudwatch_sink`, `gcs_sink`, `azure_blob_sink`) read connection details from `sink.properties`, resolved from environment variables — no credentials in configuration files.

---

## Configuring Pipelines

Pipelines live in `application.yml` under `auditflow.pipelines`, or are passed as `JAVA_OPTS` system properties in Docker/Kubernetes environments.

```yaml
auditflow:
  pipelines:
    - name: security-alerts
      enabled: true
      condition:
        match: all           # "all" (AND) or "any" (OR)
        rules:
          - field: eventType
            operator: eq
            value: "security.alert"
          - field: extra.severity
            operator: in
            value: "HIGH,CRITICAL"
      transformer:
        name: audit_loki     # optional — omit to pass through unchanged
      sink:
        name: loki_sink
        properties:
          url: "http://loki:3100"
        fallback:            # optional — used when primary sink fails with a retryable error
          name: webhook_sink
          properties:
            url: "https://hooks.example.com/auditflow"
```

**Available condition operators:** `eq`, `neq`, `eqIgnoreCase`, `contains`, `startsWith`, `endsWith`, `in`, `notIn`, `exists`, `notExists`, `regex`, `gt`, `gte`, `lt`, `lte`

Field paths support dot notation (`extra.userId`) and array indices (`items[0].name`).

### PII Redaction

```yaml
auditflow:
  redaction:
    enabled: true
    rules:
      - field: extra.userId
        strategy: mask       # replace with ***
      - field: extra.sessionId
        strategy: drop       # remove field entirely
```

Redaction runs at ingest, before the event is published to the broker — sensitive values never reach it or any downstream sink.

---

## Built-in Sinks and Transformers

### Sinks

| Sink | Destination |
|------|-------------|
| `logging_sink` | Log output (dev/testing) |
| `webhook_sink` | HTTP POST to any URL |
| `syslog_sink` | RFC 5424 syslog |
| `loki_sink` | Grafana Loki |
| `opensearch_sink` | OpenSearch / Elasticsearch |
| `aws_s3_sink` | Amazon S3 |
| `aws_cloudwatch_sink` | Amazon CloudWatch Logs |
| `gcs_sink` | Google Cloud Storage |
| `azure_blob_sink` | Azure Blob Storage |
| `datadog_sink` | Datadog Logs API |
| `splunk_sink` | Splunk HTTP Event Collector |
| `snowflake_sink` | Snowflake (via REST) |
| `netlicensing_sink` | Labs64 NetLicensing |

### Transformers

| Transformer | What it does |
|-------------|-------------|
| `zero` | Pass-through (no change) |
| `audit_loki` | Reshape event for Loki label conventions |
| `audit_opensearch` | Reshape event for OpenSearch indexing |

### Adding your own sink or transformer

Drop a `.py` file into `sinks/` or `transformers/`. It's discovered at runtime — no backend changes, no image rebuild for local development.

```python
# sinks/my_sink.py
def process(event_data: dict, properties: dict) -> dict:
    # event_data: the audit event JSON
    # properties: pipeline-specific config from sink.properties
    return {"sent": True}
```

Then reference it by filename (without `.py`) in a pipeline:

```yaml
sink:
  name: my_sink
  properties:
    api-key: "${MY_API_KEY}"
```

See [DEVELOPERS.md](DEVELOPERS.md) for full details on the plugin API, transformer chaining, and testing.

---

## Developer Guide

The [DEVELOPERS.md](DEVELOPERS.md) covers everything for working on AuditFlow locally:

- Architecture deep-dive and key design decisions
- Prerequisites and environment setup
- Running the full, lite, observability, and verification stacks
- Writing and testing sinks and transformers
- Pipeline condition reference
- Health endpoints and circuit breaker metrics
- Troubleshooting common issues

**Prerequisites at a glance:**

| Tool | Minimum version |
|------|----------------|
| Java (Temurin) | 25 |
| Maven | 3.6.3+ |
| Python | 3.13 |
| Docker Engine | 24+ |
| Docker Compose | v2 |
| `just` | any |

---

## Contributing

Community input shapes where AuditFlow goes next. Contributions of all sizes are welcome.

**Good first areas to contribute:**

- New sinks (many destinations are still missing from the catalogue)
- New condition operators or transformer utilities
- Helm chart / Kubernetes manifests
- Python service test coverage
- Documentation improvements and usage examples

**To get started:**

1. Fork the repository and create a branch.
2. For a new sink or transformer, see the [plugin guide in DEVELOPERS.md](DEVELOPERS.md#adding-a-new-sink).
3. Run `just test` to make sure existing tests pass.
4. Open a pull request with a clear description of what you changed and why.

If you're unsure whether something is a good fit, open an issue first — it saves everyone time. And if AuditFlow helps you, a ⭐ on the repo helps others find it.

---

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=Labs64/labs64.io-auditflow&type=Date)](https://www.star-history.com/#Labs64/labs64.io-auditflow&Date)

---

## License

AuditFlow is licensed under the [GNU Lesser General Public License v3.0](LICENSE).
