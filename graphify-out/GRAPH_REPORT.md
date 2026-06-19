# Graph Report - .  (2026-06-19)

## Corpus Check
- 19 files · ~27,406 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 601 nodes · 1163 edges · 46 communities (36 shown, 10 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 102 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Pipeline Configuration & Conditions|Pipeline Configuration & Conditions]]
- [[_COMMUNITY_HTTP Client & Retry Config|HTTP Client & Retry Config]]
- [[_COMMUNITY_WebClient & Timeout Settings|WebClient & Timeout Settings]]
- [[_COMMUNITY_Condition Evaluator Tests|Condition Evaluator Tests]]
- [[_COMMUNITY_Application Bootstrap|Application Bootstrap]]
- [[_COMMUNITY_REST API Layer|REST API Layer]]
- [[_COMMUNITY_Exception Handling|Exception Handling]]
- [[_COMMUNITY_NetLicensing Sink|NetLicensing Sink]]
- [[_COMMUNITY_Syslog Sink|Syslog Sink]]
- [[_COMMUNITY_Audit Service Tests|Audit Service Tests]]
- [[_COMMUNITY_Redaction Service (PIIPCI)|Redaction Service (PII/PCI)]]
- [[_COMMUNITY_Docker Compose & Infrastructure|Docker Compose & Infrastructure]]
- [[_COMMUNITY_Sink & Transform Tests|Sink & Transform Tests]]
- [[_COMMUNITY_Audit Pipeline Orchestration|Audit Pipeline Orchestration]]
- [[_COMMUNITY_Kubernetes Service Discovery|Kubernetes Service Discovery]]
- [[_COMMUNITY_Local Service Discovery|Local Service Discovery]]
- [[_COMMUNITY_Idempotency Service Tests|Idempotency Service Tests]]
- [[_COMMUNITY_Python Plugin Registry Tests|Python Plugin Registry Tests]]
- [[_COMMUNITY_Correlation ID Filter|Correlation ID Filter]]
- [[_COMMUNITY_Redis Idempotency Store|Redis Idempotency Store]]
- [[_COMMUNITY_Event Consumer Setup|Event Consumer Setup]]
- [[_COMMUNITY_AWS CloudWatch Sink|AWS CloudWatch Sink]]
- [[_COMMUNITY_Publisher Exceptions|Publisher Exceptions]]
- [[_COMMUNITY_AWS S3 Sink|AWS S3 Sink]]
- [[_COMMUNITY_Azure Blob Sink|Azure Blob Sink]]
- [[_COMMUNITY_GCS Sink|GCS Sink]]
- [[_COMMUNITY_Jackson JSON Config|Jackson JSON Config]]
- [[_COMMUNITY_Pipeline Executor Config|Pipeline Executor Config]]
- [[_COMMUNITY_Loki Transformer|Loki Transformer]]
- [[_COMMUNITY_Datadog Sink|Datadog Sink]]
- [[_COMMUNITY_Logging Sink|Logging Sink]]
- [[_COMMUNITY_Loki Sink|Loki Sink]]
- [[_COMMUNITY_OpenSearch Sink|OpenSearch Sink]]
- [[_COMMUNITY_Snowflake Sink|Snowflake Sink]]
- [[_COMMUNITY_Splunk Sink|Splunk Sink]]
- [[_COMMUNITY_Pass-Through Transformer|Pass-Through Transformer]]
- [[_COMMUNITY_Transformer OpenTelemetry|Transformer OpenTelemetry]]
- [[_COMMUNITY_Sink OpenTelemetry|Sink OpenTelemetry]]
- [[_COMMUNITY_OpenSearch Transformer|OpenSearch Transformer]]
- [[_COMMUNITY_OpenAPI Config|OpenAPI Config]]
- [[_COMMUNITY_CICD Workflows|CI/CD Workflows]]
- [[_COMMUNITY_Abstract Base Class|Abstract Base Class]]
- [[_COMMUNITY_CI Build Workflow|CI Build Workflow]]
- [[_COMMUNITY_Redaction Concept|Redaction Concept]]
- [[_COMMUNITY_Sink Plugin Discovery Test|Sink Plugin Discovery Test]]
- [[_COMMUNITY_Sink Validation Test|Sink Validation Test]]

## God Nodes (most connected - your core abstractions)
1. `ConditionEvaluatorTest` - 30 edges
2. `Test` - 26 edges
3. `DisplayName` - 26 edges
4. `String` - 16 edges
5. `PipelineProperties` - 15 edges
6. `AuditService` - 14 edges
7. `IdempotencyService` - 11 edges
8. `NetLicensingClient` - 11 edges
9. `InMemoryIdempotencyService` - 11 edges
10. `RedactionServiceTest` - 10 edges

## Surprising Connections (you probably didn't know these)
- `Infra-Only Stack (RabbitMQ)` --semantically_similar_to--> `RabbitMQ Broker (compose)`  [INFERRED] [semantically similar]
  docker-compose-infra.yml → docker-compose.yml
- `Local Happy-Path Pipeline` --references--> `auditflow.pipelines Configuration`  [INFERRED]
  docker-compose.yml → auditflow-be/src/main/resources/application.yml
- `Pluggable Storage Destinations` --conceptually_related_to--> `Sink Discovery Mode (local|kubernetes)`  [INFERRED]
  README.md → auditflow-be/src/main/resources/application.yml
- `InMemoryIdempotencyService` --implements--> `IdempotencyService`  [EXTRACTED]
  auditflow-be/src/main/java/io/labs64/audit/service/InMemoryIdempotencyService.java → auditflow-be/src/main/java/io/labs64/audit/service/IdempotencyService.java
- `RedisIdempotencyService` --implements--> `IdempotencyService`  [EXTRACTED]
  auditflow-be/src/main/java/io/labs64/audit/service/RedisIdempotencyService.java → auditflow-be/src/main/java/io/labs64/audit/service/IdempotencyService.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **End-to-End Audit Pipeline Flow** — agents_audit_event_controller, agents_rabbitmq, agents_audit_subscriber_service, agents_audit_service, agents_transformation_service, agents_sink_service_java [EXTRACTED 1.00]
- **Pluggable Service Discovery Pattern** — agents_transformer_discovery, agents_sink_discovery, agents_local_discovery_service, agents_local_sink_discovery, agents_kubernetes_discovery_service, agents_kubernetes_sink_discovery [EXTRACTED 1.00]
- **Dynamic Plugin Loading Pattern (Transformer and Sink)** — agents_plugin_pattern, agents_transformer_service, agents_sink_service, agents_transformer, agents_sink [EXTRACTED 1.00]

## Communities (46 total, 10 thin omitted)

### Community 0 - "Pipeline Configuration & Conditions"
Cohesion: 0.06
Nodes (24): ConditionProperties, ConditionRule, List, Map, PipelineProperties, PostConstruct, SinkProperties, String (+16 more)

### Community 1 - "HTTP Client & Retry Config"
Cohesion: 0.06
Nodes (35): Duration, HttpRetryProperties, Throwable, String, Builder, HttpRetryProperties, JsonNode, Map (+27 more)

### Community 2 - "WebClient & Timeout Settings"
Cohesion: 0.10
Nodes (19): Duration, Duration, Override, String, Duration, BeforeEach, DisplayName, Test (+11 more)

### Community 3 - "Condition Evaluator Tests"
Cohesion: 0.25
Nodes (8): BeforeEach, ConditionProperties, ConditionRule, DisplayName, JsonNode, String, Test, ConditionEvaluatorTest

### Community 4 - "Application Bootstrap"
Cohesion: 0.11
Nodes (21): AuditApplication, String, Executor, JsonNode, MeterRegistry, Mono, ObjectMapper, PipelineProperties (+13 more)

### Community 5 - "REST API Layer"
Cohesion: 0.15
Nodes (18): AuditEventApi, AuditEvent, Override, ResponseEntity, String, ResponseEntity, String, AuditPublisherService (+10 more)

### Community 6 - "Exception Handling"
Cohesion: 0.12
Nodes (14): String, Throwable, String, Throwable, RuntimeException, String, Throwable, DisplayName (+6 more)

### Community 7 - "NetLicensing Sink"
Cohesion: 0.12
Nodes (17): _create_license(), _create_licensee(), NetLicensingClient, process(), _process_item(), NetLicensing Sink - Process checkout transactions and create/update NetLicensing, Process a single purchase order item and create licensee/licenses., Create a new licensee in NetLicensing. (+9 more)

### Community 8 - "Syslog Sink"
Cohesion: 0.13
Nodes (19): format_cef(), format_json(), process(), Syslog Sink - Send events to Syslog server.  This sink sends audit events to a S, Send message via UDP., Send message via TCP., Format event as JSON string., Format event as Common Event Format (CEF).     CEF:Version|Device Vendor|Device (+11 more)

### Community 9 - "Audit Service Tests"
Cohesion: 0.23
Nodes (6): String, BeforeEach, DisplayName, Test, IdempotencyService, IdempotencyServiceTest

### Community 10 - "Redaction Service (PII/PCI)"
Cohesion: 0.32
Nodes (8): Action, DisplayName, JsonNode, RedactionService, Rule, String, Test, RedactionServiceTest

### Community 11 - "Docker Compose & Infrastructure"
Cohesion: 0.12
Nodes (17): Local Happy-Path Pipeline, Infra-Only Stack (RabbitMQ), Jaeger OTLP Tracing, RabbitMQ Broker (compose), AuditEvent Schema, bearerAuth (JWT) Security Scheme, ErrorResponse Schema, publishEvent Operation (POST /api/v1/audit/publish) (+9 more)

### Community 12 - "Sink & Transform Tests"
Cohesion: 0.26
Nodes (8): BeforeEach, DisplayName, String, SuppressWarnings, Test, CsvSource, ParameterizedTest, KubernetesSinkDiscoveryTest

### Community 13 - "Audit Pipeline Orchestration"
Cohesion: 0.20
Nodes (8): MeterRegistry, StreamBridge, String, BeforeEach, DisplayName, Test, QuarantineService, QuarantineServiceTest

### Community 14 - "Kubernetes Service Discovery"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesDiscoveryService, LocalDiscoveryService, TransformerDiscovery

### Community 15 - "Local Service Discovery"
Cohesion: 0.17
Nodes (8): KubernetesClient, Override, String, Override, String, KubernetesSinkDiscovery, LocalSinkDiscovery, SinkDiscovery

### Community 16 - "Idempotency Service Tests"
Cohesion: 0.35
Nodes (4): BeforeEach, DisplayName, Test, InMemoryIdempotencyServiceTest

### Community 17 - "Python Plugin Registry Tests"
Cohesion: 0.32
Nodes (11): Path, Unit tests for the plugin registry allow-list / hardening (P1-4)., _registry(), test_details_captures_optional_metadata(), test_discovers_and_resolves_valid_plugin(), test_import_error_is_excluded_without_crashing(), test_malformed_id_raises_not_found(), test_missing_entry_point_is_excluded() (+3 more)

### Community 18 - "Correlation ID Filter"
Cohesion: 0.31
Nodes (7): Override, String, CorrelationIdFilter, FilterChain, HttpServletRequest, HttpServletResponse, OncePerRequestFilter

### Community 19 - "Redis Idempotency Store"
Cohesion: 0.53
Nodes (3): Override, String, RedisIdempotencyService

### Community 20 - "Event Consumer Setup"
Cohesion: 0.29
Nodes (6): Bean, PostConstruct, String, AuditService, Consumer, AuditSubscriberService

### Community 21 - "AWS CloudWatch Sink"
Cohesion: 0.32
Nodes (7): ensure_log_group(), ensure_log_stream(), process(), AWS CloudWatch Logs Sink - Send events to AWS CloudWatch Logs.  This sink sends, Ensure log group exists, create if it doesn't., Ensure log stream exists, create if it doesn't., Process an audit event by sending it to CloudWatch Logs.      Args:         even

### Community 22 - "Publisher Exceptions"
Cohesion: 0.33
Nodes (4): String, PublishException, RuntimeException, Throwable

### Community 23 - "AWS S3 Sink"
Cohesion: 0.40
Nodes (5): build_object_key(), process(), AWS S3 Sink - Store events in Amazon S3.  This sink uploads audit events to AWS, Build S3 object key with optional tenantId and date partitioning., Process an audit event by uploading it to AWS S3.      Args:         event_data:

### Community 24 - "Azure Blob Sink"
Cohesion: 0.40
Nodes (5): build_blob_name(), process(), Azure Blob Storage Sink - Store events in Azure Blob Storage.  This sink uploads, Build blob name with optional date partitioning., Process an audit event by uploading it to Azure Blob Storage.      Args:

### Community 25 - "GCS Sink"
Cohesion: 0.40
Nodes (5): build_object_name(), process(), Google Cloud Storage Sink - Store events in Google Cloud Storage.  This sink upl, Build GCS object name with optional date partitioning., Process an audit event by uploading it to Google Cloud Storage.      Args:

### Community 26 - "Jackson JSON Config"
Cohesion: 0.60
Nodes (3): Bean, ObjectMapper, JacksonConfig

### Community 27 - "Pipeline Executor Config"
Cohesion: 0.60
Nodes (3): Bean, Executor, PipelineExecutorConfig

### Community 28 - "Loki Transformer"
Cohesion: 0.50
Nodes (4): get_log_level(), Maps a status string to a log level string., Transforms a Labs64.IO AuditFlow JSON structure into a Loki-compatible payload., transform()

### Community 29 - "Datadog Sink"
Cohesion: 0.50
Nodes (3): process(), Datadog Sink - forward audit events to the Datadog Logs intake API., Send a single audit event to the Datadog Logs intake API.

### Community 30 - "Logging Sink"
Cohesion: 0.50
Nodes (3): process(), Logging Sink - Simple sink that logs events.  This sink writes audit events to t, Process an audit event by logging it.      Args:         event_data: The transfo

### Community 31 - "Loki Sink"
Cohesion: 0.50
Nodes (3): process(), Loki Sink - Send events to Grafana Loki for log aggregation.  This sink sends tr, Process an audit event by sending it to Grafana Loki.      Args:         event_d

### Community 32 - "OpenSearch Sink"
Cohesion: 0.50
Nodes (3): process(), OpenSearch Sink - Send events to OpenSearch/Elasticsearch.  This sink sends tran, Process an audit event by sending it to OpenSearch.      Args:         event_dat

### Community 33 - "Snowflake Sink"
Cohesion: 0.50
Nodes (3): process(), Snowflake Sink - insert audit events into a Snowflake table as a VARIANT.  Requi, Insert a single audit event into a Snowflake VARIANT column.

### Community 34 - "Splunk Sink"
Cohesion: 0.50
Nodes (3): process(), Splunk Sink - forward audit events to a Splunk HTTP Event Collector (HEC)., Send a single audit event to a Splunk HEC endpoint.

### Community 35 - "Pass-Through Transformer"
Cohesion: 0.50
Nodes (3): Pass-through transformer: returns the input event unchanged., This is the 'zero' transformation.     It performs no transformation and simply, transform()

## Knowledge Gaps
- **60 isolated node(s):** `String`, `Override`, `String`, `OpenAPIConfig`, `String` (+55 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **10 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `List` connect `Pipeline Configuration & Conditions` to `Redaction Service (PII/PCI)`, `Application Bootstrap`, `Kubernetes Service Discovery`?**
  _High betweenness centrality (0.077) - this node is a cross-community bridge._
- **Why does `Duration` connect `WebClient & Timeout Settings` to `Idempotency Service Tests`, `Audit Service Tests`, `HTTP Client & Retry Config`?**
  _High betweenness centrality (0.064) - this node is a cross-community bridge._
- **Why does `ConditionRule` connect `Pipeline Configuration & Conditions` to `Sink & Transform Tests`?**
  _High betweenness centrality (0.063) - this node is a cross-community bridge._
- **What connects `String`, `Override`, `String` to the rest of the system?**
  _115 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Pipeline Configuration & Conditions` be split into smaller, more focused modules?**
  _Cohesion score 0.06144393241167435 - nodes in this community are weakly interconnected._
- **Should `HTTP Client & Retry Config` be split into smaller, more focused modules?**
  _Cohesion score 0.05706214689265537 - nodes in this community are weakly interconnected._
- **Should `WebClient & Timeout Settings` be split into smaller, more focused modules?**
  _Cohesion score 0.0951219512195122 - nodes in this community are weakly interconnected._