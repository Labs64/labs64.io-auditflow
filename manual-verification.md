# AuditFlow — Manual Verification Plan

Step-by-step instructions to manually verify core AuditFlow functionality on the lite local stack (no Redis, no Jaeger). Covers four test cases run from a single verification stack.

---

## Preconditions

| Requirement | Check command | Expected |
|---|---|---|
| Docker Engine 24+ | `docker --version` | `Docker version 24+` |
| Docker Compose v2 | `docker compose version` | `v2.x` |
| `just` task runner | `just --version` | any |
| `curl` | `curl --version` | any |
| `jq` (optional, pretty-print) | `jq --version` | any |
| Ports free: 5673, 8080, 8081, 8082, 15673 | `lsof -i :8080 -i :8081 -i :8082 -i :5673 -i :15673` | no output |

**Credentials:** The stack uses `guest`/`guest` by default. Copy `.env.example` to `.env` if you have custom credentials:

```bash
cp .env.example .env   # only if .env does not exist
```

---

## Starting the Verification Stack

The `docker-compose-verify.yml` override (at the repo root) extends the lite stack with:

- **Pipeline 0 (`e2e-logging`)** — no condition, logs at `INFO` → used by TC-1 and TC-4
- **Pipeline 1 (`security-alerts`)** — condition: `eventType = security.alert`, logs at `WARN` → used by TC-2
- **Redaction enabled** — `extra.userId` is masked (`***`), `extra.sessionId` is dropped → used by TC-3

```bash
docker compose -f docker-compose-lite.yml -f docker-compose-verify.yml up --build -d
```

Wait ~60 s for all containers to become healthy:

```bash
docker compose -f docker-compose-lite.yml ps
# All four rows should show "healthy" in the STATUS column
```

Confirm all three services respond:

```bash
curl -s http://localhost:8080/actuator/health | jq -r .status   # UP
curl -s http://localhost:8081/registry | jq '[.transformers[].id]'  # ["audit_loki","audit_opensearch","zero"]
curl -s http://localhost:8082/registry | jq '[.sinks[].id]'        # ["aws_cloudwatch_sink","aws_s3_sink",...]
```

---

## TC-1 — Happy-Path Event Flow

**What it verifies:** An event published via REST travels through RabbitMQ, is picked up by the consumer, passes through the `zero` (pass-through) transformer, and is delivered to the `logging_sink`.

### Publish the event

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0001-000000000001",
    "eventType":   "user.login",
    "sourceSystem": "auth-service",
    "tenantId":    "T-VERIFY-001",
    "extra": {
      "userId":        "alice",
      "sessionId":     "sess-abc-123",
      "action_status": "SUCCESS"
    }
  }'
```

### Where to verify

**Expected HTTP response:** `200 OK`, body `Audit event published successfully`

**Sink logs** — event delivered:

```bash
just log sink
# Ctrl+C to stop tailing
```

Look for a line containing `"eventType": "user.login"`. Because redaction is active, `extra.userId` will be `***` and `extra.sessionId` will be absent — this is expected behaviour.

**RabbitMQ UI** — http://localhost:15673 (guest / guest):

- *Exchanges* → `labs64-audit-topic` → message rate briefly spikes then returns to 0
- *Queues* → `labs64-audit-topic.labs64.io-auditflow` → **Ready** count = 0 (message was consumed)

### Pass criteria

- [x] `200 OK` from the publish endpoint
- [x] Sink log contains an entry with `eventType: user.login`
- [x] RabbitMQ queue depth returns to 0

---

## TC-2 — Pipeline Condition Filtering

**What it verifies:** Pipeline 1 (`security-alerts`) only processes events where `eventType` equals `security.alert`. Other event types are handled by pipeline 0 only.

### Step 2a — Send a non-matching event (pipeline 0 only)

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0002-000000000001",
    "eventType":   "api.call",
    "sourceSystem": "backend-service"
  }'
```

Check sink logs:

```bash
just log sink
```

**Expected:** exactly **one** new log entry at level `INFO`. No `WARNING` entry for this event.

### Step 2b — Send a matching event (both pipelines)

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0002-000000000002",
    "eventType":   "security.alert",
    "sourceSystem": "auth-service",
    "extra": {
      "action_status": "FAILURE"
    }
  }'
```

Check sink logs:

```bash
just log sink
```

**Expected:** **two** new log entries for this event — one at `INFO` (pipeline 0) and one at `WARNING` (pipeline 1 / security-alerts).

### Pass criteria

- [x] Non-matching `api.call` event → 1 sink log entry (INFO), no WARNING
- [x] Matching `security.alert` event → 2 sink log entries (INFO + WARNING)

---

## TC-3 — PII / Sensitive Data Redaction

**What it verifies:** `extra.userId` is replaced with `***` and `extra.sessionId` is dropped before the event enters RabbitMQ. Raw PII never reaches the message broker or any downstream sink.

Redaction applies globally at ingest (inside `AuditPublisherService`, before `StreamBridge.send()`), so it affects every pipeline.

### Publish an event with PII fields

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0003-000000000001",
    "eventType":   "user.profile.updated",
    "sourceSystem": "profile-service",
    "extra": {
      "userId":    "alice",
      "sessionId": "sess-secret-999",
      "action":    "update_email"
    }
  }'
```

### Where to verify

**Sink logs** — the event delivered to the sink must show the redacted payload:

```bash
just log sink
```

Look for the entry with `eventType: user.profile.updated` and confirm:

| Field | Raw value sent | Expected in log |
|---|---|---|
| `extra.userId` | `alice` | `***` |
| `extra.sessionId` | `sess-secret-999` | absent (dropped) |
| `extra.action` | `update_email` | `update_email` (unchanged) |

**RabbitMQ UI** — if you can capture a message in-flight (pause consumption or raise prefetch to 0), inspect the payload and confirm `alice` does not appear.

### Pass criteria

- [x] Sink log shows `extra.userId` = `***`
- [x] Sink log has no `extra.sessionId` field
- [x] `extra.action` is unchanged (`update_email`)
- [x] The raw value `alice` and `sess-secret-999` do not appear anywhere in sink logs

---

## TC-4 — Idempotency (Duplicate Event Suppression)

**What it verifies:** Sending the same `eventId` twice within the 5-minute claim window results in the second event being silently suppressed at the consumer. Only one delivery reaches the sink.

The in-memory idempotency store uses claim-ttl = 5 minutes and done-ttl = 24 hours.

### Step 4a — First publish (accepted)

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0004-000000000001",
    "eventType":   "order.created",
    "sourceSystem": "order-service",
    "tenantId":    "T-VERIFY-004"
  }'
```

**Expected:** `200 OK`, body `Audit event published successfully`

### Step 4b — Duplicate publish (suppressed)

Run the **identical** command immediately:

```bash
curl -s -X POST http://localhost:8080/api/v1/audit/publish \
  -H "Content-Type: application/json" \
  -d '{
    "eventId":     "00000000-0000-0000-0004-000000000001",
    "eventType":   "order.created",
    "sourceSystem": "order-service",
    "tenantId":    "T-VERIFY-004"
  }'
```

**Expected:** `200 OK` — the API always accepts the request; deduplication happens asynchronously at the consumer level.

### Where to verify

**Backend logs** — look for a dedup/idempotency rejection message for event `00000000-0000-0000-0004-000000000001`:

```bash
just log backend
```

Search for `duplicate`, `already`, `idempotent`, or the eventId string. The second occurrence should produce a rejection log, not a pipeline run.

**Sink logs** — the `order.created` event must appear exactly **once**:

```bash
just log sink
```

No matter how many times step 4b is repeated, the count in the sink stays at 1.

### Pass criteria

- [x] Both publishes return `200 OK`
- [x] Backend log shows a dedup rejection for the second event
- [x] Sink log contains exactly one entry with `eventType: order.created` and `tenantId: T-VERIFY-004`

---

## Teardown

```bash
just down     # stop containers, keep images and build cache
# or
just clean    # stop containers AND remove volumes (full reset)
```

---

## Quick Reference

### Service URLs

| URL | Purpose |
|---|---|
| http://localhost:8080/swagger-ui.html | Interactive API — publish events from the browser |
| http://localhost:8080/actuator/health | Backend liveness / readiness |
| http://localhost:8080/actuator/prometheus | Prometheus metrics scrape endpoint |
| http://localhost:8081/docs | Transformer FastAPI docs + transformer list |
| http://localhost:8082/docs | Sink FastAPI docs + sink list |
| http://localhost:15673 | RabbitMQ Management UI (guest / guest) |

### Useful commands

| Command | Purpose |
|---|---|
| `just log backend` | Tail backend (Java) logs |
| `just log sink` | Tail sink (Python) logs |
| `just log transformer` | Tail transformer (Python) logs |
| `just log rabbitmq` | Tail RabbitMQ broker logs |
| `just status` | Show container health |
| `just e2e` | Built-in smoke test (happy path only) |
| `just down` | Stop the stack |

### Event ID conventions used in this plan

| TC | eventId suffix | eventType |
|---|---|---|
| TC-1 | `0001-000000000001` | `user.login` |
| TC-2a | `0002-000000000001` | `api.call` |
| TC-2b | `0002-000000000002` | `security.alert` |
| TC-3 | `0003-000000000001` | `user.profile.updated` |
| TC-4 | `0004-000000000001` | `order.created` |
