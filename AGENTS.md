# AGENTS.md — Labs64.IO :: AuditFlow

Guidance for agentic AI tools working in this repository. Read this before making changes.

## What this project is

AuditFlow is a scalable, microservices-based audit-logging pipeline. A client publishes an
**audit event** over REST; the event is buffered in a message broker, then processed
asynchronously through one or more configurable **pipelines**. Each pipeline runs the event
through a **transformer** (reshape/enrich) and delivers the result to a **sink** (a storage or
notification destination).

This is a **polyglot monorepo** with three independently deployable services.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Repository layout

| Path | Service | Stack | Port | Role |
|------|---------|-------|------|------|
| `auditflow-be/` | Backend | Java 25, Spring Boot 4.1.0, Maven | 8080 | REST API, broker publish/consume, pipeline orchestration |
| `auditflow-transformer/` | Transformer | Python 3.13, FastAPI, Uvicorn | 8081 | Dynamically-loaded transform modules |
| `auditflow-sink/` | Sink | Python 3.13, FastAPI, Uvicorn | 8082 | Dynamically-loaded sink/delivery modules |

Root-level orchestration:
- `justfile` — top-level task runner (`just up`, `just up-lite`, `just obs-up`, `just obs-up-lite`, `just e2e`, `just logs`, `just down`).
- `docker-compose.yml` — full local stack (3 services + RabbitMQ + Redis) with a pre-wired happy-path pipeline.
- `docker-compose-lite.yml` — trimmed local stack (`just up-lite`): 3 services + RabbitMQ only (no Redis). Sets `auditflow.idempotency.store=memory` so the dedup guard runs in-process (single-process; not for clustered use).
- `docker-compose-observability.yml` — observability overlay (`just obs-up` / `just obs-up-lite`): OTel Collector + Tempo + Loki + Prometheus + Grafana. Compose on top of any base stack. See [Observability](#observability) below.
- `docker-compose-infra.yml` — RabbitMQ only, for running services on the host.
- `.env.example` — copy to `.env`; supplies `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD`.
- `.github/workflows/` — CI (build + test all three) and Docker publish.

## End-to-end data flow

```
POST /api/v1/audit/publish
        │  (AuditEventController → AuditPublisherService)
        ▼
RabbitMQ topic  labs64-audit-topic   (Spring Cloud Stream binding audit-out-0)
        │
        ▼  (binding audit-in-0 → AuditSubscriberService.audit() Consumer)
AuditService.processAuditEvent(json)
        │   for each ENABLED pipeline whose condition matches:
        ├─► TransformationService → POST http://transformer:8081/transform/{name}
        └─► SinkService          → POST http://sink:8082/sink/{name}
```

Key behaviour:
- The backend is **both producer and consumer** of the same topic (decoupling + buffering).
- Pipelines are evaluated independently; one failing pipeline does **not** stop the others
  (`AuditService` logs and continues).
- A pipeline with no transformer passes the original message through unchanged.
- Consumer uses a dead-letter queue (`autoBindDlq: true`, `republishToDlq: true`, `requeueRejected: false`).

## Backend (`auditflow-be`) details

- **Build is OpenAPI-first.** The `AuditEvent` / `ErrorResponse` models and the `AuditEventApi`
  interface are **generated at build time** from `src/main/resources/openapi/openapi-audit-v1.yaml`
  by the `openapi-generator-maven-plugin` (package `io.labs64.audit.v1.*`). To change the API
  contract, **edit the YAML, not generated code** — generated sources live under
  `target/generated-sources` and are git-ignored. `AuditEventController implements AuditEventApi`.
- **Pipelines are configuration, not code.** Defined under the `auditflow.pipelines` prefix and
  bound by `AuditFlowConfiguration`. Each pipeline = `name`, `enabled`, optional `condition`,
  `transformer.name`, `sink.name`, `sink.properties` (a string map passed to the sink).
- **Conditions** are evaluated by `ConditionEvaluator` against the event JSON. `match` is `all`
  (AND) or `any` (OR). Operators: `eq, neq, contains, startsWith, endsWith, in, notIn, exists,
  notExists, regex, gt, gte, lt, lte, eqIgnoreCase`. Field paths support dot notation and array
  indices (`extra.userId`, `items[0].name`). See the commented example in `application.yml`.
- **Service discovery** is pluggable via `*.discovery.mode` (`local` | `kubernetes`):
  - `local` → `LocalDiscoveryService` / `LocalSinkDiscovery` use a configured base URL.
  - `kubernetes` → `KubernetesDiscoveryService` / `KubernetesSinkDiscovery` resolve a Service
    ClusterIP via the fabric8 `KubernetesClient`. Beans are selected with
    `@ConditionalOnProperty`, so only one implementation of each interface
    (`TransformerDiscovery`, `SinkDiscovery`) is active.
- **HTTP to Python services** uses reactive `WebClient` (5s connect / 10s response timeout),
  cached per base URL. Calls are made blocking with `.block()`.
- Cross-cutting: `CorrelationIdFilter` (X-Correlation-ID), `GlobalExceptionHandler`
  (uniform `ErrorResponse`), `JacksonConfig`, `OpenAPIConfig`. Observability via Actuator +
  Micrometer Tracing (OTLP/HTTP) + Prometheus scrape at `/actuator/prometheus` + OTLP metrics push.
- **Observability wiring**: On Spring Boot 4.1, OpenTelemetry SDK auto-configuration lives in the
  dedicated `spring-boot-opentelemetry` (SDK bean + OTLP log/trace/metrics export) and
  `spring-boot-micrometer-tracing-opentelemetry` (Micrometer observations → OTLP spans + W3C
  context propagation) modules. The backend depends on both. (`micrometer-tracing-bridge-otel` alone
  is *not* enough: it provides the Micrometer→OTel abstraction but never creates the `Tracer` bean
  or registers tracing `ObservationHandler`s, so observations silently produce metrics but **zero
  spans** — that was the bug fixed when the manual `OtelConfig` was removed.)
  **Critical gap in Spring Boot 4.1**: `spring-boot-opentelemetry` creates the `OpenTelemetry` Spring
  bean and sets up OTLP exporters, but **never calls `OpenTelemetryAppender.install()`** and never
  registers the SDK as `GlobalOpenTelemetry`. The Logback `OpenTelemetryAppender` declared in
  `logback-spring.xml` would silently drop every log event without an explicit install call.
  `OtelLogbackInstaller` (in `config/`) is the `ApplicationListener<ApplicationReadyEvent>` that
  bridges the gap — it calls `OpenTelemetryAppender.install(openTelemetry)` with the Spring-managed
  bean once the context is ready. **Do not remove `OtelLogbackInstaller`** — without it backend logs
  will never reach Loki, with no error (logs are silently discarded).
  `opentelemetry-logback-appender-1.0` must remain an explicit dependency — it is *not* pulled in
  transitively. Traces and logs use **different OTLP paths** (`/v1/traces` vs `/v1/logs`); the
  collector routes by path. **In Spring Boot 4.1+, the correct OTLP logging property is
  `management.opentelemetry.logging.export.otlp.endpoint`** (the old `management.otlp.logging.endpoint`
  path is silently ignored — the `@ConfigurationProperties` prefix moved to
  `management.opentelemetry.logging.export.otlp` in Spring Boot 4.1.0).

## Python services (`auditflow-transformer`, `auditflow-sink`) details

Both follow the **same plugin pattern** — keep them symmetric when editing one.

- A request to `POST /transform/{id}` (or `/sink/{id}`) dynamically `importlib.import_module(id)`s
  a module by that name and calls a well-known function:
  - **Transformer module** must define `transform(input_data: dict) -> dict`.
  - **Sink module** must define `process(event_data: dict, properties: dict) -> dict`.
- The `id` is validated against `^[a-zA-Z0-9_]+$` to prevent path traversal / arbitrary import.
  **Keep this validation** (the backend `TransformationService`/`SinkService` enforce the same
  regex — change both sides together).
- Module resolution paths:
  - Internal, shipped in the image: `transformers/` and `sinks/`.
  - External, mounted at runtime (ConfigMap/volume), git-ignored except `.gitkeep`:
    `transformers_bootstrap/` and `sinks_bootstrap/`.
- `GET /registry` lists available modules with version, description, and documented properties (also used as the Docker healthcheck).
- Existing sinks: `logging_sink`, `webhook_sink`, `syslog_sink`, `loki_sink`, `opensearch_sink`,
  `aws_s3_sink`, `aws_cloudwatch_sink`, `gcs_sink`, `azure_blob_sink`, `netlicensing_sink`.
  Existing transformers: `zero` (pass-through), `audit_loki`, `audit_opensearch`.

### Adding a transformer or sink

1. Drop `myname.py` into `transformers/` (or `sinks/`) implementing the required function.
2. If it needs new Python packages, add them to that service's `requirements.txt`.
3. Reference it from a pipeline: `transformer.name: myname` / `sink.name: myname` (+ `sink.properties`).
4. No backend code change is needed — the name is resolved dynamically.

## Build, run, test

Prefer the `just` recipes. From the repo root:

```bash
just up           # build backend JAR, build all images, start full stack + RabbitMQ
just up-lite      # trimmed stack (no Redis, in-memory dedup)
just obs-up       # full stack + OTel Collector + Tempo + Loki + Prometheus + Grafana
just obs-up-lite  # lite stack + observability overlay (fastest local iteration)
just e2e          # publish a test event and confirm it flows to the logging sink
just log sink     # tail a single service (backend|transformer|sink|rabbitmq)
just down         # stop containers   |   just clean = also remove volumes
```

Per-service / direct commands:

```bash
# Backend (Java) — note: build is driven from the auditflow-be/pom.xml
mvn -B clean package -DskipTests --file auditflow-be/pom.xml   # build JAR (needed before its image)
mvn -B verify --file auditflow-be/pom.xml                      # run unit tests (== just test-be / CI)

# Python services run with hot reload on the host (RabbitMQ via `just infra-up`)
cd auditflow-transformer && just run-local   # uvicorn transformer:app --reload on :8081
cd auditflow-sink        && just run-local   # uvicorn sink:app        --reload on :8082
```

Local URLs when the stack is up: backend Swagger `http://localhost:8080/swagger-ui.html`,
transformer/sink docs `:8081/docs` / `:8082/docs`, RabbitMQ UI `http://localhost:15673` (guest/guest).
Observability overlay: Grafana `http://localhost:3000` (admin/admin), Prometheus `http://localhost:9090`.

> Compose remaps RabbitMQ to host ports **5673/15673** (not the defaults 5672/15672).

## Conventions & guardrails

- **Java 25 and Maven 3.6.3+ are enforced** by the `maven-enforcer-plugin`; the build fails on
  older versions. CI uses Temurin JDK 25 and Python 3.13 — match these.
- **Never edit OpenAPI-generated Java** under `target/`. Change the YAML spec and rebuild.
- **`timestamp` is server-assigned** (`readOnly`) — set in the controller, never trust client input.
- **Credentials come from env vars only.** `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` have no defaults
  in `application.yml` (intentional, to avoid silent unauthenticated connections). The API is
  documented with `bearerAuth` (JWT) security.
- Keep the **transformer/sink id validation regex consistent** across Java and Python.
- Backend tests live in `auditflow-be/src/test/java/...` using JUnit + Spring Boot Test
  (e.g. `AuditServiceTest`, `ConditionEvaluatorTest`, `SinkServiceTest`,
  `TransformationServiceTest`, `KubernetesSinkDiscoveryTest`). Add tests alongside new backend logic.
- The Python services currently have no automated tests; CI only builds their Docker images.
- All three Dockerfiles run as a non-root user `l64user` (uid/gid 1064). Preserve this.
- Logging: backend uses SLF4J/Logback with logstash JSON encoder; `io.labs64` is at DEBUG.

## Observability

The observability overlay (`docker-compose-observability.yml`) is composed on top of any base stack
via `just obs-up` / `just obs-up-lite`. It adds:

| Component | Container | Port(s) |
|-----------|-----------|---------|
| OTel Collector | `auditflow-otel-collector` | 4317 (gRPC), 4318 (HTTP), 8889 (Prometheus exporter) |
| Tempo | `auditflow-tempo` | 3200 |
| Loki | `auditflow-loki` | 3100 |
| Prometheus | `auditflow-prometheus` | 9090 |
| Grafana | `auditflow-grafana` | 3000 |

Signal routing:
- **Traces** — Spring Boot OTLP/HTTP → OTel Collector → Tempo. Configured via `management.otlp.tracing.endpoint`.
- **Logs** — Logback `OpenTelemetryAppender` → OTLP → OTel Collector → Loki. The appender is wired to the Spring-managed `OpenTelemetry` bean by `OtelLogbackInstaller` (an `ApplicationListener<ApplicationReadyEvent>` in `config/`). Keep `opentelemetry-logback-appender-1.0` as an explicit dep — it is not transitive.
- **Metrics (backend)** — dual path: Prometheus scrape of `/actuator/prometheus` (job `auditflow-backend`) AND OTLP push via `micrometer-registry-otlp` → OTel Collector prometheus exporter (:8889) → Prometheus.
- **Metrics (Python)** — `OTEL_METRICS_EXPORTER=otlp` → OTel Collector → Prometheus exporter.

Config files live under `observability/` (one sub-directory per component). The Grafana
`AuditFlow Overview` dashboard is auto-provisioned from
`observability/grafana/dashboards/auditflow-overview.json`.

**Guardrails for agents editing observability:**
- Do **not** add a manual `OpenTelemetry` `@Bean` (e.g. an `OtelConfig`): a second `OpenTelemetry`
  bean breaks the micrometer-tracing bridge. The SDK bean is created by `spring-boot-opentelemetry`
  auto-configuration. Note `micrometer-tracing-bridge-otel` on its own does **not** create the
  `Tracer` bean — without `spring-boot-starter-opentelemetry` +
  `spring-boot-micrometer-tracing-opentelemetry`, observations emit metrics but no spans.
- **`OtelLogbackInstaller` must stay.** Spring Boot 4.1's `spring-boot-opentelemetry` never calls
  `OpenTelemetryAppender.install()` and never registers `GlobalOpenTelemetry`. Without
  `OtelLogbackInstaller` the Logback appender silently discards every log event — no error, no Loki
  logs. It is NOT a duplicate `OpenTelemetry` bean; it simply wires the existing Spring bean to the
  static appender at application-ready time.
- Keep `opentelemetry-logback-appender-1.0` as an explicit dependency; it is not transitive —
  remove it and the appender class disappears from the classpath.
- The log exporter must target `/v1/logs`, the span exporter `/v1/traces` — the collector
  routes OTLP/HTTP by path. Pointing logs at `/v1/traces` yields HTTP 400
  `proto: wrong wireType = 1 for field TraceId`.
- The OTel Collector listens on **4318 for HTTP** and **4317 for gRPC**. Spring Boot's OTLP config uses HTTP (4318 + `/v1/traces`). Python services use the shared `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318`.
- Do not add `opentelemetry-sdk-extension-autoconfigure` to the backend pom — it conflicts with Spring Boot's `micrometer-tracing-bridge-otel` auto-configuration when OTEL_* env vars are present.

## Where to make common changes

| Goal | Where |
|------|-------|
| Change the public API contract | `auditflow-be/src/main/resources/openapi/openapi-audit-v1.yaml` |
| Add/adjust a pipeline | `auditflow.pipelines` in `application.yml` (or `JAVA_OPTS` in `docker-compose.yml`) |
| Add a condition operator | `auditflow-be/.../service/ConditionEvaluator.java` |
| Change broker/topic/binding | `spring.cloud.stream` in `application.yml` |
| Add a transformer | `auditflow-transformer/transformers/<name>.py` (+ requirements.txt) |
| Add a sink | `auditflow-sink/sinks/<name>.py` (+ requirements.txt) |
| Switch local vs Kubernetes discovery | `transformer.discovery.mode` / `sink.discovery.mode` |
| Change OTel Collector pipelines | `observability/otel-collector/config.yaml` |
| Add/edit Grafana dashboard | `observability/grafana/dashboards/*.json` |
| Change Prometheus scrape targets | `observability/prometheus/prometheus.yml` |
| Change OTLP tracing endpoint | `management.otlp.tracing.endpoint` in `application.yml` |
| Change OTLP logging endpoint | `management.opentelemetry.logging.export.otlp.endpoint` in `application.yml` (Boot 4.1+ path; old `management.otlp.logging.endpoint` is silently ignored) |
| Change OTLP metrics endpoint | `management.otlp.metrics.export.url` in `application.yml` |
