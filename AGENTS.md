# AGENTS.md — Labs64.IO :: AuditFlow

Guidance for agentic AI tools working in this repository. Read this before making changes.

## What this project is

AuditFlow is a scalable, microservices-based audit-logging pipeline. A client publishes an
**audit event** over REST; the event is buffered in a message broker, then processed
asynchronously through one or more configurable **pipelines**. Each pipeline runs the event
through a **transformer** (reshape/enrich) and delivers the result to a **sink** (a storage or
notification destination).

This is a **polyglot monorepo** with three independently deployable services.

## Repository layout

| Path | Service | Stack | Port | Role |
|------|---------|-------|------|------|
| `auditflow-be/` | Backend | Java 25, Spring Boot 4.0.5, Maven | 8080 | REST API, broker publish/consume, pipeline orchestration |
| `auditflow-transformer/` | Transformer | Python 3.13, FastAPI, Uvicorn | 8081 | Dynamically-loaded transform modules |
| `auditflow-sink/` | Sink | Python 3.13, FastAPI, Uvicorn | 8082 | Dynamically-loaded sink/delivery modules |

Root-level orchestration:
- `justfile` — top-level task runner (`just up`, `just up-lite`, `just e2e`, `just logs`, `just down`).
- `docker-compose.yml` — full local stack (3 services + RabbitMQ + Redis + Jaeger) with a pre-wired happy-path pipeline.
- `docker-compose-lite.yml` — trimmed local stack (`just up-lite`): 3 services + RabbitMQ only (no Redis, no Jaeger). Sets `auditflow.idempotency.store=memory` so the dedup guard runs in-process (single-process; not for clustered use).
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
  Micrometer Tracing + OTLP exporter; Prometheus metrics at `/actuator/prometheus`.

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
- `GET /transformers` and `GET /sinks` list available modules (also used as Docker healthchecks).
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
just up        # build backend JAR, build all images, start full stack + RabbitMQ
just e2e       # publish a test event and confirm it flows to the logging sink
just log sink  # tail a single service (backend|transformer|sink|rabbitmq)
just down      # stop containers   |   just clean = also remove volumes
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
