# Labs64.IO :: AuditFlow

**Audit logging for microservices: publish once, route anywhere, lose nothing.**
*Part of the [Labs64.IO Ecosystem](https://labs64.io): the audit-event router that sits behind your services.*

[![CI](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml/badge.svg)](https://github.com/Labs64/labs64.io-auditflow/actions/workflows/labs64io-ci.yml)
[![Docker Image Version](https://img.shields.io/docker/v/labs64/auditflow?label=Docker&logo=docker&logoColor=white&color=1C90ED)](https://hub.docker.com/r/labs64/auditflow)
[![Artifact Hub](https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/labs64io-helm-charts)](https://artifacthub.io/packages/helm/labs64io-helm-charts/auditflow)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![📖 Documentation](https://img.shields.io/badge/📖-Documentation-AB6543.svg)](https://labs64.io/docs/modules/auditflow/)

<p align="center"><img src="docs/assets/auditflow-hero.svg" alt="AuditFlow: business applications, platform events, legacy systems and security tooling publish events; AuditFlow ingests, redacts, routes and delivers them to databases, log and stream platforms, archives and security platforms." width="880"></p>

[Why AuditFlow?](#why-auditflow) · [Documentation](https://labs64.io/docs/modules/auditflow/) · [Build and test](#build-and-test) · [Ecosystem](#the-labs64io-ecosystem) · [labs64.io](https://labs64.io)

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

The common formats and destinations are in the catalogue and ready to use. Anything unusual (a proprietary format, an internal system, a destination only your organisation runs) is a small module that you own and that inherits everything above. We will tell you plainly which of your needs is which, and the [catalogue](https://labs64.io/docs/modules/auditflow/sinks-and-transformers/) is the place to check. If your case is not covered, [open an issue](https://github.com/Labs64/labs64.io-auditflow/issues) and describe it.

## Documentation

Install, configure and operate AuditFlow in the **[AuditFlow documentation](https://labs64.io/docs/modules/auditflow/)**.

[Quick Start](https://labs64.io/docs/modules/auditflow/#quick-start) · [Configuring pipelines](https://labs64.io/docs/modules/auditflow/configuring-pipelines/) · [Sinks and transformers](https://labs64.io/docs/modules/auditflow/sinks-and-transformers/) · [Deployment](https://labs64.io/docs/modules/auditflow/deployment/)

New to Labs64.IO? The [Quickstart](https://labs64.io/docs/getting-started/quickstart.html) puts the platform on any Kubernetes cluster in about five minutes.

## Build and test

```bash
just up                                          # build and start the whole local stack
just down                                        # stop it (`just clean` also removes the data volumes)
mvn -B verify -pl auditflow-be -am               # test the backend
just test-transformer && just test-sink          # test the Python services
```

Needs Docker, JDK 25 and Maven 3.6.3+. [`DEVELOPERS.md`](DEVELOPERS.md) is the full developer guide. Working on the code with an AI agent? [`AGENTS.md`](AGENTS.md) and [`CLAUDE.md`](CLAUDE.md) describe the layout, conventions and checks.

## The Labs64.IO Ecosystem

This repository is part of the [Labs64.IO Ecosystem](https://labs64.io): open-source building blocks for secure commerce platforms. Every module is documented at [labs64.io/docs](https://labs64.io/docs/).

| Module | What it does |
|---|---|
| [**AuditFlow**](https://github.com/Labs64/labs64.io-auditflow) | Publish once, route anywhere, lose nothing |
| [**Auth Gateway**](https://github.com/Labs64/labs64.io-authproxy) | One authenticated edge for every module |
| [**Payment Gateway**](https://github.com/Labs64/labs64.io-payment-gateway) | One API across multiple payment providers |
| [**Checkout**](https://github.com/Labs64/labs64.io-checkout) | Whitelabel checkout and order workflow |
| [**Customer Portal**](https://github.com/Labs64/labs64.io-customer-portal) | Self-service portal for your customers |

Built on [Commons](https://github.com/Labs64/labs64.io-commons), deployed with [Helm Charts](https://github.com/Labs64/labs64.io-helm-charts), developed in the [Workspace](https://github.com/Labs64/labs64.io-workspace), verified by [Tests](https://github.com/Labs64/labs64.io-tests) and documented in [Docs](https://github.com/Labs64/labs64.io-docs). Also from Labs64: [NetLicensing](https://netlicensing.io) for licensing-as-a-service and [GuideChimp](https://www.labs64.com/guidechimp/) for guided product tours.

## Contributing

We would love your help. Good first areas are new condition operators, transformers and sinks, and documentation or examples. For a new sink or transformer, the [plugin guide](DEVELOPERS.md#adding-a-new-sink) is the place to start. Fork the repository, make your change on a branch, run the checks described in [`AGENTS.md`](AGENTS.md) and open a pull request. Not sure whether an idea fits? Open an issue first.

## License

Open source under the [Apache License 2.0](LICENSE): use it, change it and ship it commercially.

---

<p align="center">
  <a href="https://labs64.io"><img alt="Labs64.IO" src="https://labs64.io/docs/assets/images/labs64-logo.png" width="110"></a><br>
  <sub>Made by the <a href="https://labs64.io">Labs64</a> team and community.</sub>
</p>
