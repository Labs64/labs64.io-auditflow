# Graph Report - .  (2026-06-17)

## Corpus Check
- 39 files · ~25,756 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 799 nodes · 1597 edges · 43 communities (38 shown, 5 thin omitted)
- Extraction: 85% EXTRACTED · 15% INFERRED · 0% AMBIGUOUS · INFERRED: 235 edges (avg confidence: 0.81)
- Token cost: 125,124 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Audit Event Model & Publishing|Audit Event Model & Publishing]]
- [[_COMMUNITY_Transformer HTTP Delivery & Retry|Transformer HTTP Delivery & Retry]]
- [[_COMMUNITY_Pipeline Config & Sink Properties|Pipeline Config & Sink Properties]]
- [[_COMMUNITY_Condition & Pipeline Config Binding|Condition & Pipeline Config Binding]]
- [[_COMMUNITY_Application Bootstrap & Orchestration|Application Bootstrap & Orchestration]]
- [[_COMMUNITY_Transformer Plugin Registry|Transformer Plugin Registry]]
- [[_COMMUNITY_Sink HTTP Delivery & Retry|Sink HTTP Delivery & Retry]]
- [[_COMMUNITY_Condition Evaluation|Condition Evaluation]]
- [[_COMMUNITY_Sink Plugin Registry|Sink Plugin Registry]]
- [[_COMMUNITY_Delivery Failure Classification|Delivery Failure Classification]]
- [[_COMMUNITY_Architectural Design Rationale|Architectural Design Rationale]]
- [[_COMMUNITY_Audit Event REST Controller|Audit Event REST Controller]]
- [[_COMMUNITY_NetLicensing Sink|NetLicensing Sink]]
- [[_COMMUNITY_Transformer FastAPI App|Transformer FastAPI App]]
- [[_COMMUNITY_Circuit Breaker & Idempotency Config|Circuit Breaker & Idempotency Config]]
- [[_COMMUNITY_Syslog Sink|Syslog Sink]]
- [[_COMMUNITY_Sink Plugin SDK Base Classes|Sink Plugin SDK Base Classes]]
- [[_COMMUNITY_Transformer Service Discovery|Transformer Service Discovery]]
- [[_COMMUNITY_Sink Service Discovery|Sink Service Discovery]]
- [[_COMMUNITY_Plugin Registry Tests|Plugin Registry Tests]]
- [[_COMMUNITY_Plugin SDK & Property Validation|Plugin SDK & Property Validation]]
- [[_COMMUNITY_Datadog Sink & Sink API Tests|Datadog Sink & Sink API Tests]]
- [[_COMMUNITY_Correlation ID Filter|Correlation ID Filter]]
- [[_COMMUNITY_Audit Event Subscriber|Audit Event Subscriber]]
- [[_COMMUNITY_Logging & Snowflake Sinks|Logging & Snowflake Sinks]]
- [[_COMMUNITY_AWS CloudWatch Sink|AWS CloudWatch Sink]]
- [[_COMMUNITY_Quarantine Service Tests|Quarantine Service Tests]]
- [[_COMMUNITY_Publish Exception|Publish Exception]]
- [[_COMMUNITY_Splunk Sink|Splunk Sink]]
- [[_COMMUNITY_Sink Dispatch Endpoint|Sink Dispatch Endpoint]]
- [[_COMMUNITY_AWS S3 Sink|AWS S3 Sink]]
- [[_COMMUNITY_Azure Blob Sink|Azure Blob Sink]]
- [[_COMMUNITY_Google Cloud Storage Sink|Google Cloud Storage Sink]]
- [[_COMMUNITY_Jackson Configuration|Jackson Configuration]]
- [[_COMMUNITY_Pipeline Executor Config|Pipeline Executor Config]]
- [[_COMMUNITY_Loki Transformer|Loki Transformer]]
- [[_COMMUNITY_Loki Sink|Loki Sink]]
- [[_COMMUNITY_OpenSearch Sink|OpenSearch Sink]]
- [[_COMMUNITY_Transformer Tracing Setup|Transformer Tracing Setup]]
- [[_COMMUNITY_Sink Tracing Setup|Sink Tracing Setup]]
- [[_COMMUNITY_OpenSearch Transformer|OpenSearch Transformer]]
- [[_COMMUNITY_OpenAPI Configuration|OpenAPI Configuration]]
- [[_COMMUNITY_CI & Docker Publish Workflows|CI & Docker Publish Workflows]]

## God Nodes (most connected - your core abstractions)
1. `ConditionEvaluatorTest` - 30 edges
2. `Test` - 26 edges
3. `DisplayName` - 26 edges
4. `AuditServiceTest` - 21 edges
5. `Test` - 18 edges
6. `DisplayName` - 18 edges
7. `String` - 16 edges
8. `PluginRegistry` - 16 edges
9. `PipelineProperties` - 15 edges
10. `AuditService` - 14 edges

## Surprising Connections (you probably didn't know these)
- `require_properties()` --semantically_similar_to--> `require_properties()`  [INFERRED] [semantically similar]
  auditflow-transformer/auditflow_sdk.py → auditflow-sink/auditflow_sdk.py
- `PluginRegistry` --semantically_similar_to--> `PluginRegistry`  [INFERRED] [semantically similar]
  auditflow-transformer/plugin_registry.py → auditflow-sink/plugin_registry.py
- `Transformer transform(input_data) contract` --semantically_similar_to--> `Sink process(event_data, properties) contract`  [INFERRED] [semantically similar]
  auditflow-transformer/auditflow_sdk.py → auditflow-sink/auditflow_sdk.py
- `Infra-Only Stack (RabbitMQ)` --semantically_similar_to--> `RabbitMQ Broker (compose)`  [INFERRED] [semantically similar]
  docker-compose-infra.yml → docker-compose.yml
- `transformer FastAPI app` --semantically_similar_to--> `sink FastAPI app`  [INFERRED] [semantically similar]
  auditflow-transformer/transformer.py → auditflow-sink/sink.py

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Resilient Outbound Delivery (retry + circuit breaker + failure classification)** — service_httpretrysupport, service_deliveryerrors, config_circuitbreakerconfig, exception_poisondeliveryexception, exception_retryabledeliveryexception [EXTRACTED 0.90]
- **Audit Event Processing Orchestration** — service_auditservice, service_transformationservice, service_sinkservice, service_conditionevaluator, service_idempotencyservice, service_quarantineservice [EXTRACTED 0.90]
- **Sink request dispatch via allow-list registry** — auditflow_sink_sink_sink, auditflow_sink_plugin_registry_resolve, sinks_logging_sink_process, sink_process_contract [INFERRED 0.85]
- **Symmetric transformer/sink plugin pattern** — auditflow_sink_plugin_registry_pluginregistry, auditflow_transformer_plugin_registry_pluginregistry, auditflow_sink_auditflow_sdk_require_properties, auditflow_transformer_auditflow_sdk_require_properties [INFERRED 0.85]
- **Sinks validating config via require_properties** — sinks_datadog_sink_process, sinks_snowflake_sink_process, sinks_splunk_sink_process, auditflow_sink_auditflow_sdk_require_properties [EXTRACTED 1.00]

## Communities (43 total, 5 thin omitted)

### Community 0 - "Audit Event Model & Publishing"
Cohesion: 0.07
Nodes (29): AuditEvent, Action, List, Rule, String, AuditEvent, ObjectMapper, RedactionService (+21 more)

### Community 1 - "Transformer HTTP Delivery & Retry"
Cohesion: 0.06
Nodes (31): Duration, HttpRetryProperties, Throwable, Builder, HttpRetryProperties, JsonNode, Map, MeterRegistry (+23 more)

### Community 2 - "Pipeline Config & Sink Properties"
Cohesion: 0.12
Nodes (15): List, SinkProperties, String, String, BeforeEach, DisplayName, PipelineProperties, String (+7 more)

### Community 3 - "Condition & Pipeline Config Binding"
Cohesion: 0.07
Nodes (24): ConditionRule, Map, PipelineProperties, PostConstruct, String, ConditionProperties, ConditionRule, JsonNode (+16 more)

### Community 4 - "Application Bootstrap & Orchestration"
Cohesion: 0.08
Nodes (26): AuditApplication, String, ConditionProperties, Executor, JsonNode, MeterRegistry, Mono, ObjectMapper (+18 more)

### Community 5 - "Transformer Plugin Registry"
Cohesion: 0.07
Nodes (31): PluginNotFoundError, PluginRegistry, Plugin registry for dynamically-loaded modules (transformers / sinks).  Hardenin, List the allow-listed plugins (id, type, path)., Full registry view including optional SDK metadata (version, description, proper, Map of discovered-but-excluded plugin id -> {kind, error}., The requested plugin id is not on the discovered allow-list., Discovers, validates, and resolves plugin modules from a fixed set of directorie (+23 more)

### Community 6 - "Sink HTTP Delivery & Retry"
Cohesion: 0.12
Nodes (22): String, Builder, HttpRetryProperties, JsonNode, Map, MeterRegistry, Mono, ReactiveCircuitBreakerFactory (+14 more)

### Community 7 - "Condition Evaluation"
Cohesion: 0.25
Nodes (8): BeforeEach, ConditionProperties, ConditionRule, DisplayName, JsonNode, String, Test, ConditionEvaluatorTest

### Community 8 - "Sink Plugin Registry"
Cohesion: 0.08
Nodes (19): PluginRegistry._load_one (sink), PluginRegistry.discover (sink), sink FastAPI app, PluginNotFoundError, PluginRegistry, Plugin registry for dynamically-loaded modules (transformers / sinks).  Hardenin, List the allow-listed plugins (id, type, path)., Full registry view including optional SDK metadata (version, description, proper (+11 more)

### Community 9 - "Delivery Failure Classification"
Cohesion: 0.12
Nodes (14): String, Throwable, String, Throwable, RuntimeException, String, Throwable, DisplayName (+6 more)

### Community 10 - "Architectural Design Rationale"
Cohesion: 0.08
Nodes (26): Async Processing via Message Queue, Env-Only RabbitMQ Credentials (no defaults), Module ID Validation Regex (^[a-zA-Z0-9_]+$), OpenAPI-First Build (generated models), Pipelines Are Configuration, Not Code, Transformer/Sink Plugin Pattern, Backend Is Both Producer and Consumer, Server-Assigned readOnly Timestamp (+18 more)

### Community 11 - "Audit Event REST Controller"
Cohesion: 0.16
Nodes (17): AuditEventApi, AuditEvent, Override, ResponseEntity, String, ResponseEntity, String, AuditPublisherService (+9 more)

### Community 12 - "NetLicensing Sink"
Cohesion: 0.12
Nodes (17): _create_license(), _create_licensee(), NetLicensingClient, process(), _process_item(), NetLicensing Sink - Process checkout transactions and create/update NetLicensing, Process a single purchase order item and create licensee/licenses., Create a new licensee in NetLicensing. (+9 more)

### Community 13 - "Transformer FastAPI App"
Cohesion: 0.08
Nodes (16): PluginRegistry.resolve (transformer), list_transformers(), List all available (allow-listed) transformer modules. Also doubles as the conta, List all available transformer modules.      Returns a list of available transfo, Detailed registry view: per-transformer version, description, and documented pro, Re-scan the transformer directories (hot-reload of newly mounted bootstrap modul, Transforms Labs64.IO AuditFlow JSON structures based on a transformer ID.      T, registry_details() (+8 more)

### Community 14 - "Circuit Breaker & Idempotency Config"
Cohesion: 0.16
Nodes (12): Duration, BeforeEach, DisplayName, Test, Bean, At-Least-Once Idempotency / Dedup (claim-process-mark), CircuitBreakerConfig, Customizer (+4 more)

### Community 15 - "Syslog Sink"
Cohesion: 0.13
Nodes (19): format_cef(), format_json(), process(), Syslog Sink - Send events to Syslog server.  This sink sends audit events to a S, Send message via UDP., Send message via TCP., Format event as JSON string., Format event as Common Event Format (CEF).     CEF:Version|Device Vendor|Device (+11 more)

### Community 16 - "Sink Plugin SDK Base Classes"
Cohesion: 0.19
Nodes (10): ABC, BaseSink, BaseTransformer, Any, Optional SDK for AuditFlow plugins (P2-5).  Plugins stay simple: a transformer m, Optional base class for transformer plugins., Reshape/enrich the event and return the new event dict., Optional base class for sink plugins. (+2 more)

### Community 17 - "Transformer Service Discovery"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesDiscoveryService, LocalDiscoveryService, TransformerDiscovery

### Community 18 - "Sink Service Discovery"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesSinkDiscovery, LocalSinkDiscovery, SinkDiscovery

### Community 19 - "Plugin Registry Tests"
Cohesion: 0.32
Nodes (11): Path, Unit tests for the plugin registry allow-list / hardening (P1-4)., _registry(), test_details_captures_optional_metadata(), test_discovers_and_resolves_valid_plugin(), test_import_error_is_excluded_without_crashing(), test_malformed_id_raises_not_found(), test_missing_entry_point_is_excluded() (+3 more)

### Community 20 - "Plugin SDK & Property Validation"
Cohesion: 0.18
Nodes (10): BaseSink, BaseTransformer, Any, Optional SDK for AuditFlow plugins (P2-5).  Plugins stay simple: a transformer m, Optional base class for transformer plugins., Reshape/enrich the event and return the new event dict., Optional base class for sink plugins., Deliver the event to the destination and return a result dict. (+2 more)

### Community 21 - "Datadog Sink & Sink API Tests"
Cohesion: 0.15
Nodes (5): process(), Datadog Sink - forward audit events to the Datadog Logs intake API., Send a single audit event to the Datadog Logs intake API., API-level tests for the sink service (P1-3)., test_datadog_sink_missing_api_key_errors()

### Community 22 - "Correlation ID Filter"
Cohesion: 0.31
Nodes (7): Override, String, CorrelationIdFilter, FilterChain, HttpServletRequest, HttpServletResponse, OncePerRequestFilter

### Community 23 - "Audit Event Subscriber"
Cohesion: 0.29
Nodes (6): Bean, PostConstruct, String, AuditService, Consumer, AuditSubscriberService

### Community 24 - "Logging & Snowflake Sinks"
Cohesion: 0.22
Nodes (7): Sink process(event_data, properties) contract, process(), Logging Sink - Simple sink that logs events.  This sink writes audit events to t, Process an audit event by logging it.      Args:         event_data: The transfo, process(), Snowflake Sink - insert audit events into a Snowflake table as a VARIANT.  Requi, Insert a single audit event into a Snowflake VARIANT column.

### Community 25 - "AWS CloudWatch Sink"
Cohesion: 0.32
Nodes (7): ensure_log_group(), ensure_log_stream(), process(), AWS CloudWatch Logs Sink - Send events to AWS CloudWatch Logs.  This sink sends, Ensure log group exists, create if it doesn't., Ensure log stream exists, create if it doesn't., Process an audit event by sending it to CloudWatch Logs.      Args:         even

### Community 26 - "Quarantine Service Tests"
Cohesion: 0.43
Nodes (4): BeforeEach, DisplayName, Test, QuarantineServiceTest

### Community 27 - "Publish Exception"
Cohesion: 0.33
Nodes (4): String, PublishException, RuntimeException, Throwable

### Community 28 - "Splunk Sink"
Cohesion: 0.33
Nodes (5): Raise ValueError if any required property key is missing/empty. Convenience for, require_properties(), process(), Splunk Sink - forward audit events to a Splunk HTTP Event Collector (HEC)., Send a single audit event to a Splunk HEC endpoint.

### Community 29 - "Sink Dispatch Endpoint"
Cohesion: 0.33
Nodes (6): PluginRegistry.resolve (sink), Send transformed audit events to a destination sink.      This endpoint dynamica, Send transformed audit events to a destination sink.      The sink is resolved f, sink(), test_malformed_id_raises_not_found (sink), test_logging_sink_processes_event()

### Community 30 - "AWS S3 Sink"
Cohesion: 0.40
Nodes (5): build_object_key(), process(), AWS S3 Sink - Store events in Amazon S3.  This sink uploads audit events to AWS, Build S3 object key with optional tenantId and date partitioning., Process an audit event by uploading it to AWS S3.      Args:         event_data:

### Community 31 - "Azure Blob Sink"
Cohesion: 0.40
Nodes (5): build_blob_name(), process(), Azure Blob Storage Sink - Store events in Azure Blob Storage.  This sink uploads, Build blob name with optional date partitioning., Process an audit event by uploading it to Azure Blob Storage.      Args:

### Community 32 - "Google Cloud Storage Sink"
Cohesion: 0.40
Nodes (5): build_object_name(), process(), Google Cloud Storage Sink - Store events in Google Cloud Storage.  This sink upl, Build GCS object name with optional date partitioning., Process an audit event by uploading it to Google Cloud Storage.      Args:

### Community 33 - "Jackson Configuration"
Cohesion: 0.60
Nodes (3): Bean, ObjectMapper, JacksonConfig

### Community 34 - "Pipeline Executor Config"
Cohesion: 0.60
Nodes (3): Bean, Executor, PipelineExecutorConfig

### Community 35 - "Loki Transformer"
Cohesion: 0.50
Nodes (4): get_log_level(), Maps a status string to a log level string., Transforms a Labs64.IO AuditFlow JSON structure into a Loki-compatible payload., transform()

### Community 36 - "Loki Sink"
Cohesion: 0.50
Nodes (3): process(), Loki Sink - Send events to Grafana Loki for log aggregation.  This sink sends tr, Process an audit event by sending it to Grafana Loki.      Args:         event_d

### Community 37 - "OpenSearch Sink"
Cohesion: 0.50
Nodes (3): process(), OpenSearch Sink - Send events to OpenSearch/Elasticsearch.  This sink sends tran, Process an audit event by sending it to OpenSearch.      Args:         event_dat

## Knowledge Gaps
- **63 isolated node(s):** `String`, `Override`, `String`, `OpenAPIConfig`, `String` (+58 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **5 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Exception` connect `Sink Plugin Registry` to `Audit Event REST Controller`, `Transformer Plugin Registry`?**
  _High betweenness centrality (0.248) - this node is a cross-community bridge._
- **Why does `PluginNotFoundError` connect `Transformer Plugin Registry` to `Sink Plugin Registry`, `Plugin Registry Tests`, `Sink Dispatch Endpoint`?**
  _High betweenness centrality (0.181) - this node is a cross-community bridge._
- **What connects `String`, `Override`, `String` to the rest of the system?**
  _170 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Audit Event Model & Publishing` be split into smaller, more focused modules?**
  _Cohesion score 0.0689484126984127 - nodes in this community are weakly interconnected._
- **Should `Transformer HTTP Delivery & Retry` be split into smaller, more focused modules?**
  _Cohesion score 0.06370543541788427 - nodes in this community are weakly interconnected._
- **Should `Pipeline Config & Sink Properties` be split into smaller, more focused modules?**
  _Cohesion score 0.12337662337662338 - nodes in this community are weakly interconnected._
- **Should `Condition & Pipeline Config Binding` be split into smaller, more focused modules?**
  _Cohesion score 0.07127882599580712 - nodes in this community are weakly interconnected._