# Graph Report - .  (2026-06-17)

## Corpus Check
- 8 files · ~26,842 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 852 nodes · 1718 edges · 49 communities (43 shown, 6 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 240 edges (avg confidence: 0.81)
- Token cost: 33,130 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Audit Event Core & Redaction|Audit Event Core & Redaction]]
- [[_COMMUNITY_Pipeline & Condition Config|Pipeline & Condition Config]]
- [[_COMMUNITY_Python Plugin Registry|Python Plugin Registry]]
- [[_COMMUNITY_App Bootstrap & Wiring|App Bootstrap & Wiring]]
- [[_COMMUNITY_Idempotency  Dedup Guard|Idempotency / Dedup Guard]]
- [[_COMMUNITY_Pipeline Properties Binding|Pipeline Properties Binding]]
- [[_COMMUNITY_Sink Delivery WebClient|Sink Delivery WebClient]]
- [[_COMMUNITY_Condition Evaluator Tests|Condition Evaluator Tests]]
- [[_COMMUNITY_Transformer Delivery WebClient|Transformer Delivery WebClient]]
- [[_COMMUNITY_Poison vs Retryable Failures|Poison vs Retryable Failures]]
- [[_COMMUNITY_Architecture Principles (AGENTS)|Architecture Principles (AGENTS)]]
- [[_COMMUNITY_Audit Event REST Controller|Audit Event REST Controller]]
- [[_COMMUNITY_NetLicensing Sink|NetLicensing Sink]]
- [[_COMMUNITY_Syslog & Webhook Sinks|Syslog & Webhook Sinks]]
- [[_COMMUNITY_Transformation Service Tests|Transformation Service Tests]]
- [[_COMMUNITY_Plugin Registry Internals|Plugin Registry Internals]]
- [[_COMMUNITY_Plugin SDK Base Classes|Plugin SDK Base Classes]]
- [[_COMMUNITY_Transformer Discovery (LocalK8s)|Transformer Discovery (Local/K8s)]]
- [[_COMMUNITY_Sink Discovery (LocalK8s)|Sink Discovery (Local/K8s)]]
- [[_COMMUNITY_In-Memory Idempotency Tests|In-Memory Idempotency Tests]]
- [[_COMMUNITY_Plugin SDK Property Helpers|Plugin SDK Property Helpers]]
- [[_COMMUNITY_Datadog Sink & Sink API Tests|Datadog Sink & Sink API Tests]]
- [[_COMMUNITY_Correlation ID Filter|Correlation ID Filter]]
- [[_COMMUNITY_Audit Subscriber (Consumer)|Audit Subscriber (Consumer)]]
- [[_COMMUNITY_Idempotency Service Tests|Idempotency Service Tests]]
- [[_COMMUNITY_Zero Pass-Through Transformer|Zero Pass-Through Transformer]]
- [[_COMMUNITY_Logging & Splunk Sinks|Logging & Splunk Sinks]]
- [[_COMMUNITY_Transformer FastAPI App|Transformer FastAPI App]]
- [[_COMMUNITY_AWS CloudWatch Sink|AWS CloudWatch Sink]]
- [[_COMMUNITY_Quarantine Service Tests|Quarantine Service Tests]]
- [[_COMMUNITY_Plugin Resolve & Errors|Plugin Resolve & Errors]]
- [[_COMMUNITY_Transformer API Tests|Transformer API Tests]]
- [[_COMMUNITY_Publish Exception|Publish Exception]]
- [[_COMMUNITY_Snowflake Sink|Snowflake Sink]]
- [[_COMMUNITY_Plugin Load & CI Flow|Plugin Load & CI Flow]]
- [[_COMMUNITY_Sink Endpoint & Tests|Sink Endpoint & Tests]]
- [[_COMMUNITY_AWS S3 Sink|AWS S3 Sink]]
- [[_COMMUNITY_Azure Blob Sink|Azure Blob Sink]]
- [[_COMMUNITY_GCS Sink|GCS Sink]]
- [[_COMMUNITY_Jackson Config|Jackson Config]]
- [[_COMMUNITY_Pipeline Executor Config|Pipeline Executor Config]]
- [[_COMMUNITY_Audit Loki Transformer|Audit Loki Transformer]]
- [[_COMMUNITY_Loki Sink|Loki Sink]]
- [[_COMMUNITY_OpenSearch Sink|OpenSearch Sink]]
- [[_COMMUNITY_Transformer Tracing Setup|Transformer Tracing Setup]]
- [[_COMMUNITY_Sink Tracing Setup|Sink Tracing Setup]]
- [[_COMMUNITY_Audit OpenSearch Transformer|Audit OpenSearch Transformer]]
- [[_COMMUNITY_OpenAPI Config|OpenAPI Config]]
- [[_COMMUNITY_CI  Docker Publish Workflows|CI / Docker Publish Workflows]]

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
- **Pluggable idempotency store strategy** — service_idempotencyservice, service_inmemoryidempotencyservice, service_redisidempotencyservice, resources_application_idempotency [EXTRACTED 0.85]

## Communities (49 total, 6 thin omitted)

### Community 0 - "Audit Event Core & Redaction"
Cohesion: 0.07
Nodes (30): AuditEvent, Action, List, Rule, String, AuditEvent, ObjectMapper, RedactionService (+22 more)

### Community 1 - "Pipeline & Condition Config"
Cohesion: 0.07
Nodes (27): ConditionRule, List, Map, PipelineProperties, PostConstruct, String, ConditionProperties, ConditionRule (+19 more)

### Community 2 - "Python Plugin Registry"
Cohesion: 0.06
Nodes (42): PluginNotFoundError, PluginRegistry, Plugin registry for dynamically-loaded modules (transformers / sinks).  Hardenin, List the allow-listed plugins (id, type, path)., Full registry view including optional SDK metadata (version, description, proper, Map of discovered-but-excluded plugin id -> {kind, error}., The requested plugin id is not on the discovered allow-list., Discovers, validates, and resolves plugin modules from a fixed set of directorie (+34 more)

### Community 3 - "App Bootstrap & Wiring"
Cohesion: 0.07
Nodes (27): AuditApplication, String, ConditionProperties, SinkProperties, Executor, JsonNode, MeterRegistry, Mono (+19 more)

### Community 4 - "Idempotency / Dedup Guard"
Cohesion: 0.08
Nodes (23): Duration, Duration, Override, String, Duration, Override, String, BeforeEach (+15 more)

### Community 5 - "Pipeline Properties Binding"
Cohesion: 0.15
Nodes (11): String, String, BeforeEach, DisplayName, PipelineProperties, String, Test, ConditionProperties (+3 more)

### Community 6 - "Sink Delivery WebClient"
Cohesion: 0.12
Nodes (22): String, Builder, HttpRetryProperties, JsonNode, Map, MeterRegistry, Mono, ReactiveCircuitBreakerFactory (+14 more)

### Community 7 - "Condition Evaluator Tests"
Cohesion: 0.25
Nodes (8): BeforeEach, ConditionProperties, ConditionRule, DisplayName, JsonNode, String, Test, ConditionEvaluatorTest

### Community 8 - "Transformer Delivery WebClient"
Cohesion: 0.08
Nodes (21): Duration, HttpRetryProperties, Throwable, Builder, HttpRetryProperties, JsonNode, Map, MeterRegistry (+13 more)

### Community 9 - "Poison vs Retryable Failures"
Cohesion: 0.12
Nodes (14): String, Throwable, String, Throwable, RuntimeException, String, Throwable, DisplayName (+6 more)

### Community 10 - "Architecture Principles (AGENTS)"
Cohesion: 0.08
Nodes (26): Async Processing via Message Queue, Env-Only RabbitMQ Credentials (no defaults), Module ID Validation Regex (^[a-zA-Z0-9_]+$), OpenAPI-First Build (generated models), Pipelines Are Configuration, Not Code, Transformer/Sink Plugin Pattern, Backend Is Both Producer and Consumer, Server-Assigned readOnly Timestamp (+18 more)

### Community 11 - "Audit Event REST Controller"
Cohesion: 0.16
Nodes (17): AuditEventApi, AuditEvent, Override, ResponseEntity, String, ResponseEntity, String, AuditPublisherService (+9 more)

### Community 12 - "NetLicensing Sink"
Cohesion: 0.12
Nodes (17): _create_license(), _create_licensee(), NetLicensingClient, process(), _process_item(), NetLicensing Sink - Process checkout transactions and create/update NetLicensing, Process a single purchase order item and create licensee/licenses., Create a new licensee in NetLicensing. (+9 more)

### Community 13 - "Syslog & Webhook Sinks"
Cohesion: 0.13
Nodes (19): format_cef(), format_json(), process(), Syslog Sink - Send events to Syslog server.  This sink sends audit events to a S, Send message via UDP., Send message via TCP., Format event as JSON string., Format event as Common Event Format (CEF).     CEF:Version|Device Vendor|Device (+11 more)

### Community 14 - "Transformation Service Tests"
Cohesion: 0.24
Nodes (9): BeforeEach, DisplayName, JsonNode, Mono, String, SuppressWarnings, Test, WebClient (+1 more)

### Community 15 - "Plugin Registry Internals"
Cohesion: 0.15
Nodes (8): PluginRegistry, List the allow-listed plugins (id, type, path)., Full registry view including optional SDK metadata (version, description, proper, Map of discovered-but-excluded plugin id -> {kind, error}., Discovers, validates, and resolves plugin modules from a fixed set of directorie, :param base_dir: absolute base directory of the service.         :param dir_spec, (Re)scan the configured directories and rebuild the allow-list. Returns self., Re-run discovery (hot-reload of newly mounted bootstrap plugins). Returns self.

### Community 16 - "Plugin SDK Base Classes"
Cohesion: 0.19
Nodes (10): ABC, BaseSink, BaseTransformer, Any, Optional SDK for AuditFlow plugins (P2-5).  Plugins stay simple: a transformer m, Optional base class for transformer plugins., Reshape/enrich the event and return the new event dict., Optional base class for sink plugins. (+2 more)

### Community 17 - "Transformer Discovery (Local/K8s)"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesDiscoveryService, LocalDiscoveryService, TransformerDiscovery

### Community 18 - "Sink Discovery (Local/K8s)"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesSinkDiscovery, LocalSinkDiscovery, SinkDiscovery

### Community 19 - "In-Memory Idempotency Tests"
Cohesion: 0.35
Nodes (4): BeforeEach, DisplayName, Test, InMemoryIdempotencyServiceTest

### Community 20 - "Plugin SDK Property Helpers"
Cohesion: 0.18
Nodes (10): BaseSink, BaseTransformer, Any, Optional SDK for AuditFlow plugins (P2-5).  Plugins stay simple: a transformer m, Optional base class for transformer plugins., Reshape/enrich the event and return the new event dict., Optional base class for sink plugins., Deliver the event to the destination and return a result dict. (+2 more)

### Community 21 - "Datadog Sink & Sink API Tests"
Cohesion: 0.15
Nodes (5): process(), Datadog Sink - forward audit events to the Datadog Logs intake API., Send a single audit event to the Datadog Logs intake API., API-level tests for the sink service (P1-3)., test_datadog_sink_missing_api_key_errors()

### Community 22 - "Correlation ID Filter"
Cohesion: 0.31
Nodes (7): Override, String, CorrelationIdFilter, FilterChain, HttpServletRequest, HttpServletResponse, OncePerRequestFilter

### Community 23 - "Audit Subscriber (Consumer)"
Cohesion: 0.29
Nodes (6): Bean, PostConstruct, String, AuditService, Consumer, AuditSubscriberService

### Community 24 - "Idempotency Service Tests"
Cohesion: 0.40
Nodes (4): BeforeEach, DisplayName, Test, IdempotencyServiceTest

### Community 25 - "Zero Pass-Through Transformer"
Cohesion: 0.22
Nodes (8): PluginRegistry.resolve (transformer), Transforms Labs64.IO AuditFlow JSON structures based on a transformer ID.      T, transform(), test_zero_transform_is_passthrough(), Transformer transform(input_data) contract, Pass-through transformer: returns the input event unchanged., This is the 'zero' transformation.     It performs no transformation and simply, transform()

### Community 26 - "Logging & Splunk Sinks"
Cohesion: 0.22
Nodes (7): Sink process(event_data, properties) contract, process(), Logging Sink - Simple sink that logs events.  This sink writes audit events to t, Process an audit event by logging it.      Args:         event_data: The transfo, process(), Splunk Sink - forward audit events to a Splunk HTTP Event Collector (HEC)., Send a single audit event to a Splunk HEC endpoint.

### Community 27 - "Transformer FastAPI App"
Cohesion: 0.25
Nodes (7): list_transformers(), List all available (allow-listed) transformer modules. Also doubles as the conta, List all available transformer modules.      Returns a list of available transfo, Detailed registry view: per-transformer version, description, and documented pro, Re-scan the transformer directories (hot-reload of newly mounted bootstrap modul, registry_details(), registry_reload()

### Community 28 - "AWS CloudWatch Sink"
Cohesion: 0.32
Nodes (7): ensure_log_group(), ensure_log_stream(), process(), AWS CloudWatch Logs Sink - Send events to AWS CloudWatch Logs.  This sink sends, Ensure log group exists, create if it doesn't., Ensure log stream exists, create if it doesn't., Process an audit event by sending it to CloudWatch Logs.      Args:         even

### Community 29 - "Quarantine Service Tests"
Cohesion: 0.43
Nodes (4): BeforeEach, DisplayName, Test, QuarantineServiceTest

### Community 30 - "Plugin Resolve & Errors"
Cohesion: 0.29
Nodes (5): PluginNotFoundError, Plugin registry for dynamically-loaded modules (transformers / sinks).  Hardenin, The requested plugin id is not on the discovered allow-list., Return the entry-point callable for an allow-listed plugin.          :raises Plu, Exception

### Community 32 - "Publish Exception"
Cohesion: 0.33
Nodes (4): String, PublishException, RuntimeException, Throwable

### Community 33 - "Snowflake Sink"
Cohesion: 0.33
Nodes (5): Raise ValueError if any required property key is missing/empty. Convenience for, require_properties(), process(), Snowflake Sink - insert audit events into a Snowflake table as a VARIANT.  Requi, Insert a single audit event into a Snowflake VARIANT column.

### Community 34 - "Plugin Load & CI Flow"
Cohesion: 0.40
Nodes (6): PluginRegistry._load_one (sink), PluginRegistry.discover (sink), sink FastAPI app, transformer FastAPI app, CI workflow (build & test all three), test_discovers_and_resolves_valid_plugin (sink)

### Community 35 - "Sink Endpoint & Tests"
Cohesion: 0.33
Nodes (6): PluginRegistry.resolve (sink), Send transformed audit events to a destination sink.      This endpoint dynamica, Send transformed audit events to a destination sink.      The sink is resolved f, sink(), test_malformed_id_raises_not_found (sink), test_logging_sink_processes_event()

### Community 36 - "AWS S3 Sink"
Cohesion: 0.40
Nodes (5): build_object_key(), process(), AWS S3 Sink - Store events in Amazon S3.  This sink uploads audit events to AWS, Build S3 object key with optional tenantId and date partitioning., Process an audit event by uploading it to AWS S3.      Args:         event_data:

### Community 37 - "Azure Blob Sink"
Cohesion: 0.40
Nodes (5): build_blob_name(), process(), Azure Blob Storage Sink - Store events in Azure Blob Storage.  This sink uploads, Build blob name with optional date partitioning., Process an audit event by uploading it to Azure Blob Storage.      Args:

### Community 38 - "GCS Sink"
Cohesion: 0.40
Nodes (5): build_object_name(), process(), Google Cloud Storage Sink - Store events in Google Cloud Storage.  This sink upl, Build GCS object name with optional date partitioning., Process an audit event by uploading it to Google Cloud Storage.      Args:

### Community 39 - "Jackson Config"
Cohesion: 0.60
Nodes (3): Bean, ObjectMapper, JacksonConfig

### Community 40 - "Pipeline Executor Config"
Cohesion: 0.60
Nodes (3): Bean, Executor, PipelineExecutorConfig

### Community 41 - "Audit Loki Transformer"
Cohesion: 0.50
Nodes (4): get_log_level(), Maps a status string to a log level string., Transforms a Labs64.IO AuditFlow JSON structure into a Loki-compatible payload., transform()

### Community 42 - "Loki Sink"
Cohesion: 0.50
Nodes (3): process(), Loki Sink - Send events to Grafana Loki for log aggregation.  This sink sends tr, Process an audit event by sending it to Grafana Loki.      Args:         event_d

### Community 43 - "OpenSearch Sink"
Cohesion: 0.50
Nodes (3): process(), OpenSearch Sink - Send events to OpenSearch/Elasticsearch.  This sink sends tran, Process an audit event by sending it to OpenSearch.      Args:         event_dat

## Knowledge Gaps
- **63 isolated node(s):** `String`, `Override`, `String`, `OpenAPIConfig`, `String` (+58 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **6 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Exception` connect `Plugin Resolve & Errors` to `Python Plugin Registry`, `Audit Event REST Controller`?**
  _High betweenness centrality (0.242) - this node is a cross-community bridge._
- **Why does `PluginNotFoundError` connect `Python Plugin Registry` to `Sink Endpoint & Tests`, `Plugin Resolve & Errors`?**
  _High betweenness centrality (0.177) - this node is a cross-community bridge._
- **What connects `String`, `Override`, `String` to the rest of the system?**
  _171 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Audit Event Core & Redaction` be split into smaller, more focused modules?**
  _Cohesion score 0.0673076923076923 - nodes in this community are weakly interconnected._
- **Should `Pipeline & Condition Config` be split into smaller, more focused modules?**
  _Cohesion score 0.06557377049180328 - nodes in this community are weakly interconnected._
- **Should `Python Plugin Registry` be split into smaller, more focused modules?**
  _Cohesion score 0.05649350649350649 - nodes in this community are weakly interconnected._
- **Should `App Bootstrap & Wiring` be split into smaller, more focused modules?**
  _Cohesion score 0.07315233785822021 - nodes in this community are weakly interconnected._