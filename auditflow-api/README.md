# AuditFlow API Client (Java)

Minimal-dependency Java client for the [Labs64.IO AuditFlow](https://labs64.io) API.
Wraps `POST /api/v1/audit/publish` with auto event-id, correlation propagation, retries, and synchronous / async / fire-and-forget publishing.

## Install

```xml
<dependency>
    <groupId>io.labs64</groupId>
    <artifactId>auditflow-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Quick Start (Zero-Config)

For containerized environments (Kubernetes/Docker), you can configure the client entirely via standard environment variables (`AUDITFLOW_URL`, `AUDITFLOW_TOKEN`, `AUDITFLOW_DEFAULT_SOURCE`):

```java
AuditFlowClient client = AuditFlowClient.fromEnv();
```

## Advanced Configuration

For advanced configurations, use the fluent builder:

```java
AuditFlowClient client = AuditFlowClient.builder()
        .baseUrl("https://audit.labs64.io/api/v1")
        .token("eyJ...")                       // or .tokenProvider(() -> jwtCache.current())
        .defaultSourceSystem("netlicensing/core")
        // Use for OpenTelemetry trace propagation:
        .correlationIdProvider(() -> Tracer.currentSpan().getTraceId()) 
        .build();
```

## Usage

```java
// Compile-time safe: eventType is required in the constructor
AuditEvent event = AuditEvents.builder("user.login")
        .extra("userId", "u1")
        .extra("action_status", "SUCCESS")
        .build();

// Synchronous — throws a typed AuditFlowException on failure
PublishResult result = client.publish(event);

// Asynchronous — non-blocking
client.publishAsync(event).thenAccept(r -> System.out.println(r.eventId()));

// Fire-and-forget — never throws into your code path
client.fireAndForget(event);
```

## Configuration Options

| Builder method | Default | Purpose |
|----------------|---------|---------|
| `baseUrl(String)` | (required) | API base URL, e.g. `https://host/api/v1` |
| `token(String)` / `tokenProvider(TokenProvider)` | none | Bearer auth; `tokenProvider` re-evaluated per request |
| `defaultSourceSystem(String)` | none | Used when an event has no `sourceSystem` |
| `correlationIdProvider(Supplier<String>)` | none | Dynamically injects OTel / Distributed Trace IDs into events |
| `httpClient(HttpClient)` | internal client | Bring-Your-Own `java.net.http.HttpClient` for fine-grained network control |
| `connectTimeout(Duration)` | 5s | TCP connect timeout |
| `requestTimeout(Duration)` | 10s | Per-request timeout |
| `retry(RetryPolicy)` | `exponential(3)` | Retries on network / HTTP 503 errors |
| `errorHandler(BiConsumer<AuditEvent,Throwable>)` | logs via `System.Logger` | Sink for `fireAndForget` failures |

## Error Handling

If an operation fails after all retries are exhausted, the client throws a unified `AuditFlowException`. 

## OpenAPI contract

The exact contract is published alongside this artifact as `auditflow-api-<version>-openapi.yaml` and bundled at `openapi/openapi-audit-v1.yaml` on the classpath, so you can reference or re-generate against it.
