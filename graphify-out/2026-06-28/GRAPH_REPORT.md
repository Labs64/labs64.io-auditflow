# Graph Report - labs64.io-auditflow  (2026-06-27)

## Corpus Check
- 126 files · ~52,049 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1164 nodes · 2502 edges · 93 communities (68 shown, 25 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 286 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `1ac2defd`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Pipeline Config & Conditions|Pipeline Config & Conditions]]
- [[_COMMUNITY_HTTP Retry & WebClient|HTTP Retry & WebClient]]
- [[_COMMUNITY_Condition Evaluator Tests|Condition Evaluator Tests]]
- [[_COMMUNITY_Audit Event API & Controller|Audit Event API & Controller]]
- [[_COMMUNITY_Idempotency & Redis|Idempotency & Redis]]
- [[_COMMUNITY_Sink Plugin Registry|Sink Plugin Registry]]
- [[_COMMUNITY_Exception Handling|Exception Handling]]
- [[_COMMUNITY_Sink SDK & Base Classes|Sink SDK & Base Classes]]
- [[_COMMUNITY_Global Exception & Transformer Registry|Global Exception & Transformer Registry]]
- [[_COMMUNITY_Transformation Service Tests|Transformation Service Tests]]
- [[_COMMUNITY_Service Discovery|Service Discovery]]
- [[_COMMUNITY_OTel & Observability|OTel & Observability]]
- [[_COMMUNITY_Circuit Breaker & Rate Limiting|Circuit Breaker & Rate Limiting]]
- [[_COMMUNITY_RabbitMQ Streaming|RabbitMQ Streaming]]
- [[_COMMUNITY_Sink Plugins|Sink Plugins]]
- [[_COMMUNITY_Transformer Plugins|Transformer Plugins]]
- [[_COMMUNITY_Docker & Infra Config|Docker & Infra Config]]
- [[_COMMUNITY_CICD Workflows|CI/CD Workflows]]
- [[_COMMUNITY_Kubernetes Discovery|Kubernetes Discovery]]
- [[_COMMUNITY_Audit Service & Pipeline|Audit Service & Pipeline]]
- [[_COMMUNITY_Loki & OpenSearch Sinks|Loki & OpenSearch Sinks]]
- [[_COMMUNITY_Cloud Storage Sinks|Cloud Storage Sinks]]
- [[_COMMUNITY_Grafana & Prometheus Config|Grafana & Prometheus Config]]
- [[_COMMUNITY_NetLicensing Sink|NetLicensing Sink]]
- [[_COMMUNITY_Health & Readiness|Health & Readiness]]
- [[_COMMUNITY_DLQ & Poison Messages|DLQ & Poison Messages]]
- [[_COMMUNITY_Graceful Shutdown|Graceful Shutdown]]
- [[_COMMUNITY_Tracing & OTLP Setup|Tracing & OTLP Setup]]
- [[_COMMUNITY_Requirements & Dependencies|Requirements & Dependencies]]
- [[_COMMUNITY_Audit Publisher|Audit Publisher]]
- [[_COMMUNITY_Webhook & Syslog Sinks|Webhook & Syslog Sinks]]
- [[_COMMUNITY_Redaction Service|Redaction Service]]
- [[_COMMUNITY_Consumer Health|Consumer Health]]
- [[_COMMUNITY_Tempo & Loki Config|Tempo & Loki Config]]
- [[_COMMUNITY_YAML Config & Application Props|YAML Config & Application Props]]
- [[_COMMUNITY_Test Utilities|Test Utilities]]
- [[_COMMUNITY_Audit Subscriber|Audit Subscriber]]
- [[_COMMUNITY_Community 38|Community 38]]
- [[_COMMUNITY_Community 39|Community 39]]
- [[_COMMUNITY_Community 40|Community 40]]
- [[_COMMUNITY_Community 41|Community 41]]
- [[_COMMUNITY_Community 42|Community 42]]
- [[_COMMUNITY_Community 43|Community 43]]
- [[_COMMUNITY_Community 44|Community 44]]
- [[_COMMUNITY_Community 45|Community 45]]
- [[_COMMUNITY_Community 46|Community 46]]
- [[_COMMUNITY_Community 47|Community 47]]
- [[_COMMUNITY_Community 48|Community 48]]
- [[_COMMUNITY_Community 49|Community 49]]
- [[_COMMUNITY_Community 50|Community 50]]
- [[_COMMUNITY_Community 51|Community 51]]
- [[_COMMUNITY_Community 52|Community 52]]
- [[_COMMUNITY_Community 53|Community 53]]
- [[_COMMUNITY_Community 54|Community 54]]
- [[_COMMUNITY_Community 55|Community 55]]
- [[_COMMUNITY_Community 56|Community 56]]
- [[_COMMUNITY_Community 57|Community 57]]
- [[_COMMUNITY_Community 58|Community 58]]
- [[_COMMUNITY_Community 59|Community 59]]
- [[_COMMUNITY_Community 60|Community 60]]
- [[_COMMUNITY_Community 61|Community 61]]
- [[_COMMUNITY_Community 62|Community 62]]
- [[_COMMUNITY_Community 63|Community 63]]
- [[_COMMUNITY_Community 64|Community 64]]
- [[_COMMUNITY_Community 65|Community 65]]
- [[_COMMUNITY_Community 66|Community 66]]
- [[_COMMUNITY_Community 67|Community 67]]
- [[_COMMUNITY_Community 68|Community 68]]
- [[_COMMUNITY_Community 69|Community 69]]
- [[_COMMUNITY_Community 70|Community 70]]
- [[_COMMUNITY_Community 71|Community 71]]
- [[_COMMUNITY_Community 72|Community 72]]
- [[_COMMUNITY_Community 73|Community 73]]
- [[_COMMUNITY_Community 74|Community 74]]
- [[_COMMUNITY_Community 75|Community 75]]
- [[_COMMUNITY_Community 76|Community 76]]
- [[_COMMUNITY_Community 77|Community 77]]
- [[_COMMUNITY_Community 78|Community 78]]
- [[_COMMUNITY_Community 79|Community 79]]
- [[_COMMUNITY_Community 80|Community 80]]
- [[_COMMUNITY_Community 81|Community 81]]
- [[_COMMUNITY_Community 82|Community 82]]
- [[_COMMUNITY_Community 83|Community 83]]
- [[_COMMUNITY_Community 84|Community 84]]
- [[_COMMUNITY_Community 85|Community 85]]
- [[_COMMUNITY_Community 86|Community 86]]
- [[_COMMUNITY_Community 87|Community 87]]
- [[_COMMUNITY_Community 88|Community 88]]
- [[_COMMUNITY_Community 89|Community 89]]
- [[_COMMUNITY_Community 90|Community 90]]
- [[_COMMUNITY_Community 91|Community 91]]
- [[_COMMUNITY_Community 92|Community 92]]

## God Nodes (most connected - your core abstractions)
1. `ConditionEvaluatorTest` - 30 edges
2. `PipelineProperties` - 24 edges
3. `CircuitBreakerProperties` - 20 edges
4. `AuditServiceTest` - 20 edges
5. `ConsumerHealthIndicator` - 19 edges
6. `ConditionProperties` - 17 edges
7. `HttpRetryProperties` - 17 edges
8. `Builder` - 16 edges
9. `Features` - 16 edges
10. `SinkProperties` - 15 edges

## Surprising Connections (you probably didn't know these)
- `DefaultAuditFlowClient` --implements--> `AuditFlowClient`  [EXTRACTED]
  auditflow-api/src/main/java/io/labs64/auditflow/client/DefaultAuditFlowClient.java → auditflow-api/src/main/java/io/labs64/auditflow/client/AuditFlowClient.java
- `KubernetesSinkDiscovery` --implements--> `SinkDiscovery`  [EXTRACTED]
  auditflow-be/src/main/java/io/labs64/audit/service/KubernetesSinkDiscovery.java → auditflow-be/src/main/java/io/labs64/audit/service/SinkDiscovery.java
- `AuditFlowTransportException` --inherits--> `AuditFlowException`  [EXTRACTED]
  auditflow-api/src/main/java/io/labs64/auditflow/client/exception/AuditFlowTransportException.java → auditflow-api/src/main/java/io/labs64/auditflow/client/exception/AuditFlowException.java
- `InMemoryIdempotencyService` --implements--> `IdempotencyService`  [EXTRACTED]
  auditflow-be/src/main/java/io/labs64/audit/service/InMemoryIdempotencyService.java → auditflow-be/src/main/java/io/labs64/audit/service/IdempotencyService.java
- `RedisIdempotencyService` --implements--> `IdempotencyService`  [EXTRACTED]
  auditflow-be/src/main/java/io/labs64/audit/service/RedisIdempotencyService.java → auditflow-be/src/main/java/io/labs64/audit/service/IdempotencyService.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Stability Improvements: Circuit Breaker, Rate Limiter, Graceful Shutdown, DLQ** — config_circuitbreakerconfig, config_circuitbreakerproperties, config_circuitbreakermetricsexporter, config_pipelineraterlimiterregistry, config_ratelimitproperties, config_consumerhealthindicator, config_gracefulshutdownmanager, controller_dlqendpoint [INFERRED 0.95]
- **AWS Sink Implementations (S3, CloudWatch)** — sinks_aws_s3_sink, sinks_aws_cloudwatch_sink [EXTRACTED 1.00]
- **Audit Pipeline Orchestration (AuditService, TransformationService, SinkService)** — service_auditservice, service_sinkservice, concept_pipeline_idempotency, concept_fallback_sink, concept_multi_stage_transformer, concept_poison_vs_retryable [EXTRACTED 1.00]
- **All Sink Plugin Implementations (process function contract)** — sinks_azure_blob_sink_process, sinks_gcs_sink_process, sinks_loki_sink_process, sinks_netlicensing_sink_process, sinks_opensearch_sink_process, sinks_syslog_sink_process, sinks_webhook_sink_process [EXTRACTED 1.00]
- **All Transformer Plugin Implementations (transform function contract)** — transformers_audit_loki_transform, transformers_audit_opensearch_transform, transformers_zero_transform [EXTRACTED 1.00]
- **CI Pipeline: all three service build jobs** — workflows_labs64io_ci_build_backend, workflows_labs64io_ci_build_transformer, workflows_labs64io_ci_build_sink [EXTRACTED 1.00]
- **Sink Service Test Suite** — tests_test_sink_app_test_list_sinks_includes_logging_sink, tests_test_sink_app_test_logging_sink_processes_event, tests_test_sink_app_test_unknown_sink_returns_404, tests_test_sink_app_test_malformed_sink_id_returns_400, tests_test_sink_app_test_registry_exposes_logging_sink_metadata, tests_test_sink_app_test_registry_reload, tests_test_sink_app_test_new_catalogue_sinks_are_registered, tests_test_sink_app_test_datadog_sink_missing_api_key_errors [EXTRACTED 1.00]
- **Transformer Service Test Suite** — tests_test_transformer_app_test_list_transformers_includes_zero, tests_test_transformer_app_test_zero_transform_is_passthrough, tests_test_transformer_app_test_unknown_transformer_returns_404, tests_test_transformer_app_test_malformed_transformer_id_returns_400, tests_test_transformer_app_test_registry_exposes_zero_metadata, tests_test_transformer_app_test_registry_reload [EXTRACTED 1.00]
- **Transformer Health/Readiness/Liveness Endpoints** — transformer_health_health, transformer_health_readiness, transformer_health_liveness, transformer_health_service_info, transformer_health_set_ready [EXTRACTED 1.00]
- **Observability Stack: OTel Collector + Tempo + Loki + Prometheus + Grafana** — otel_collector_config, tempo_yaml, loki_yaml, prometheus_yml, grafana_datasources_yaml, grafana_dashboards_yaml, docker_compose_observability_yml [EXTRACTED 1.00]
- **Full Stack Compose Files** — docker_compose_yml, docker_compose_lite_yml, docker_compose_observability_yml, docker_compose_verify_yml [EXTRACTED 1.00]
- **CI/CD: Test + Build + Push Pipeline** — workflows_docker_publish, sink_requirements_dev_txt, transformer_requirements_dev_txt, resources_application_yml [EXTRACTED 1.00]
- **Python Services Runtime Dependencies** — sink_requirements_txt, transformer_requirements_txt [EXTRACTED 1.00]

## Communities (93 total, 25 thin omitted)

### Community 0 - "Pipeline Config & Conditions"
Cohesion: 0.16
Nodes (3): AuditFlowClient, Builder, AuditFlowClientBuilderTest

### Community 1 - "HTTP Retry & WebClient"
Cohesion: 0.16
Nodes (9): Builder, HttpRetryProperties, Counter, MeterRegistry, ReactiveCircuitBreakerFactory, Retry, HttpRetrySupport, QuarantineService (+1 more)

### Community 2 - "Condition Evaluator Tests"
Cohesion: 0.06
Nodes (16): BeforeEach, ConditionProperties, CsvSource, DisplayName, ParameterizedTest, AuditServiceTest, ConditionEvaluatorTest, DeliveryErrorsTest (+8 more)

### Community 4 - "Idempotency & Redis"
Cohesion: 0.06
Nodes (20): AuditApplication, ApiClient, Reactor Context Propagation for Tracing/MDC, ConditionRule, CorrelationIdFilter, DlqEndpoint, FilterChain, HttpServletRequest (+12 more)

### Community 5 - "Sink Plugin Registry"
Cohesion: 0.17
Nodes (21): Unit tests for the plugin registry allow-list / hardening (P1-4)., _registry(), test_details_captures_optional_metadata(), test_discovers_and_resolves_valid_plugin(), test_import_error_is_excluded_without_crashing(), test_malformed_id_raises_not_found(), test_missing_entry_point_is_excluded(), test_reload_picks_up_new_plugin() (+13 more)

### Community 6 - "Exception Handling"
Cohesion: 0.13
Nodes (8): Poison vs Retryable Failure Classification, AuditFlowException, AuditFlowTransportException, PoisonDeliveryException, RetryableDeliveryException, RuntimeException, DeliveryErrors, Throwable

### Community 7 - "Sink SDK & Base Classes"
Cohesion: 0.07
Nodes (33): ABC, Any, AuditEventApi, BaseSink, BaseTransformer, Optional SDK for AuditFlow plugins.  Plugins stay simple: a transformer module d, Optional base class for transformer plugins., Reshape/enrich the event and return the new event dict. (+25 more)

### Community 8 - "Global Exception & Transformer Registry"
Cohesion: 0.05
Nodes (25): PluginNotFoundError, PluginRegistry, Plugin registry for dynamically-loaded modules (transformers / sinks).  Hardenin, List the allow-listed plugins (id, type, path)., Full registry view including optional SDK metadata (version, description, proper, Map of discovered-but-excluded plugin id -> {kind, error}., The requested plugin id is not on the discovered allow-list., Discovers, validates, and resolves plugin modules from a fixed set of directorie (+17 more)

### Community 9 - "Transformation Service Tests"
Cohesion: 0.27
Nodes (5): AuditEvent, DefaultAuditFlowClient, PublishResult, CompletableFuture, HttpRequest

### Community 10 - "Service Discovery"
Cohesion: 0.13
Nodes (5): Action, RedactionProperties, Rule, RedactionService, RedactionServiceTest

### Community 11 - "OTel & Observability"
Cohesion: 0.19
Nodes (4): AuditFlowConfiguration, PipelineProperties, TransformerProperties, List

### Community 12 - "Circuit Breaker & Rate Limiting"
Cohesion: 0.20
Nodes (5): KubernetesClient, KubernetesDiscoveryService, KubernetesSinkDiscovery, LocalDiscoveryService, TransformerDiscovery

### Community 14 - "Sink Plugins"
Cohesion: 0.22
Nodes (3): RetryPolicy, RetryPolicyTest, IllegalArgumentException

### Community 15 - "Transformer Plugins"
Cohesion: 0.18
Nodes (3): TokenProvider, TokenProviderTest, Supplier

### Community 17 - "CI/CD Workflows"
Cohesion: 0.19
Nodes (4): SinkProperties, JsonNode, PipelineOutcome, AuditService

### Community 18 - "Kubernetes Discovery"
Cohesion: 0.28
Nodes (3): PipelineRateLimiterRegistry, RateLimiter, RateLimiterRegistry

### Community 19 - "Audit Service & Pipeline"
Cohesion: 0.18
Nodes (6): Bean, JacksonConfig, Consumer, ObjectMapper, StreamBridge, AuditSubscriberService

### Community 20 - "Loki & OpenSearch Sinks"
Cohesion: 0.05
Nodes (41): Adding your own sink or transformer, Architecture, Asynchronous, decoupled processing, Broker-agnostic transport, Built-in Sinks and Transformers, Centralised audit hub across microservices, Cloud-managed infrastructure, Compliance audit trail (GDPR, SOC 2, ISO 27001, HIPAA) (+33 more)

### Community 21 - "Cloud Storage Sinks"
Cohesion: 0.35
Nodes (4): PublishResultTest, HttpHeaders, OffsetDateTime, UUID

### Community 22 - "Grafana & Prometheus Config"
Cohesion: 0.18
Nodes (10): Commits, Files Created, Implementation Details, Key Design Points, No Concerns, Status, Summary, Task 4 Implementation Report: RetryPolicy (+2 more)

### Community 23 - "NetLicensing Sink"
Cohesion: 0.50
Nodes (4): AuditEvent Schema, bearerAuth (JWT) Security Scheme, ErrorResponse Schema, publishEvent Operation (POST /api/v1/audit/publish)

### Community 24 - "Health & Readiness"
Cohesion: 0.50
Nodes (3): process(), Datadog Sink - forward audit events to the Datadog Logs intake API., Send a single audit event to the Datadog Logs intake API.

### Community 25 - "DLQ & Poison Messages"
Cohesion: 0.50
Nodes (3): process(), Logging Sink - Simple sink that logs events.  This sink writes audit events to t, Process an audit event by logging it.      Args:         event_data: The transfo

### Community 26 - "Graceful Shutdown"
Cohesion: 0.50
Nodes (3): process(), Snowflake Sink - insert audit events into a Snowflake table as a VARIANT.  Requi, Insert a single audit event into a Snowflake VARIANT column.

### Community 27 - "Tracing & OTLP Setup"
Cohesion: 0.50
Nodes (3): process(), Splunk Sink - forward audit events to a Splunk HTTP Event Collector (HEC)., Send a single audit event to a Splunk HEC endpoint.

### Community 38 - "Community 38"
Cohesion: 0.31
Nodes (4): Map, Mono, TransformationService, WebClient

### Community 39 - "Community 39"
Cohesion: 0.11
Nodes (18): _create_license(), _create_licensee(), NetLicensingClient, process(), _process_item(), NetLicensing Sink - Process checkout transactions and create/update NetLicensing, Process a single purchase order item and create licensee/licenses., Create a new licensee in NetLicensing. (+10 more)

### Community 40 - "Community 40"
Cohesion: 0.18
Nodes (10): Commit, Concerns, Files Created, Implementation Details, Next Steps, Process Followed, Status, Summary (+2 more)

### Community 41 - "Community 41"
Cohesion: 0.11
Nodes (17): Code-Review Fix Report (2026-06-26), Commit, Files Created, Fix 1 — `jakarta.annotation-api` scope: provided, Fix 2 — ApiClient stub self-documenting comment, Main sources, Modified, Self-Review Notes (+9 more)

### Community 43 - "Community 43"
Cohesion: 0.20
Nodes (9): Commits, Files Created, Implementation Details, Implementation Summary, Notes, Status, Task 3 Implementation Report — TokenProvider, TDD Process (+1 more)

### Community 44 - "Community 44"
Cohesion: 0.15
Nodes (12): Adding a transformer or sink, AGENTS.md — Labs64.IO :: AuditFlow, Backend (`auditflow-be`) details, Build, run, test, Conventions & guardrails, End-to-end data flow, graphify, Observability (+4 more)

### Community 45 - "Community 45"
Cohesion: 0.20
Nodes (5): ApplicationReadyEvent, OpenTelemetry setup for the AuditFlow Sink service.  Exports traces, logs, and m, OpenTelemetry setup for the AuditFlow Transformer service.  Exports traces, logs, OtelLogbackInstaller, OpenTelemetry

### Community 46 - "Community 46"
Cohesion: 0.50
Nodes (3): CircuitBreakerConfig, Customizer, ReactiveResilience4JCircuitBreakerFactory

### Community 47 - "Community 47"
Cohesion: 0.18
Nodes (9): Detailed registry view: per-sink version, description, and documented properties, Re-scan the sink directories (hot-reload of newly mounted bootstrap modules)., Set service as ready after startup completes., Send transformed audit events to a destination sink.      The sink is resolved f, registry_details(), registry_reload(), sink(), startup_event() (+1 more)

### Community 48 - "Community 48"
Cohesion: 0.06
Nodes (31): health(), HealthAccessLogFilter, liveness(), Health check endpoints for AuditFlow Python services. Provides /health, /ready,, Suppress Uvicorn access logs for health-check endpoints., Attach the filter to Uvicorn's access logger. Call once at startup., Mark the service as ready or not ready., Basic health check - returns 200 if service is running. (+23 more)

### Community 49 - "Community 49"
Cohesion: 0.22
Nodes (8): Advanced Configuration, AuditFlow API Client (Java), Configuration Options, Error Handling, Install, OpenAPI contract, Quick Start (Zero-Config), Usage

### Community 50 - "Community 50"
Cohesion: 0.23
Nodes (11): format_cef(), format_json(), process(), Syslog Sink - Send events to Syslog server.  This sink sends audit events to a S, Send message via UDP., Send message via TCP., Format event as JSON string., Format event as Common Event Format (CEF).     CEF:Version|Device Vendor|Device (+3 more)

### Community 51 - "Community 51"
Cohesion: 0.28
Nodes (5): AutoCloseable, HttpExchange, CannedResponse, CapturedRequest, StubAuditServer

### Community 52 - "Community 52"
Cohesion: 0.20
Nodes (8): Adding a New Sink, Architecture Overview, AuditFlow — Developer Guide, Available sinks (13), Prerequisites, Project Layout, Quick Start, Table of Contents

### Community 53 - "Community 53"
Cohesion: 0.18
Nodes (11): Backend won't start, Container healthcheck failing, Dead Letter Queue filling up, Events not being consumed, Idempotency: events being dropped unexpectedly, Maven build fails with "Java version not supported", Python service hot-reload not working, Sink returns 422 Unprocessable Content (+3 more)

### Community 54 - "Community 54"
Cohesion: 0.18
Nodes (10): Commands Run and Output, Commit, Concern: Extra.java not generated (4 models instead of 5), Files Created / Modified, Self-Review Notes, Status: DONE_WITH_CONCERNS, Step 1 — Move spec and remove stray dir, Step 5 — Verify auditflow-api generates models (+2 more)

### Community 55 - "Community 55"
Cohesion: 0.24
Nodes (5): GracefulShutdownManager, EventListener, HealthIndicator, PreDestroy, TimeUnit

### Community 56 - "Community 56"
Cohesion: 0.29
Nodes (9): flatten_dict(), generate_signature(), prepare_payload(), process(), Webhook Sink - Send events to HTTP webhooks (Zapier, Make, n8n, etc.).  This sin, Prepare payload based on content type., Generate HMAC signature for webhook verification (GitHub/Zapier style).     Uses, Flatten nested dictionary for URL encoding. (+1 more)

### Community 58 - "Community 58"
Cohesion: 0.11
Nodes (6): AuditEvents, Builder, AuditEventsTest, Geolocation, AuditEventSchemaTest, Validator

### Community 59 - "Community 59"
Cohesion: 0.22
Nodes (8): Detailed registry view: per-transformer version, description, and documented pro, Re-scan the transformer directories (hot-reload of newly mounted bootstrap modul, Set service as ready after startup completes., Transforms Labs64.IO AuditFlow JSON structures based on a transformer ID.      T, registry_details(), registry_reload(), startup_event(), transform()

### Community 60 - "Community 60"
Cohesion: 0.25
Nodes (8): Architecture, How signals flow from Python services, How signals flow from the Java backend, Observability Stack, Pre-provisioned Grafana dashboard, Startup sequence (full observability), Troubleshooting: no data in Grafana / Prometheus, Verify the stack is working

### Community 61 - "Community 61"
Cohesion: 0.32
Nodes (7): ensure_log_group(), ensure_log_stream(), process(), AWS CloudWatch Logs Sink - Send events to AWS CloudWatch Logs.  This sink sends, Ensure log group exists, create if it doesn't., Ensure log stream exists, create if it doesn't., Process an audit event by sending it to CloudWatch Logs.      Args:         even

### Community 63 - "Community 63"
Cohesion: 0.19
Nodes (5): CircuitBreakerRegistry, CircuitBreakerMetricsExporter, PostConstruct, LocalSinkDiscovery, SinkDiscovery

### Community 64 - "Community 64"
Cohesion: 0.29
Nodes (3): BiConsumer, ClientConfig, HttpClient

### Community 65 - "Community 65"
Cohesion: 0.33
Nodes (4): At-Least-Once Idempotency / Dedup (claim-process-mark), ConditionalOnProperty, Claim-on-receive dedup pattern, StringRedisTemplate

### Community 66 - "Community 66"
Cohesion: 0.33
Nodes (6): Actuator Endpoints (Backend), Circuit Breaker States, Graceful Shutdown, Health & Observability, Python Service Endpoints, Rate Limiting

### Community 67 - "Community 67"
Cohesion: 0.50
Nodes (4): Docker (recommended), Host-Based Development, Observability Stack, Running Locally

### Community 68 - "Community 68"
Cohesion: 0.40
Nodes (5): build_object_key(), process(), AWS S3 Sink - Store events in Amazon S3.  This sink uploads audit events to AWS, Build S3 object key with optional tenantId and date partitioning., Process an audit event by uploading it to AWS S3.      Args:         event_data:

### Community 69 - "Community 69"
Cohesion: 0.40
Nodes (5): build_blob_name(), process(), Azure Blob Storage Sink - Store events in Azure Blob Storage.  This sink uploads, Build blob name with optional date partitioning., Process an audit event by uploading it to Azure Blob Storage.      Args:

### Community 70 - "Community 70"
Cohesion: 0.40
Nodes (5): build_object_name(), process(), Google Cloud Storage Sink - Store events in Google Cloud Storage.  This sink upl, Build GCS object name with optional date partitioning., Process an audit event by uploading it to Google Cloud Storage.      Args:

### Community 71 - "Community 71"
Cohesion: 0.40
Nodes (5): get_log_level(), Loki transformer: reshapes an AuditFlow event into a Grafana Loki push payload., Maps a status string to a log level string., Transforms a Labs64.IO AuditFlow JSON structure into a Loki-compatible payload., transform()

### Community 72 - "Community 72"
Cohesion: 0.33
Nodes (6): Backend (Java), End-to-End (stack must be running), Getting-Started Notebook (stack must be running), Python Services, Run All Tests, Testing

### Community 73 - "Community 73"
Cohesion: 0.50
Nodes (4): Observability URLs (obs stack only), Quick Reference, Service URLs, Useful commands

### Community 74 - "Community 74"
Cohesion: 0.50
Nodes (3): Minor findings (for final review triage), SDD Progress — AuditFlow API Client Library, Tasks

### Community 75 - "Community 75"
Cohesion: 0.50
Nodes (4): Condition operators, Configuring Pipelines, Configuring via JAVA_OPTS, Pipeline structure

### Community 76 - "Community 76"
Cohesion: 0.50
Nodes (3): process(), Loki Sink - Send events to Grafana Loki for log aggregation.  This sink sends tr, Process an audit event by sending it to Grafana Loki.      Args:         event_d

### Community 77 - "Community 77"
Cohesion: 0.50
Nodes (3): process(), OpenSearch Sink - Send events to OpenSearch/Elasticsearch.  This sink sends tran, Process an audit event by sending it to OpenSearch.      Args:         event_dat

### Community 78 - "Community 78"
Cohesion: 0.50
Nodes (3): OpenSearch transformer: flattens an AuditFlow event into an OpenSearch-friendly, Transforms an AuditEvent JSON object into a flattened, OpenSearch-friendly forma, transform()

### Community 79 - "Community 79"
Cohesion: 0.50
Nodes (3): Pass-through transformer: returns the input event unchanged., This is the 'zero' transformation.     It performs no transformation and simply, transform()

### Community 84 - "Community 84"
Cohesion: 0.25
Nodes (7): AuditEvent accessor names (confirmed from generated source), Commands run, Exception hierarchy note, JDK HttpServer header casing (deviation from brief), Self-review notes, Task 7 Report — DefaultAuditFlowClient sync publish + StubAuditServer, Test results

### Community 85 - "Community 85"
Cohesion: 0.29
Nodes (6): Commits, Files created, Status: DONE, Task 6 Implementation Report, Tests, What was done

### Community 87 - "Community 87"
Cohesion: 0.67
Nodes (3): Adding a New Transformer, Available transformers (3), Multi-stage transformer chains

## Knowledge Gaps
- **173 isolated node(s):** `io.labs64:auditflow-api`, `io.labs64:auditflow`, `OpenAPIConfig`, `Tasks`, `Minor findings (for final review triage)` (+168 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **25 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ConsumerHealthIndicator` connect `Consumer Health` to `HTTP Retry & WebClient`, `OTel & Observability`, `RabbitMQ Streaming`, `Community 47`, `Community 55`?**
  _High betweenness centrality (0.019) - this node is a cross-community bridge._
- **What connects `io.labs64:auditflow-api`, `io.labs64:auditflow`, `OpenAPIConfig` to the rest of the system?**
  _295 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Condition Evaluator Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.06136732908386452 - nodes in this community are weakly interconnected._
- **Should `Idempotency & Redis` be split into smaller, more focused modules?**
  _Cohesion score 0.05672926447574335 - nodes in this community are weakly interconnected._
- **Should `Exception Handling` be split into smaller, more focused modules?**
  _Cohesion score 0.1286549707602339 - nodes in this community are weakly interconnected._
- **Should `Sink SDK & Base Classes` be split into smaller, more focused modules?**
  _Cohesion score 0.06557377049180328 - nodes in this community are weakly interconnected._
- **Should `Global Exception & Transformer Registry` be split into smaller, more focused modules?**
  _Cohesion score 0.053156146179401995 - nodes in this community are weakly interconnected._