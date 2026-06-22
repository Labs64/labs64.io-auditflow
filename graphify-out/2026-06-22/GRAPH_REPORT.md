# Graph Report - .  (2026-06-20)

## Corpus Check
- 54 files · ~36,947 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 541 nodes · 1019 edges · 38 communities (29 shown, 9 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 73 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

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

## God Nodes (most connected - your core abstractions)
1. `ConditionEvaluatorTest` - 30 edges
2. `Test` - 26 edges
3. `DisplayName` - 26 edges
4. `String` - 16 edges
5. `PipelineProperties` - 14 edges
6. `PluginRegistry` - 12 edges
7. `RuntimeException` - 11 edges
8. `InMemoryIdempotencyService` - 11 edges
9. `RedisIdempotencyService` - 10 edges
10. `RedactionServiceTest` - 10 edges

## Surprising Connections (you probably didn't know these)
- `PluginNotFoundError` --inherits--> `Exception`  [EXTRACTED]
  auditflow-sink/plugin_registry.py → auditflow-be/src/main/java/io/labs64/audit/exception/GlobalExceptionHandler.java
- `Path` --uses--> `PluginNotFoundError`  [INFERRED]
  auditflow-transformer/tests/test_plugin_registry.py → auditflow-sink/plugin_registry.py
- `Path` --uses--> `PluginRegistry`  [INFERRED]
  auditflow-transformer/tests/test_plugin_registry.py → auditflow-sink/plugin_registry.py
- `PluginNotFoundError` --inherits--> `Exception`  [EXTRACTED]
  auditflow-transformer/plugin_registry.py → auditflow-be/src/main/java/io/labs64/audit/exception/GlobalExceptionHandler.java
- `Path` --uses--> `PluginNotFoundError`  [INFERRED]
  auditflow-sink/tests/test_plugin_registry.py → auditflow-sink/plugin_registry.py

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

## Communities (38 total, 9 thin omitted)

### Community 0 - "Pipeline Config & Conditions"
Cohesion: 0.06
Nodes (20): ConditionProperties, ConditionRule, List, Map, PipelineProperties, PostConstruct, SinkProperties, String (+12 more)

### Community 1 - "HTTP Retry & WebClient"
Cohesion: 0.06
Nodes (27): Duration, HttpRetryProperties, Throwable, MeterRegistry, StreamBridge, String, Builder, HttpRetryProperties (+19 more)

### Community 2 - "Condition Evaluator Tests"
Cohesion: 0.24
Nodes (8): BeforeEach, ConditionProperties, ConditionRule, DisplayName, JsonNode, String, Test, ConditionEvaluatorTest

### Community 3 - "Audit Event API & Controller"
Cohesion: 0.12
Nodes (22): AuditEventApi, AuditEvent, Override, ResponseEntity, String, ResponseEntity, String, AuditEvent (+14 more)

### Community 4 - "Idempotency & Redis"
Cohesion: 0.12
Nodes (13): String, Duration, Override, String, BeforeEach, DisplayName, Test, At-Least-Once Idempotency / Dedup (claim-process-mark) (+5 more)

### Community 5 - "Sink Plugin Registry"
Cohesion: 0.09
Nodes (23): PluginNotFoundError, PluginRegistry, Plugin registry for dynamically-loaded modules (transformers / sinks).  Hardenin, List the allow-listed plugins (id, type, path)., Full registry view including optional SDK metadata (version, description, proper, Map of discovered-but-excluded plugin id -> {kind, error}., The requested plugin id is not on the discovered allow-list., Discovers, validates, and resolves plugin modules from a fixed set of directorie (+15 more)

### Community 6 - "Exception Handling"
Cohesion: 0.10
Nodes (17): String, Throwable, String, Throwable, String, Throwable, RuntimeException, String (+9 more)

### Community 7 - "Sink SDK & Base Classes"
Cohesion: 0.10
Nodes (21): ABC, BaseSink, BaseTransformer, Any, Optional SDK for AuditFlow plugins.  Plugins stay simple: a transformer module d, Optional base class for transformer plugins., Reshape/enrich the event and return the new event dict., Optional base class for sink plugins. (+13 more)

### Community 8 - "Global Exception & Transformer Registry"
Cohesion: 0.10
Nodes (13): Exception, PluginNotFoundError, PluginRegistry, Plugin registry for dynamically-loaded modules (transformers / sinks).  Hardenin, List the allow-listed plugins (id, type, path)., Full registry view including optional SDK metadata (version, description, proper, Map of discovered-but-excluded plugin id -> {kind, error}., The requested plugin id is not on the discovered allow-list. (+5 more)

### Community 9 - "Transformation Service Tests"
Cohesion: 0.26
Nodes (9): BeforeEach, DisplayName, JsonNode, Mono, String, SuppressWarnings, Test, WebClient (+1 more)

### Community 10 - "Service Discovery"
Cohesion: 0.32
Nodes (8): Action, DisplayName, JsonNode, RedactionService, Rule, String, Test, RedactionServiceTest

### Community 11 - "OTel & Observability"
Cohesion: 0.17
Nodes (6): Action, List, Rule, String, RedactionProperties, Rule

### Community 12 - "Circuit Breaker & Rate Limiting"
Cohesion: 0.13
Nodes (9): KubernetesClient, Override, String, Override, String, String, KubernetesSinkDiscovery, LocalSinkDiscovery (+1 more)

### Community 13 - "RabbitMQ Streaming"
Cohesion: 0.33
Nodes (4): Duration, Override, String, InMemoryIdempotencyService

### Community 14 - "Sink Plugins"
Cohesion: 0.27
Nodes (8): BeforeEach, DisplayName, String, SuppressWarnings, Test, CsvSource, ParameterizedTest, KubernetesSinkDiscoveryTest

### Community 15 - "Transformer Plugins"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, TransformerDiscovery, KubernetesDiscoveryService, LocalDiscoveryService

### Community 16 - "Docker & Infra Config"
Cohesion: 0.35
Nodes (4): BeforeEach, DisplayName, Test, InMemoryIdempotencyServiceTest

### Community 17 - "CI/CD Workflows"
Cohesion: 0.32
Nodes (11): Path, Unit tests for the plugin registry allow-list / hardening (P1-4)., _registry(), test_details_captures_optional_metadata(), test_discovers_and_resolves_valid_plugin(), test_import_error_is_excluded_without_crashing(), test_malformed_id_raises_not_found(), test_missing_entry_point_is_excluded() (+3 more)

### Community 18 - "Kubernetes Discovery"
Cohesion: 0.31
Nodes (7): Override, String, CorrelationIdFilter, FilterChain, HttpServletRequest, HttpServletResponse, OncePerRequestFilter

### Community 19 - "Audit Service & Pipeline"
Cohesion: 0.29
Nodes (6): Bean, PostConstruct, String, AuditService, Consumer, AuditSubscriberService

### Community 20 - "Loki & OpenSearch Sinks"
Cohesion: 0.33
Nodes (5): Architecture Highlights, Key Features, Labs64.IO :: AuditFlow, Scalable Audit Logging for Modern Microservices, Star History

### Community 21 - "Cloud Storage Sinks"
Cohesion: 0.40
Nodes (3): AuditApplication, String, Reactor Context Propagation for Tracing/MDC

### Community 22 - "Grafana & Prometheus Config"
Cohesion: 0.60
Nodes (3): Bean, ObjectMapper, JacksonConfig

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

## Knowledge Gaps
- **49 isolated node(s):** `String`, `Override`, `String`, `OpenAPIConfig`, `String` (+44 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `RuntimeException` connect `Exception Handling` to `Condition Evaluator Tests`, `Transformation Service Tests`, `Service Discovery`, `Sink Plugins`, `Docker & Infra Config`?**
  _High betweenness centrality (0.330) - this node is a cross-community bridge._
- **Why does `Exception` connect `Global Exception & Transformer Registry` to `Audit Event API & Controller`, `Sink Plugin Registry`?**
  _High betweenness centrality (0.175) - this node is a cross-community bridge._
- **Why does `IllegalArgumentException` connect `Audit Event API & Controller` to `HTTP Retry & WebClient`?**
  _High betweenness centrality (0.153) - this node is a cross-community bridge._
- **What connects `String`, `Override`, `String` to the rest of the system?**
  _95 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Pipeline Config & Conditions` be split into smaller, more focused modules?**
  _Cohesion score 0.060655737704918035 - nodes in this community are weakly interconnected._
- **Should `HTTP Retry & WebClient` be split into smaller, more focused modules?**
  _Cohesion score 0.06274509803921569 - nodes in this community are weakly interconnected._
- **Should `Audit Event API & Controller` be split into smaller, more focused modules?**
  _Cohesion score 0.11586452762923351 - nodes in this community are weakly interconnected._