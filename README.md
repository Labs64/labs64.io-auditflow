# Labs64.IO :: AuditFlow

**Audit logging for microservices: publish once, route anywhere, lose nothing.**
*Part of the [Labs64.IO Ecosystem](https://labs64.io): the audit-event router that sits behind your services.*

[![CI](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml/badge.svg)](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml)
[![Docker Image Version](https://img.shields.io/docker/v/labs64/auditflow?label=Docker&logo=docker&logoColor=white&color=1C90ED)](https://hub.docker.com/r/labs64/auditflow)
[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/labs64io-helm-charts)](https://artifacthub.io/packages/helm/labs64io-helm-charts/auditflow)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![📖 Documentation](https://img.shields.io/badge/📖-Documentation-AB6543.svg)](https://labs64.io/docs/modules/auditflow/)

<p align="center"><img src="docs/assets/auditflow-hero.svg" alt="AuditFlow: services such as Keycloak, Kubernetes, GitHub and Stripe publish audit events; AuditFlow ingests, redacts, routes and delivers them to search, archive, SIEM and webhook destinations." width="880"></p>

[**Get started**](#get-started) · [Features](#features) · [Architecture](#architecture) · [AI agents](#using-auditflow-with-ai-agents) · [Ecosystem](#the-labs64io-ecosystem) · [Documentation](https://labs64.io/docs/modules/auditflow/) · [labs64.io](https://labs64.io)

> [!NOTE]
> AuditFlow is a **router, not a system of record**. It has no database of its own by design; your sinks (OpenSearch, S3, Splunk, …) own retention and query.

## Why AuditFlow?

Most teams reinvent audit logging in every service, and the rules for *what* to capture, *where* it goes and *which fields must never be stored* drift apart. AuditFlow moves that into one place:

1. **Your service makes one REST call.** No SDK, no broker client, no per-destination code.
2. **AuditFlow protects and routes it.** Sensitive fields are masked before anything is stored, then your YAML pipelines decide where the event goes.
3. **Every destination gets it, reliably.** Retries over hours, a replayable dead-letter queue and per-tenant isolation mean nothing is silently lost.

It complements OpenTelemetry rather than replacing it: OpenTelemetry tells you whether your system is healthy; AuditFlow tells you who did what, when, and with what outcome.

## Get started

Four ways in. Pick the one that matches what you have; each is complete on its own.

```
 1  install.sh           2  Kubernetes           3  Docker               4  Source
┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐
│ The whole ecosystem │ │ AuditFlow on your   │ │ AuditFlow on your   │ │ Build it, change    │
│ with gateway + auth │ │ cluster and your    │ │ machine, from       │ │ it, contribute      │
│ and a live smoke    │ │ own infrastructure  │ │ published images    │ │ back                │
│ test                │ │                     │ │                     │ │                     │
├─────────────────────┤ ├─────────────────────┤ ├─────────────────────┤ ├─────────────────────┤
│ needs  a cluster,   │ │ needs  a cluster,   │ │ needs  Docker       │ │ needs  JDK 25,      │
│        kubectl,helm │ │        kubectl,helm │ │                     │ │        Maven, Docker│
│ time   ~5 minutes   │ │ time   an afternoon │ │ time   ~2 minutes   │ │ time   first build  │
└─────────────────────┘ └─────────────────────┘ └─────────────────────┘ └─────────────────────┘
 evaluate everything     run it for real         see AuditFlow work      work on the code
```

Not sure? Start with **3** to see AuditFlow in two minutes, or **1** to see it as part of the platform.

### 1. `install.sh`: the quick ecosystem start

One script installs AuditFlow, the Payment Gateway and the authenticating API gateway on any Kubernetes cluster, then sends a real audit event through it to prove it works. No cluster? A throwaway one on your laptop is enough:

```bash
k3d cluster create labs64io-demo -p "8000:80@loadbalancer" --k3s-arg "--disable=traefik@server:0"   # optional

curl -fsSL https://raw.githubusercontent.com/Labs64/labs64.io-helm-charts/master/install.sh -o install.sh
bash install.sh
```

Press Enter three times to take the defaults. You finish with a gateway you can `curl`, interactive API docs, and an audit event you published yourself:

```text
  PASS  Obtained a demo access token from mock-oidc
  PASS  Published a demo audit event (HTTP 200) — first good request succeeded

 Labs64.IO is ready.
   Base URL:  http://localhost:8000
   API docs:  http://localhost:8000/swagger-ui/
```

The same script later runs `status`, `stop`, `start` and `uninstall`. This is an evaluation install with a demo identity provider: keep it on a local or private cluster. Details: [Quickstart](https://labs64.io/docs/getting-started/quickstart/) · [`install.sh`](https://github.com/Labs64/labs64.io-helm-charts/blob/master/install.sh).

### 2. Kubernetes: on your own cluster

Install AuditFlow from its Helm chart and point it at your infrastructure:

```bash
helm repo add labs64io https://labs64.github.io/labs64.io-helm-charts
helm repo update
helm install my-auditflow labs64io/auditflow -n labs64io --create-namespace
```

AuditFlow needs a RabbitMQ broker (self-hosted or managed) and, for more than one replica, a Redis-compatible store. The [`preflight`](https://labs64.io/docs/getting-started/deploy-to-kubernetes/) chart checks both from inside your cluster before you install. Guides: [Deploy to your Kubernetes cluster](https://labs64.io/docs/getting-started/deploy-to-kubernetes/) · [AuditFlow deployment](https://labs64.io/docs/modules/auditflow/deployment/) · [Helm chart on Artifact Hub](https://artifacthub.io/packages/helm/labs64io-helm-charts/auditflow).

### 3. Docker: on your machine

Only Docker and Git are needed. This uses the published images, so there is nothing to build.

**Start the stack** (the first run pulls the images):

```bash
git clone https://github.com/Labs64/labs64.io-auditflow.git && cd labs64.io-auditflow
docker compose -f docker-compose.yml -f docker-compose.images.yml up -d
```

**Send an audit event**, as any service would. It carries a user id we do not want stored:

```bash
curl -i -X POST http://localhost:8080/audit/publish \
  -H "Content-Type: application/json" \
  -d '{"eventType":"user.login","sourceSystem":"keycloak","tenantId":"demo","extra":{"userId":"alice","ip":"203.0.113.7"}}'
```

You get `HTTP/1.1 200` and the event id in the `X-Audit-Event-Id` header. (The backend needs about 30 seconds after `up` to become healthy; if the call is refused, retry.)

**See where it landed.** The demo tenant routes every event to a log and to ClickHouse:

```bash
docker compose exec clickhouse clickhouse-client --user auditflow --password auditflow \
  --query "SELECT event_type, source_system, tenant_id, user_id FROM audit.audit_events ORDER BY timestamp DESC LIMIT 5"
```

```
user.login   keycloak   demo   ***
```

`userId` arrived as `alice` and was stored as `***`: it was masked at ingest, before the event reached the broker or any destination.

**Clean up** with `docker compose down -v`. Prefer a UI? Open <http://localhost:8080/swagger-ui.html> and call the API from the browser.

**What just happened?** The event was accepted only after RabbitMQ confirmed it, redacted, matched against the `demo` tenant's pipelines in [`tenants/demo.yaml`](tenants/demo.yaml), and delivered to two destinations. Open that file and change a destination: it is picked up live, with no restart.

### 4. Source: build and change it

Needs Docker with Compose v2, JDK 25 and Maven 3.6.3+:

```bash
mvn -B clean package -DskipTests -pl auditflow-be -am   # build the backend jar
docker compose up --build -d                            # build and start every service, including Cerbos
```

[`AGENTS.md`](AGENTS.md) describes the layout, commands and tests; [`DEVELOPERS.md`](DEVELOPERS.md) is the full developer guide; the [Contributing guide](https://labs64.io/docs/development/contributing/) covers the workflow.

### Where next?

Everything below is in the [AuditFlow documentation](https://labs64.io/docs/modules/auditflow/):

| I want to… | Read |
|---|---|
| Route events to my own destination (S3, OpenSearch, Splunk, webhook, …) | [Configuring pipelines](https://labs64.io/docs/modules/auditflow/configuring-pipelines/) and [Sinks and transformers](https://labs64.io/docs/modules/auditflow/sinks-and-transformers/) |
| Mask, hash or drop sensitive fields | [PII redaction](https://labs64.io/docs/modules/auditflow/configuring-pipelines/#pii-redaction) |
| See which setup fits my situation | [Usage scenarios](https://labs64.io/docs/modules/auditflow/usage-scenarios/) |
| Compare all the ways to run Labs64.IO | [Choose a deployment path](https://labs64.io/docs/getting-started/choose-a-deployment-path/) |

## Features

- **Pipeline-as-configuration**: destinations and conditions live in YAML; adding or rerouting one needs no rebuild and no restart.
- **Intelligent routing**: field-level conditions (nested paths, AND/OR groups, CIDR, regex, wildcard) plus a dry run that shows which pipelines a sample event would reach.
- **Asynchronous by design**: events are buffered on RabbitMQ before processing, so API latency stays flat under bursts.
- **Multi-destination fan-out**: one publish can reach search, archive, SIEM and webhooks; pipelines fail independently.
- **Redaction at ingest**: sensitive fields are masked, hashed or dropped before they reach the broker.
- **Multi-tenant by construction**: every tenant owns its pipelines, quota and credentials; there is no cross-tenant fall-through.
- **Resilient delivery**: idempotent processing, retry tiers, circuit breaking and a per-tenant dead-letter queue with replay.
- **Pluggable**: sinks and transformers are Python modules loaded at runtime; ClickHouse, OpenSearch, S3/GCS/Azure Blob, Splunk, Datadog, webhook and more ship in the box.
- **Observable**: OpenTelemetry traces, metrics and logs, with a ready-made Grafana dashboard.

The complete list is in the [Features documentation](https://labs64.io/docs/modules/auditflow/features/).

## Architecture

```
  ┌──────────────┐   POST /audit/publish    ┌───────────────────────────────────────────┐
  │ Your service │ ───────────────────────► │ auditflow-be   Java 25 · Spring Boot 4    │
  │ (any stack)  │   redact · validate      │  ingest → router → delivery worker        │
  └──────────────┘                          └───────┬────────────────────────▲──────────┘
                                                    │ publish                │ consume
                                            ┌───────▼────────────────────────┴──────────┐
                                            │ RabbitMQ   delivery queue · delay tiers   │
                                            │            per-tenant DLQ                 │
                                            └───────────────────────────────────────────┘
                                                    │ one delivery per matching pipeline
                                       ┌────────────┴────────────┐
                                       ▼                         ▼
                        ┌─────────────────────────┐  ┌─────────────────────────┐
                        │ auditflow-transformer   │  │ auditflow-sink          │
                        │ Python · FastAPI :8081  │  │ Python · FastAPI :8082  │
                        │ POST /transform/{name}  │  │ POST /sink/{name}       │
                        └─────────────────────────┘  └───────────┬─────────────┘
                                                                 ▼
                                          OpenSearch · S3/Blob · Splunk · Webhook · …
```

| Service | Stack | Port | Role |
|---|---|---|---|
| `auditflow-be` | Java 25, Spring Boot 4 | 8080 | REST API, broker, pipeline orchestration |
| `auditflow-transformer` | Python 3.14, FastAPI | 8081 | Dynamically loaded transform modules |
| `auditflow-sink` | Python 3.14, FastAPI | 8082 | Dynamically loaded sink modules |

Redis (Valkey) is used for deduplication only, never as storage. Design details and invariants are in the [architecture documentation](https://labs64.io/docs/modules/auditflow/#architecture).

## Using AuditFlow with AI agents

AuditFlow gives an agent a durable record of what it did, with one HTTP call per action and no SDK.

- **Emit audit events from an agent.** `POST /audit/publish` (or `/audit/publish/batch`, up to 100 events) with `eventType` (for example `agent.tool.called`), `sourceSystem` (the agent's name) and the details in `extra`. Fields listed in your redaction rules are removed before the event is stored anywhere.
- **Machine-readable contract.** The OpenAPI v1 spec is served at `/v3/api-docs` and lives in [`openapi-audit-v1.yaml`](auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml). It is additive-only within v1, so generated clients and tool definitions stay valid.
- **Errors an agent can act on.** `400` invalid event, `403 TENANT_NOT_PROVISIONED` / `TENANT_DISABLED`, `429` with `Retry-After`, `503` when the broker has not confirmed (retry with the same `eventId`; duplicates are suppressed).
- **Check routing before relying on it.** `POST /actuator/pipelines/<tenantId>/dry-run` shows which pipelines a sample event would reach and why.
- **Working on the code with an agent?** [`AGENTS.md`](AGENTS.md) and [`CLAUDE.md`](CLAUDE.md) describe the layout, conventions and test commands.

## The Labs64.IO Ecosystem

AuditFlow is one module of the [Labs64.IO Ecosystem](https://labs64.io); [every module is documented here](https://labs64.io/docs/).

| Module | What it does | Repository |
|---|---|---|
| **AuditFlow** | Audit-event routing pipeline | [labs64.io-auditflow](https://github.com/Labs64/labs64.io-auditflow) |
| **API Gateway** | Authenticating, policy-enforcing edge for all modules | [labs64.io-authproxy](https://github.com/Labs64/labs64.io-authproxy) |
| **Payment Gateway** | One API over multiple payment providers | [labs64.io-payment-gateway](https://github.com/Labs64/labs64.io-payment-gateway) |
| **Checkout** | Embeddable, brandable checkout | [labs64.io-checkout](https://github.com/Labs64/labs64.io-checkout) |
| **Customer Portal** | Customer self-service UI | [labs64.io-customer-portal](https://github.com/Labs64/labs64.io-customer-portal) |
| **Commons** | Shared Java and Python libraries | [labs64.io-commons](https://github.com/Labs64/labs64.io-commons) |
| **Helm Charts** | Kubernetes deployment for every module | [labs64.io-helm-charts](https://github.com/Labs64/labs64.io-helm-charts) |
| **Workspace** | Dev container and multi-repo tooling | [labs64.io-workspace](https://github.com/Labs64/labs64.io-workspace) |
| **Tests** | Cross-module regression suite | [labs64.io-tests](https://github.com/Labs64/labs64.io-tests) |
| **Docs** | Published documentation | [labs64.io-docs](https://github.com/Labs64/labs64.io-docs) |

## Contributing

Contributions of all sizes are welcome. Fork, branch, run the tests described in [`AGENTS.md`](AGENTS.md), and open a pull request; for a new sink or transformer, start with the [plugin guide](DEVELOPERS.md#adding-a-new-sink). If you are unsure whether something fits, open an issue first.

## License

The core of the *Labs64.IO Ecosystem* is entirely open source and free forever. Community modules are licensed under [Apache License 2.0](LICENSE).

---

<p align="center">
  <a href="https://labs64.io"><img alt="Labs64.IO" src="https://labs64.io/docs/assets/images/labs64-logo.png" width="110"></a><br>
  <sub>Made by the <a href="https://labs64.io">Labs64</a> team and community.</sub>
</p>
