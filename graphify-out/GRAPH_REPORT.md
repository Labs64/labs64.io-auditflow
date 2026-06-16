# Graph Report - .  (2026-06-16)

## Corpus Check
- Corpus is ~18,913 words - fits in a single context window. You may not need a graph.

## Summary
- 495 nodes · 953 edges · 42 communities (33 shown, 9 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 135 edges (avg confidence: 0.8)
- Token cost: 21,000 input · 2,800 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Pipeline Config & Audit Tests|Pipeline Config & Audit Tests]]
- [[_COMMUNITY_AuditFlow Configuration Binding|AuditFlow Configuration Binding]]
- [[_COMMUNITY_Condition Evaluator Tests|Condition Evaluator Tests]]
- [[_COMMUNITY_Sink Service & WebClient|Sink Service & WebClient]]
- [[_COMMUNITY_NetLicensing Sink|NetLicensing Sink]]
- [[_COMMUNITY_Architecture & Design Rationale|Architecture & Design Rationale]]
- [[_COMMUNITY_Idempotency Service|Idempotency Service]]
- [[_COMMUNITY_Audit Pipeline Orchestration|Audit Pipeline Orchestration]]
- [[_COMMUNITY_REST API & Event Publishing|REST API & Event Publishing]]
- [[_COMMUNITY_Global Exception Handling|Global Exception Handling]]
- [[_COMMUNITY_K8s Sink Discovery Tests|K8s Sink Discovery Tests]]
- [[_COMMUNITY_Transformation Service Tests|Transformation Service Tests]]
- [[_COMMUNITY_Transformer Discovery|Transformer Discovery]]
- [[_COMMUNITY_Sink Discovery|Sink Discovery]]
- [[_COMMUNITY_Syslog Sink|Syslog Sink]]
- [[_COMMUNITY_Correlation ID Filter|Correlation ID Filter]]
- [[_COMMUNITY_Audit Subscriber (Consumer)|Audit Subscriber (Consumer)]]
- [[_COMMUNITY_Webhook Sink|Webhook Sink]]
- [[_COMMUNITY_AWS CloudWatch Sink|AWS CloudWatch Sink]]
- [[_COMMUNITY_Quarantine Service Tests|Quarantine Service Tests]]
- [[_COMMUNITY_Publish Exception|Publish Exception]]
- [[_COMMUNITY_AWS S3 Sink|AWS S3 Sink]]
- [[_COMMUNITY_Azure Blob Sink|Azure Blob Sink]]
- [[_COMMUNITY_Google Cloud Storage Sink|Google Cloud Storage Sink]]
- [[_COMMUNITY_Jackson Config|Jackson Config]]
- [[_COMMUNITY_Pipeline Executor Config|Pipeline Executor Config]]
- [[_COMMUNITY_Quarantine Service|Quarantine Service]]
- [[_COMMUNITY_Sink Service App (FastAPI)|Sink Service App (FastAPI)]]
- [[_COMMUNITY_Transformer Service App (FastAPI)|Transformer Service App (FastAPI)]]
- [[_COMMUNITY_Audit Loki Transformer|Audit Loki Transformer]]
- [[_COMMUNITY_Application Entrypoint|Application Entrypoint]]
- [[_COMMUNITY_Sink Discovery Interface|Sink Discovery Interface]]
- [[_COMMUNITY_Transformer Discovery Interface|Transformer Discovery Interface]]
- [[_COMMUNITY_Logging Sink|Logging Sink]]
- [[_COMMUNITY_Loki Sink|Loki Sink]]
- [[_COMMUNITY_OpenSearch Sink|OpenSearch Sink]]
- [[_COMMUNITY_Transformer Tracing Setup|Transformer Tracing Setup]]
- [[_COMMUNITY_Sink Tracing Setup|Sink Tracing Setup]]
- [[_COMMUNITY_Audit OpenSearch Transformer|Audit OpenSearch Transformer]]
- [[_COMMUNITY_Zero Transformer (Pass-through)|Zero Transformer (Pass-through)]]
- [[_COMMUNITY_OpenAPI Config|OpenAPI Config]]
- [[_COMMUNITY_CICD Workflows|CI/CD Workflows]]

## God Nodes (most connected - your core abstractions)
1. `ConditionEvaluatorTest` - 30 edges
2. `Test` - 26 edges
3. `DisplayName` - 26 edges
4. `String` - 16 edges
5. `AuditServiceTest` - 16 edges
6. `Test` - 13 edges
7. `DisplayName` - 13 edges
8. `PipelineProperties` - 11 edges
9. `NetLicensingClient` - 11 edges
10. `SinkServiceTest` - 9 edges

## Surprising Connections (you probably didn't know these)
- `Infra-Only Stack (RabbitMQ)` --semantically_similar_to--> `RabbitMQ Broker (compose)`  [INFERRED] [semantically similar]
  docker-compose-infra.yml → docker-compose.yml
- `OpenAPI-First Build (generated models)` --rationale_for--> `publishEvent Operation (POST /api/v1/audit/publish)`  [INFERRED]
  AGENTS.md → auditflow-be/src/main/resources/openapi/openapi-audit-v1.yaml
- `Pipelines Are Configuration, Not Code` --rationale_for--> `auditflow.pipelines Configuration`  [INFERRED]
  AGENTS.md → auditflow-be/src/main/resources/application.yml
- `Server-Assigned readOnly Timestamp` --rationale_for--> `AuditEvent Schema`  [INFERRED]
  AGENTS.md → auditflow-be/src/main/resources/openapi/openapi-audit-v1.yaml
- `Async Processing via Message Queue` --rationale_for--> `labs64-audit-topic (Spring Cloud Stream)`  [INFERRED]
  AGENTS.md → auditflow-be/src/main/resources/application.yml

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **End-to-End Audit Event Flow** — openapi_openapi_audit_v1_publishevent, resources_application_audit_out_0, resources_application_audit_topic, resources_application_audit_in_0, resources_application_pipelines [EXTRACTED 1.00]
- **CI Builds All Three Services** — labs64io_ci_build_test, labs64io_docker_publish_workflow, docker_compose_happy_path_pipeline [INFERRED 0.75]
- **Local Dev Stack Composition** — docker_compose_rabbitmq, docker_compose_jaeger_otlp, docker_compose_infra_rabbitmq_only [EXTRACTED 1.00]

## Communities (42 total, 9 thin omitted)

### Community 0 - "Pipeline Config & Audit Tests"
Cohesion: 0.13
Nodes (14): ConditionProperties, String, String, String, BeforeEach, DisplayName, PipelineProperties, String (+6 more)

### Community 1 - "AuditFlow Configuration Binding"
Cohesion: 0.09
Nodes (16): ConditionRule, Map, PipelineProperties, PostConstruct, String, ConditionProperties, ConditionRule, JsonNode (+8 more)

### Community 2 - "Condition Evaluator Tests"
Cohesion: 0.24
Nodes (8): BeforeEach, ConditionProperties, ConditionRule, DisplayName, JsonNode, String, Test, ConditionEvaluatorTest

### Community 3 - "Sink Service & WebClient"
Cohesion: 0.16
Nodes (15): Builder, JsonNode, Map, SinkDiscovery, String, WebClient, BeforeEach, DisplayName (+7 more)

### Community 4 - "NetLicensing Sink"
Cohesion: 0.11
Nodes (18): _create_license(), _create_licensee(), NetLicensingClient, process(), _process_item(), NetLicensing Sink - Process checkout transactions and create/update NetLicensing, Process a single purchase order item and create licensee/licenses., Create a new licensee in NetLicensing. (+10 more)

### Community 5 - "Architecture & Design Rationale"
Cohesion: 0.08
Nodes (26): Async Processing via Message Queue, Env-Only RabbitMQ Credentials (no defaults), Module ID Validation Regex (^[a-zA-Z0-9_]+$), OpenAPI-First Build (generated models), Pipelines Are Configuration, Not Code, Transformer/Sink Plugin Pattern, Backend Is Both Producer and Consumer, Server-Assigned readOnly Timestamp (+18 more)

### Community 6 - "Idempotency Service"
Cohesion: 0.13
Nodes (13): Builder, JsonNode, Map, String, TransformerDiscovery, WebClient, BeforeEach, DisplayName (+5 more)

### Community 7 - "Audit Pipeline Orchestration"
Cohesion: 0.16
Nodes (13): Executor, JsonNode, MeterRegistry, ObjectMapper, PipelineProperties, PostConstruct, AuditFlowConfiguration, ConditionEvaluator (+5 more)

### Community 8 - "REST API & Event Publishing"
Cohesion: 0.15
Nodes (12): AuditEventApi, AuditEvent, Override, ResponseEntity, String, AuditEvent, ObjectMapper, StreamBridge (+4 more)

### Community 9 - "Global Exception Handling"
Cohesion: 0.32
Nodes (10): ResponseEntity, String, ErrorCode, ErrorResponse, Exception, GlobalExceptionHandler, ExceptionHandler, IllegalArgumentException (+2 more)

### Community 10 - "K8s Sink Discovery Tests"
Cohesion: 0.26
Nodes (8): BeforeEach, DisplayName, String, SuppressWarnings, Test, CsvSource, ParameterizedTest, KubernetesSinkDiscoveryTest

### Community 11 - "Transformation Service Tests"
Cohesion: 0.30
Nodes (7): BeforeEach, DisplayName, JsonNode, String, SuppressWarnings, Test, TransformationServiceTest

### Community 12 - "Transformer Discovery"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesDiscoveryService, LocalDiscoveryService, TransformerDiscovery

### Community 13 - "Sink Discovery"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesSinkDiscovery, LocalSinkDiscovery, SinkDiscovery

### Community 14 - "Syslog Sink"
Cohesion: 0.23
Nodes (11): format_cef(), format_json(), process(), Syslog Sink - Send events to Syslog server.  This sink sends audit events to a S, Send message via UDP., Send message via TCP., Format event as JSON string., Format event as Common Event Format (CEF).     CEF:Version|Device Vendor|Device (+3 more)

### Community 15 - "Correlation ID Filter"
Cohesion: 0.31
Nodes (7): Override, String, CorrelationIdFilter, FilterChain, HttpServletRequest, HttpServletResponse, OncePerRequestFilter

### Community 16 - "Audit Subscriber (Consumer)"
Cohesion: 0.29
Nodes (6): Bean, PostConstruct, String, AuditService, Consumer, AuditSubscriberService

### Community 17 - "Webhook Sink"
Cohesion: 0.29
Nodes (9): flatten_dict(), generate_signature(), prepare_payload(), process(), Webhook Sink - Send events to HTTP webhooks (Zapier, Make, n8n, etc.).  This sin, Prepare payload based on content type., Generate HMAC signature for webhook verification (GitHub/Zapier style).     Uses, Flatten nested dictionary for URL encoding. (+1 more)

### Community 18 - "AWS CloudWatch Sink"
Cohesion: 0.32
Nodes (7): ensure_log_group(), ensure_log_stream(), process(), AWS CloudWatch Logs Sink - Send events to AWS CloudWatch Logs.  This sink sends, Ensure log group exists, create if it doesn't., Ensure log stream exists, create if it doesn't., Process an audit event by sending it to CloudWatch Logs.      Args:         even

### Community 19 - "Quarantine Service Tests"
Cohesion: 0.43
Nodes (4): BeforeEach, DisplayName, Test, QuarantineServiceTest

### Community 20 - "Publish Exception"
Cohesion: 0.33
Nodes (4): String, PublishException, RuntimeException, Throwable

### Community 21 - "AWS S3 Sink"
Cohesion: 0.40
Nodes (5): build_object_key(), process(), AWS S3 Sink - Store events in Amazon S3.  This sink uploads audit events to AWS, Build S3 object key with optional tenantId and date partitioning., Process an audit event by uploading it to AWS S3.      Args:         event_data:

### Community 22 - "Azure Blob Sink"
Cohesion: 0.40
Nodes (5): build_blob_name(), process(), Azure Blob Storage Sink - Store events in Azure Blob Storage.  This sink uploads, Build blob name with optional date partitioning., Process an audit event by uploading it to Azure Blob Storage.      Args:

### Community 23 - "Google Cloud Storage Sink"
Cohesion: 0.40
Nodes (5): build_object_name(), process(), Google Cloud Storage Sink - Store events in Google Cloud Storage.  This sink upl, Build GCS object name with optional date partitioning., Process an audit event by uploading it to Google Cloud Storage.      Args:

### Community 24 - "Jackson Config"
Cohesion: 0.60
Nodes (3): Bean, ObjectMapper, JacksonConfig

### Community 25 - "Pipeline Executor Config"
Cohesion: 0.60
Nodes (3): Bean, Executor, PipelineExecutorConfig

### Community 26 - "Quarantine Service"
Cohesion: 0.60
Nodes (3): MeterRegistry, StreamBridge, QuarantineService

### Community 27 - "Sink Service App (FastAPI)"
Cohesion: 0.40
Nodes (4): list_sinks(), List all available sink modules.      Returns a list of available sink IDs that, Send transformed audit events to a destination sink.      This endpoint dynamica, sink()

### Community 28 - "Transformer Service App (FastAPI)"
Cohesion: 0.40
Nodes (4): list_transformers(), List all available transformer modules.      Returns a list of available transfo, Transforms Labs64.IO AuditFlow JSON structures based on a transformer ID.      T, transform()

### Community 29 - "Audit Loki Transformer"
Cohesion: 0.50
Nodes (4): get_log_level(), Maps a status string to a log level string., Transforms a Labs64.IO AuditFlow JSON structure into a Loki-compatible payload., transform()

### Community 33 - "Logging Sink"
Cohesion: 0.50
Nodes (3): process(), Logging Sink - Simple sink that logs events.  This sink writes audit events to t, Process an audit event by logging it.      Args:         event_data: The transfo

### Community 34 - "Loki Sink"
Cohesion: 0.50
Nodes (3): process(), Loki Sink - Send events to Grafana Loki for log aggregation.  This sink sends tr, Process an audit event by sending it to Grafana Loki.      Args:         event_d

### Community 35 - "OpenSearch Sink"
Cohesion: 0.50
Nodes (3): process(), OpenSearch Sink - Send events to OpenSearch/Elasticsearch.  This sink sends tran, Process an audit event by sending it to OpenSearch.      Args:         event_dat

## Knowledge Gaps
- **47 isolated node(s):** `String`, `Override`, `String`, `OpenAPIConfig`, `String` (+42 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **9 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `List` connect `AuditFlow Configuration Binding` to `Pipeline Config & Audit Tests`, `Condition Evaluator Tests`, `Transformer Discovery`, `Audit Pipeline Orchestration`?**
  _High betweenness centrality (0.043) - this node is a cross-community bridge._
- **Why does `String` connect `AuditFlow Configuration Binding` to `K8s Sink Discovery Tests`?**
  _High betweenness centrality (0.043) - this node is a cross-community bridge._
- **Why does `PublishException` connect `REST API & Event Publishing` to `Global Exception Handling`?**
  _High betweenness centrality (0.036) - this node is a cross-community bridge._
- **What connects `String`, `Override`, `String` to the rest of the system?**
  _105 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Pipeline Config & Audit Tests` be split into smaller, more focused modules?**
  _Cohesion score 0.13399778516057587 - nodes in this community are weakly interconnected._
- **Should `AuditFlow Configuration Binding` be split into smaller, more focused modules?**
  _Cohesion score 0.09175377468060394 - nodes in this community are weakly interconnected._
- **Should `NetLicensing Sink` be split into smaller, more focused modules?**
  _Cohesion score 0.11396011396011396 - nodes in this community are weakly interconnected._