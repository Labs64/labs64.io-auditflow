# Labs64.IO :: AuditFlow

**Audit logging for microservices: publish once, route anywhere, lose nothing.**
*Part of the [Labs64.IO Ecosystem](https://labs64.io): the audit-event router that sits behind your services.*

[![CI](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml/badge.svg)](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml)
[![Docker Image Version](https://img.shields.io/docker/v/labs64/auditflow?label=Docker&logo=docker&logoColor=white&color=1C90ED)](https://hub.docker.com/r/labs64/auditflow)
[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/labs64io-helm-charts)](https://artifacthub.io/packages/helm/labs64io-helm-charts/auditflow)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![📖 Documentation](https://img.shields.io/badge/📖-Documentation-AB6543.svg)](https://labs64.io/docs/modules/auditflow/)

<p align="center"><img src="docs/assets/auditflow-hero.svg" alt="AuditFlow: business applications, platform events, legacy systems and security tooling publish events; AuditFlow ingests, redacts, routes and delivers them to databases, log and stream platforms, archives and security platforms." width="880"></p>

[**Get started**](#get-started) · [Built for enterprise landscapes](#built-for-enterprise-landscapes) · [Features](#features) · [Architecture](#architecture) · [AI agents](#using-auditflow-with-ai-agents) · [Ecosystem](#the-labs64io-ecosystem) · [Documentation](https://labs64.io/docs/modules/auditflow/) · [labs64.io](https://labs64.io)

> [!NOTE]
> AuditFlow is a **router, not a system of record**. It has no database of its own by design; your destinations own retention and query.

## Why AuditFlow?

Every team ends up building audit logging more than once. One service writes to a table, another calls a logging API, a third does nothing until an auditor asks. The rules for *what* to capture, *where* it goes and *which fields must never be stored* are copied from service to service and quietly drift apart, and you find out at the worst possible moment.

1. **Your service makes one REST call.** No SDK, no broker client, no per-destination code to maintain.
2. **AuditFlow protects and routes the event.** Sensitive fields are masked before anything is stored, then your YAML pipelines decide where it goes.
3. **Every destination gets it, reliably.** Retries over hours, a replayable dead-letter queue and per-tenant isolation mean an outage at a sink does not become a hole in your audit trail.

AuditFlow complements OpenTelemetry rather than replacing it: OpenTelemetry tells you whether your system is healthy; AuditFlow tells you who did what, when, and with what outcome.

**A good fit if**

- You must answer "who did what, when, from where" across several services, and each service does it differently today.
- Records and events have to move between systems that speak different formats, and you are tired of rebuilding that glue for every consumer.
- Data has to reach more than one place, and each place has its own rules, without hand-wiring every route.
- Personal or confidential data must never reach a downstream store, and redaction at the door is a hard requirement.
- You can not afford silent loss: a downstream outage must delay delivery, not drop evidence.
- Business units or customers need their own routing and credentials, isolated from each other.

**Probably not what you need if**

- You only need traces, metrics or infrastructure logs: reach for OpenTelemetry, Prometheus or your observability platform.
- You want to intercept raw HTTP traffic at a proxy or sidecar: AuditFlow is event-driven and your services decide what to publish.
- You want AuditFlow itself to be the queryable store: it deliberately has no database; your sinks own retention and query.

## Built for enterprise landscapes

Enterprise integration is rarely one clean pipe. It is many systems that speak many formats, many destinations with their own rules, business units that must stay apart, regulators who ask questions later, and a landscape that keeps changing. AuditFlow is designed for exactly that mess.

| Your landscape| AuditFlow's answer |
|---|---|
| **Many systems, many formats** | One simple contract in. Transformers normalize each source once, and an open event vocabulary lets every system keep its own terms. |
| **Many destinations, each different** | Fan-out pipelines deliver one event to as many destinations as you need, each failing and recovering on its own. A destination you do not see in the catalogue is a small module, not a project. |
| **Business units that must stay apart** | Tenants are isolated by construction: their own pipelines, quotas and credentials, and no path from one tenant's events into another's destinations. |
| **Confidential and regulated data** | Fields are masked, hashed or dropped at the door, before anything is buffered or stored. Evidence can be archived in a tamper-evident form. |
| **Operations that cannot fail silently** | Delivery is confirmed, retried over hours and parked in a replayable queue when it cannot complete. A downstream outage becomes a delay, not a gap. |
| **A landscape that keeps changing** | Routing is configuration, not code: change it live, test it with a dry run, and add formats or destinations without rebuilding or redeploying anything else. |
| **Run it your way** | Containers and Kubernetes-native, on any cloud or on premises, with telemetry your operations team already understands. |

### Recognise yourself?

- **Integration and platform teams** who are handed "can you just get X into Y?" every month, and want each answer to come with retries, redaction and an owner.
- **Security and compliance** who need a dependable trail, delivered to the places evidence has to live, with sensitive fields handled once and correctly.
- **Data and application owners** who need records to flow between systems and formats without every consumer maintaining its own mapping.
- **Architects** who want one governed integration pattern instead of a growing collection of scripts.

### What is built in, and what is yours

The common formats and destinations are in the catalogue and ready to use. Anything unusual (a proprietary format, an internal system, a destination only your organisation runs) is a small module that you own and that inherits everything above. We will tell you plainly which of your needs is which, and the [catalogue](https://labs64.io/docs/modules/auditflow/sinks-and-transformers/) is the place to check. If your case is not covered, [open an issue](https://github.com/Labs64/labs64.io-auditflow/issues) and describe it.

## Get started

Four ways in. Pick the one that matches what you have; each is complete on its own.

```
 1  install.sh             2  Kubernetes             3  Docker                 4  Source
┌───────────────────────┐ ┌───────────────────────┐ ┌───────────────────────┐ ┌───────────────────────┐
│ The whole ecosystem   │ │ AuditFlow on your     │ │ AuditFlow on your     │ │ Build it, change      │
│ with gateway + auth   │ │ cluster and your      │ │ machine, from         │ │ it, contribute        │
│ and a live smoke      │ │ own infrastructure    │ │ published images      │ │ back                  │
│ test                  │ │                       │ │                       │ │                       │
├───────────────────────┤ ├───────────────────────┤ ├───────────────────────┤ ├───────────────────────┤
│ needs  a cluster,     │ │ needs  a cluster,     │ │ needs  Docker         │ │ needs  JDK 25, Maven, │
│        kubectl, helm  │ │        kubectl, helm  │ │                       │ │        Docker         │
│ time   ~5 minutes     │ │ time   an afternoon   │ │ time   ~2 minutes     │ │ time   first build    │
└───────────────────────┘ └───────────────────────┘ └───────────────────────┘ └───────────────────────┘
 evaluate everything       run it for real           see AuditFlow work        work on the code
```

Not sure? Start with **3** to see AuditFlow in two minutes, or **1** to see it as part of the platform.

### 1. `install.sh`: the quick ecosystem start

One script installs AuditFlow, the Payment Gateway and the authenticating API gateway on any Kubernetes cluster, then sends a real audit event through it to prove it works.

```bash
# No cluster yet? A throwaway one needs only Docker and k3d:
k3d cluster create labs64io-demo -p "8000:80@loadbalancer" --k3s-arg "--disable=traefik@server:0"

curl -fsSL https://raw.githubusercontent.com/Labs64/labs64.io-helm-charts/master/install.sh -o install.sh
bash install.sh
```

Press Enter three times to take the defaults. A few minutes later you should see:

```text
  PASS  Obtained a demo access token from mock-oidc
  PASS  Published a demo audit event (HTTP 200) — first good request succeeded

 Labs64.IO is ready.
   Base URL:  http://localhost:8000
   API docs:  http://localhost:8000/swagger-ui/
```

Along the way you watch AuditFlow accept a real audit event through the gateway, with authentication in front of it. The same script later runs `status`, `stop`, `start` and `uninstall`. This is an evaluation install with a demo identity provider: keep it on a local or private cluster. Details: [Quickstart](https://labs64.io/docs/getting-started/quickstart.html) · [`install.sh`](https://github.com/Labs64/labs64.io-helm-charts/blob/master/install.sh).

### 2. Kubernetes: on your own cluster

You have a cluster and a RabbitMQ broker (self-hosted or managed). Install AuditFlow from its chart and tell it where the broker is:

```bash
helm repo add labs64io https://labs64.github.io/labs64.io-helm-charts
helm repo update

helm install my-auditflow labs64io/auditflow -n labs64io --create-namespace \
  --set global.rabbitmq.host=rabbitmq.mycompany.internal
```

Broker credentials go in a Kubernetes Secret (see the chart's values), and a Redis-compatible store is recommended once you run more than one replica. The `preflight` chart can check the broker from inside your cluster before you install. Guides: [Deploy to your Kubernetes cluster](https://labs64.io/docs/getting-started/deploy-to-kubernetes.html) · [AuditFlow deployment](https://labs64.io/docs/modules/auditflow/deployment/) · [Helm chart on Artifact Hub](https://artifacthub.io/packages/helm/labs64io-helm-charts/auditflow).

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
  --query "SELECT event_type, source_system, tenant_id, user_id FROM audit.audit_events ORDER BY event_time DESC LIMIT 5"
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

[`AGENTS.md`](AGENTS.md) describes the layout, commands and tests; [`DEVELOPERS.md`](DEVELOPERS.md) is the full developer guide; the [Contributing guide](https://labs64.io/docs/development/contributing.html) covers the workflow.

### Where next?

Everything below is in the [AuditFlow documentation](https://labs64.io/docs/modules/auditflow/):

| I want to… | Read |
|---|---|
| Route events to my own destinations | [Configuring pipelines](https://labs64.io/docs/modules/auditflow/configuring-pipelines/) and [Sinks and transformers](https://labs64.io/docs/modules/auditflow/sinks-and-transformers/) |
| Mask, hash or drop sensitive fields | [PII redaction](https://labs64.io/docs/modules/auditflow/configuring-pipelines/#pii-redaction) |
| See which setup fits my situation | [Usage scenarios](https://labs64.io/docs/modules/auditflow/usage-scenarios/) |
| Compare all the ways to run Labs64.IO | [Choose a deployment path](https://labs64.io/docs/getting-started/choose-a-deployment-path.html) |

Something not working? [Troubleshooting your first run](https://labs64.io/docs/getting-started/troubleshooting.html) covers the usual suspects, and you are always welcome to [open an issue](https://github.com/Labs64/labs64.io-auditflow/issues).

## Features

- **Pipeline-as-configuration**: destinations and conditions live in YAML; adding or rerouting one needs no rebuild and no restart.
- **Intelligent routing**: field-level conditions (nested paths, AND/OR groups, CIDR, regex, wildcard) plus a dry run that shows which pipelines a sample event would reach.
- **Asynchronous by design**: events are buffered on RabbitMQ before processing, so API latency stays flat under bursts.
- **Multi-destination fan-out**: one publish can reach search, archive, SIEM and webhooks; pipelines fail independently.
- **Redaction at ingest**: sensitive fields are masked, hashed or dropped before they reach the broker.
- **Multi-tenant by construction**: every tenant owns its pipelines, quota and credentials; there is no cross-tenant fall-through.
- **Resilient delivery**: idempotent processing, retry tiers, circuit breaking and a per-tenant dead-letter queue with replay.
- **Pluggable**: a catalogue of common destinations ships ready (search, object storage, databases, log and security platforms, webhooks), and anything else is a small Python module loaded at runtime.
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
                                          databases · log platforms · archives · …
```

| Service | Stack | Port | Role |
|---|---|---|---|
| `auditflow-be` | Java 25, Spring Boot 4 | 8080 | REST API, broker, pipeline orchestration |
| `auditflow-transformer` | Python 3.14, FastAPI | 8081 | Dynamically loaded transform modules |
| `auditflow-sink` | Python 3.14, FastAPI | 8082 | Dynamically loaded sink modules |

Redis (Valkey) is used for deduplication only, never as storage. Design details and invariants are in the [architecture documentation](https://labs64.io/docs/modules/auditflow/#architecture).

## AuditFlow and OpenTelemetry

They look alike (both capture events from your services and ship them somewhere) but they answer different questions for different people.

| | OpenTelemetry / observability | AuditFlow |
|---|---|---|
| **Question answered** | *Is my system healthy?* Latency, errors, throughput | *Who did what, when, with what outcome?* Intentional actions by users and services |
| **Main reader** | SREs and platform engineers | Compliance, security, legal and auditors |
| **Data character** | High volume, sampled, short retention | Every event matters, lossless, long retention |
| **When it goes wrong** | A dropped span is a gap in a graph | A dropped audit event is a compliance failure |

Both belong in a production system. AuditFlow is built around the assumption that **every event matters**, which is why it has idempotency, a dead-letter queue, retries and ingest-time redaction.

## Using AuditFlow with AI agents

AuditFlow gives an agent a durable record of what it did, with one HTTP call per action and no SDK.

- **Emit audit events from an agent.** `POST /audit/publish` (or `/audit/publish/batch`, up to 100 events) with `eventType` (for example `agent.tool.called`), `sourceSystem` (the agent's name) and the details in `extra`. Fields listed in your redaction rules are removed before the event is stored anywhere.
- **Machine-readable contract.** The OpenAPI v1 spec is served at `/v3/api-docs` and lives in [`openapi-audit-v1.yaml`](auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml). It is additive-only within v1, so generated clients and tool definitions stay valid.
- **Errors an agent can act on.** `400` invalid event, `403 TENANT_NOT_PROVISIONED` / `TENANT_DISABLED`, `429` with `Retry-After`, `503` when the broker has not confirmed (retry with the same `eventId`; duplicates are suppressed).
- **Check routing before relying on it.** `POST /actuator/pipelines/<tenantId>/dry-run` shows which pipelines a sample event would reach and why.
- **Working on the code with an agent?** [`AGENTS.md`](AGENTS.md) and [`CLAUDE.md`](CLAUDE.md) describe the layout, conventions and test commands.

## The Labs64.IO Ecosystem

AuditFlow is one module of the [Labs64.IO Ecosystem](https://labs64.io); [every module is documented here](https://labs64.io/docs/).

| Module | What it does | Link |
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
| **NetLicensing** | Licensing-as-a-Service: license and entitlement management | [netlicensing.io](https://netlicensing.io) |
| **GuideChimp** | Interactive guided product tours | [labs64.com/guidechimp](https://www.labs64.com/guidechimp/) |

## Contributing

We would love your help. Good first areas are new condition operators, transformers and sinks, and documentation or examples. Fork the repository, make your change on a branch, run the checks described in [`AGENTS.md`](AGENTS.md) and open a pull request. For a new sink or transformer, the [plugin guide](DEVELOPERS.md#adding-a-new-sink) is the place to start. Not sure whether an idea fits? Open an issue first and we will talk it through; that is a perfectly good way to start.

## License

Everything in this repository is open source under the [Apache License 2.0](LICENSE): you can use it, change it and ship it commercially. The core of the *Labs64.IO Ecosystem* is free forever; community modules are Apache-2.0 licensed.

---

<p align="center">
  <a href="https://labs64.io"><img alt="Labs64.IO" src="https://labs64.io/docs/assets/images/labs64-logo.png" width="110"></a><br>
  <sub>Made by the <a href="https://labs64.io">Labs64</a> team and community.</sub>
</p>
