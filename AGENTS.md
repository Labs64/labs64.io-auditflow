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
| `auditflow-api/` | API contract + client (shared, validated models) | Java 17, Maven | n/a |

Root files: `justfile` (task runner), `docker-compose.yml` (local stack), `docker-compose-observability.yml` (observability overlay via `just up obs`).

## Data flow

```
POST /audit/publish  (direct; via gateway: /auditflow/api/v1/audit/publish — Traefik owns and strips the version prefix)
    → AuditPublisherService → RabbitMQ topic
    → AuditSubscriberService → AuditService.processAuditEvent()
    → for each enabled pipeline:
        TransformationService → POST http://transformer:8081/transform/{name}
        SinkService → POST http://sink:8082/sink/{name}
```

- **AuditFlow is a router/pipeline, NOT a system of record — settled decision, do not reopen.** It has **no database of its own**: no Postgres, no event store. Do not propose or add one. AuditFlow ingests → redacts → routes → delivers; the configured **sinks** (OpenSearch, S3/GCS/Azure Blob, Splunk, Snowflake, Database, …) are the systems of record and own persistence/retention/query. Durability = broker buffering + retry/circuit-breaker + DLQ (`/actuator/dlq/<tenantId>`, replayable or purgeable) + the sink. Redis is dedup-only (idempotency TTL), not storage. "Store audit events in AuditFlow" is out of scope by design — point a pipeline at a durable sink instead.
- Backend is both producer and consumer of the same topic.
- Pipelines are independent — one failing does not stop others.
- Consumer uses dead-letter queue (`autoBindDlq: true`).

## Tenant model (silo isolation)

Multi-tenant by construction: every pipeline belongs to exactly one tenant, and events route only through the pipeline set owned by the event's tenant (`TenantPipelineRegistry` — no global list, no fall-through). Tenantless events belong to the reserved `_platform` pseudo-tenant.

- **Tenant config source** (`tenants.source.mode`): `local-dir` (built-in default — `<tenantId>.yaml` files in a mounted dir, `tenants.source.local-dir.path`, 5s poll; compose mounts `./tenants`) or `gitops-configmap` (explicit; ConfigMaps labelled `auditflow.io/tenant`, fabric8 watch — set by the Helm chart).
- **Onboarding** = add `tenants/<tenantId>.yaml` (local) or a labelled ConfigMap (k8s): `tenantId`, `enabled`, `quota` (rateLimitPerSec/burst), `pipelines` (same shape as before). Picked up live, no restart. Offboarding = remove it; in-flight events then quarantine (`TENANT_UNRESOLVED`).
- **Ingest gate** (`TenantGate`, at `POST /audit/publish`): unprovisioned → `403 TENANT_NOT_PROVISIONED`, disabled → `403 TENANT_DISABLED`, over per-tenant quota → `429 TENANT_RATE_LIMITED` + `Retry-After`. Rate-limit backend: `tenants.ratelimit.backend` = `in-memory` (default, single replica) or `redis` (multi-replica, Lua token bucket; Helm sets it).
- **Legacy `auditflow.pipelines` fails startup** by design — move pipelines into `tenants/_platform.yaml`.
- **Sink credentials**: `${secretRef:<key>}` in sink properties, resolved at delivery from the tenant's own store — `secretRef.resolver` = `env` (default, `AUDITFLOW_TENANT_<ID>_<KEY>`) or `k8s-secret` (Secret `auditflow-tenant-<id>-creds`). Missing key ⇒ retryable failure → DLQ, never a blank or another tenant's credential.
- **Tenant-scoped DLQ**: `/actuator/dlq/<tenantId>` — GET to inspect, POST to replay, **DELETE to purge (irreversible — discards matching messages instead of replaying them)**; only that tenant's messages are touched, and there is no un-scoped DLQ operation.
- **Telemetry**: `auditflow.tenant.events{tenant,provider,outcome}` counter (outcomes: routed/delivered/quarantined/rejected:*) + Grafana `tenant` variable in the overview dashboard.
- Core stays a router: it is read-only on tenant config in every profile and still has no database (see the settled decision above).

## Backend details

- **OpenAPI-first**: canonical spec at `auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml`. Never edit generated Java under `target/`.
- **Pipelines are configuration**, not code — per tenant (see "Tenant model" above). Each = name, enabled, condition, transformer.name, sink.name, sink.properties.
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

## Gateway-edge regression suite (`tests/e2e/`)

Black-box, Robot Framework, gateway-edge only — see `labs64.io-tests/AGENTS.md` for the full
convention (tags, mock-oidc, the `local-k8s-only` kubectl exception). Runs against the local k3d
dev cluster (`labs64.io-helm-charts`'s `just up`), not the plain `docker-compose.yml` stack — that
stack has no gateway in front of it.

| File | Covers |
|---|---|
| `smoke.robot` | Happy path + 400 validation |
| `authz.robot` | Auth/authz scope matrix |
| `tenant_isolation.robot` | Gateway-derived tenant is authoritative; unprovisioned tenants rejected |
| `pipeline_routing.robot` | Non-matching events produce no delivery attempt; fan-out dead-letters exactly once |
| `condition_operators.robot` | Every `ConditionEvaluator` operator branch + match:all/any |
| `redaction.robot` | PII masking before publish (baseline + local-k8s content corroboration) |
| `dlq_replay.robot` | DLQ inspect is non-destructive; replay drains and is tenant-scoped |
| `quota_enforcement.robot` | Per-tenant token bucket: 429 + Retry-After, recovery |
| `secret_ref_resolution.robot` | Resolvable vs. missing `${secretRef:...}` — fail-closed, never blank |

The last six use a dedicated `t_regression`/`t_regression_quota` tenant pair (mock-oidc personas
`auditflow-regression`/`auditflow-regression-quota`, provisioned in
`overrides/auditflow/values.local.yaml` in `labs64.io-helm-charts`) — deliberately aggressive
fixtures (tiny quotas, pipelines that always fail via an unresolvable secretRef) that must never
touch `t_mock`, which every other suite shares. A DLQ entry is per **event**, not per failing
pipeline (`AuditService.dispatchToPipelines` fans one event out to all matching pipelines but
dead-letters the whole event once on redelivery exhaustion) — every conditional probe pipeline is
therefore ANDed with a unique `extra.op` discriminator so at most one probe can ever match a given
test event.

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
| Pipeline config | per-tenant `tenants/<tenantId>.yaml` (local-dir; compose mounts `./tenants`) or labelled ConfigMap (k8s) — legacy global `auditflow.pipelines` fails startup |
| Tenant model (registry/gate/providers) | `auditflow-be/src/main/java/io/labs64/audit/tenant/` |
| Condition operator | `auditflow-be/.../service/ConditionEvaluator.java` |
| Add transformer | `auditflow-transformer/transformers/<name>.py` |
| Add sink | `auditflow-sink/sinks/<name>.py` |
| OTel Collector config | `observability/otel-collector/config.yaml` |
| Grafana dashboard | `observability/grafana/dashboards/*.json` |
| Observability toggle (local) | `docker-compose-observability.yml` (`just up obs`) — env-only, no rebuild |
| Business telemetry | `auditflow-be` src/main/java/io/labs64/audit/telemetry/, transformer/sink `telemetry.py` |
