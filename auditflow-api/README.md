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

## Usage

```java
AuditFlowClient client = AuditFlowClient.builder()
        .baseUrl("https://audit.labs64.io/api/v1")
        .token("eyJ...")                       // or .tokenProvider(() -> jwtCache.current())
        .defaultSourceSystem("netlicensing/core")
        .build();

AuditEvent event = AuditEvents.builder()
        .eventType("user.login")
        .extra("userId", "u1")
        .extra("action_status", "SUCCESS")
        .build();

// Synchronous — throws a typed AuditFlowException on failure
PublishResult result = client.publish(event);

// Asynchronous — non-blocking
client.publishAsync(event).thenAccept(r -> ...);

// Fire-and-forget — never throws into your code path
client.fireAndForget(event);
```

## Configuration

| Builder method | Default | Purpose |
|----------------|---------|---------|
| `baseUrl(String)` | (required) | API base URL, e.g. `https://host/api/v1` |
| `token(String)` / `tokenProvider(TokenProvider)` | none | Bearer auth; `tokenProvider` re-evaluated per request |
| `defaultSourceSystem(String)` | none | Used when an event has no `sourceSystem` |
| `connectTimeout(Duration)` | 5s | TCP connect timeout |
| `requestTimeout(Duration)` | 10s | Per-request timeout |
| `retry(RetryPolicy)` | `exponential(3)` | Retries on HTTP 503 only |
| `errorHandler(BiConsumer<AuditEvent,Throwable>)` | logs via `System.Logger` | Sink for `fireAndForget` failures |

## OpenAPI contract

The exact contract is published alongside this artifact as `auditflow-api-<version>-openapi.yaml` and bundled at `openapi/openapi-audit-v1.yaml` on the classpath, so you can reference or re-generate against it.
