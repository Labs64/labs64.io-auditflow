# AGENTS.md — Labs64.IO :: AuditFlow

Audit-logging pipeline: REST → broker → pipelines → transformer → sink. Polyglot monorepo (Java backend + Python transformer/sink).

## graphify

For codebase questions, run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path` for relationships and `graphify explain` for concepts. After code changes, run `graphify update .`.

## Repository layout

| Path | Service | Stack | Port |
|------|---------|-------|------|
| `auditflow-be/` | Backend | Java 25, Spring Boot 4.1, Maven | 8080 |
| `auditflow-transformer/` | Transformer | Python 3.13, FastAPI | 8081 |
| `auditflow-sink/` | Sink | Python 3.13, FastAPI | 8082 |
| `auditflow-api/` | API Client | Java 17, Maven | n/a |

Root files: `justfile` (task runner), `docker-compose.yml` (local stack), `docker-compose-observability.yml` (observability overlay via `just up obs`).

## Data flow

```
POST /api/v1/audit/publish
    → AuditPublisherService → RabbitMQ topic
    → AuditSubscriberService → AuditService.processAuditEvent()
    → for each enabled pipeline:
        TransformationService → POST http://transformer:8081/transform/{name}
        SinkService → POST http://sink:8082/sink/{name}
```

- Backend is both producer and consumer of the same topic.
- Pipelines are independent — one failing does not stop others.
- Consumer uses dead-letter queue (`autoBindDlq: true`).

## Backend details

- **OpenAPI-first**: canonical spec at `auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml`. Never edit generated Java under `target/`.
- **Pipelines are configuration**, not code: `auditflow.pipelines` in `application.yml`. Each = name, enabled, condition, transformer.name, sink.name, sink.properties.
- **Conditions**: `ConditionEvaluator` with operators `eq, neq, contains, startsWith, endsWith, in, notIn, exists, notExists, regex, gt, gte, lt, lte, eqIgnoreCase`. Field paths support dot notation.
- **Service discovery**: pluggable via `*.discovery.mode` (`local` | `kubernetes`).
- **HTTP to Python**: reactive `WebClient` (5s connect / 10s response), cached per base URL.
- Cross-cutting: `CorrelationIdFilter`, `GlobalExceptionHandler`, `JacksonConfig`, `OpenAPIConfig`.

## Python services (transformer + sink)

Both use the same plugin pattern — keep symmetric when editing one.

- `POST /transform/{id}` or `/sink/{id}` → `importlib.import_module(id)` → call function.
- Transformer: `transform(input_data: dict) -> dict`
- Sink: `process(event_data: dict, properties: dict) -> dict`
- ID validated against `^[a-zA-Z0-9_]+$` — **keep this regex consistent** with Java `TransformationService`/`SinkService`.
- Module resolution: `transformers/` / `sinks/` (shipped), `transformers_bootstrap/` / `sinks_bootstrap/` (mounted at runtime, git-ignored).
- `GET /registry` lists available modules (also Docker healthcheck).
- `telemetry.py` = business telemetry abstraction (no OpenTelemetry imports in app code; auto-instrumentation via `entrypoint.sh` when `OTEL_EXPORTER_OTLP_ENDPOINT` is set).

### Adding a transformer or sink

1. Drop `myname.py` into `transformers/` or `sinks/` implementing the required function.
2. Add Python packages to `requirements.txt` if needed.
3. Reference from pipeline: `transformer.name: myname` / `sink.name: myname`.
4. No backend code change needed — name resolved dynamically.

## Build, run, test

```bash
just up          # build + start stack
just up obs      # stack + OTel + Tempo + Loki + Prometheus + Grafana
just log sink    # tail a service (backend|transformer|sink|rabbitmq)
just down        # stop   |   just clean = also remove volumes
```

Per-service:
```bash
mvn -B clean package -DskipTests --file auditflow-be/pom.xml   # build JAR
mvn -B verify --file auditflow-be/pom.xml                      # test backend
cd auditflow-transformer && just run-local   # uvicorn :8081
cd auditflow-sink        && just run-local   # uvicorn :8082
```

Local URLs: Swagger `:8080/swagger-ui.html`, transformer/sink `:8081/docs` / `:8082/docs`, RabbitMQ `:15673` (guest/guest), Grafana `:3000` (admin/admin).

## Conventions

- **Java 25, Maven 3.6.3+** enforced by maven-enforcer-plugin.
- **`timestamp` is server-assigned** (`readOnly`) — set in controller, never trust client input.
- **Credentials from env vars only** — `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` have no defaults.
- Backend tests: JUnit 5 + Spring Boot Test in `auditflow-be/src/test/java/`.
- Python tests: pytest in `tests/` directories, run via `just test-transformer` / `just test-sink`.
- Logging: SLF4J/Logback with logstash JSON encoder to stdout; `io.labs64` at DEBUG. trace_id/span_id MDC populated by the OTel Java Agent when observability is enabled.

## Where to make common changes

| Goal | Where |
|------|-------|
| API contract | `auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml` |
| Java client | `auditflow-api/src/main/java/io/labs64/auditflow/client/` |
| Pipeline config | `auditflow.pipelines` in `application.yml` or `JAVA_OPTS` in docker-compose |
| Condition operator | `auditflow-be/.../service/ConditionEvaluator.java` |
| Add transformer | `auditflow-transformer/transformers/<name>.py` |
| Add sink | `auditflow-sink/sinks/<name>.py` |
| OTel Collector config | `observability/otel-collector/config.yaml` |
| Grafana dashboard | `observability/grafana/dashboards/*.json` |
| Observability toggle (local) | `docker-compose-observability.yml` (`just up obs`) — env-only, no rebuild |
| Business telemetry | `auditflow-be` src/main/java/io/labs64/audit/telemetry/, transformer/sink `telemetry.py` |
