# AuditFlow — Root justfile
#
# Prerequisites: just, docker, docker compose, curl, jq (optional, for pretty output)
#
# Quick start:
#   just up      → build all images and start the full stack
#   just e2e     → publish a test event and watch it flow through the pipeline
#   just logs    → tail all service logs
#   just down    → stop everything

# ─────────────────────────────────────────────────────────────────────────────
# List available recipes
# ─────────────────────────────────────────────────────────────────────────────
default:
    @just --list

# ─────────────────────────────────────────────────────────────────────────────
# Full stack (all services + RabbitMQ + Redis + Jaeger)
# ─────────────────────────────────────────────────────────────────────────────

# Build the Spring Boot JAR, then build all Docker images and start the full stack
up: build-be
    @# Stop the infra-only container if it's running to free the ports
    docker compose -f docker-compose-infra.yml down 2>/dev/null || true
    docker compose up --build -d
    @echo ""
    @echo "  Backend API:        http://localhost:8080/api/v1"
    @echo "  Backend Swagger UI: http://localhost:8080/swagger-ui.html"
    @echo "  Backend Health:     http://localhost:8080/actuator/health"
    @echo "  Transformer API:    http://localhost:8081/docs"
    @echo "  Sink API:           http://localhost:8082/docs"
    @echo "  RabbitMQ UI:        http://localhost:15673  (guest/guest)"
    @echo "  Jaeger UI:          http://localhost:16686"

# ─────────────────────────────────────────────────────────────────────────────
# Lite stack: RabbitMQ + transformer + sink + backend — no Redis, no Jaeger
# In-memory idempotency store; faster start, fewer containers for local iteration.
# ─────────────────────────────────────────────────────────────────────────────

# Build the JAR, then build images and start the trimmed lite stack
up-lite: build-be
    @# Stop the full / infra-only stacks to free the shared ports
    docker compose down 2>/dev/null || true
    docker compose -f docker-compose-infra.yml down 2>/dev/null || true
    docker compose -f docker-compose-lite.yml up --build -d
    @echo ""
    @echo "  Lite stack up (no Redis, no Jaeger; in-memory dedup)."
    @echo "  Backend API:        http://localhost:8080/api/v1"
    @echo "  Backend Swagger UI: http://localhost:8080/swagger-ui.html"
    @echo "  Transformer API:    http://localhost:8081/docs"
    @echo "  Sink API:           http://localhost:8082/docs"
    @echo "  RabbitMQ UI:        http://localhost:15673  (guest/guest)"

# ─────────────────────────────────────────────────────────────────────────────
# Verification stack: lite + two test pipelines + redaction
# Supports all four test cases in DEVELOPERS.md
# ─────────────────────────────────────────────────────────────────────────────

# Start the verification stack (lite + verify overlay)
verify: build-be
    docker compose down 2>/dev/null || true
    docker compose -f docker-compose-infra.yml down 2>/dev/null || true
    docker compose -f docker-compose-lite.yml -f docker-compose-verify.yml up --build -d
    @echo ""
    @echo "  Verification stack up. Follow DEVELOPERS.md for TC-1 through TC-4."
    @echo "  Backend API:        http://localhost:8080/api/v1"
    @echo "  RabbitMQ UI:        http://localhost:15673  (guest/guest)"
    @echo "  just log sink       (watch delivered events)"
    @echo "  just down           (when done)"

# Stop and remove all containers (keeps images) — covers all stacks
down:
    docker compose -f docker-compose-lite.yml down 2>/dev/null || true
    docker compose down 2>/dev/null || true
    docker compose -f docker-compose-infra.yml down 2>/dev/null || true

# Stop and remove containers AND volumes (full clean — covers all stacks)
clean:
    docker compose -f docker-compose-lite.yml down -v --remove-orphans 2>/dev/null || true
    docker compose down -v --remove-orphans 2>/dev/null || true
    docker compose -f docker-compose-infra.yml down -v --remove-orphans 2>/dev/null || true

# Tail logs from all services (Ctrl+C to stop)
logs:
    docker compose logs -f

# Tail logs from a specific service: just log backend | transformer | sink | rabbitmq
log service:
    docker compose logs -f {{service}}

# Show current container status
status:
    docker compose ps

# ─────────────────────────────────────────────────────────────────────────────
# Infrastructure only (RabbitMQ + Redis) — for running services outside Docker
# ─────────────────────────────────────────────────────────────────────────────

# Start only RabbitMQ + Redis (for running services directly on the host)
infra-up:
    @# Stop the full stack if running to free the ports
    docker compose down 2>/dev/null || true
    docker compose -f docker-compose-infra.yml up -d
    @echo ""
    @echo "RabbitMQ + Redis started."
    @echo "  AMQP:           localhost:5673"
    @echo "  Management UI:  http://localhost:15673  (guest/guest)"
    @echo "  Redis:          localhost:6379"

# Stop RabbitMQ + Redis
infra-down:
    docker compose -f docker-compose-infra.yml down

# ─────────────────────────────────────────────────────────────────────────────
# Build
# ─────────────────────────────────────────────────────────────────────────────

# Build the Spring Boot JAR (required before building the backend Docker image)
build-be:
    mvn -B clean package -DskipTests --file auditflow-be/pom.xml

# Build all Docker images without starting them
build:
    docker compose build

# ─────────────────────────────────────────────────────────────────────────────
# Testing
# ─────────────────────────────────────────────────────────────────────────────

# Run all tests across all three services
test: test-be test-transformer test-sink

# Run Java backend unit tests
test-be:
    mvn -B verify --file auditflow-be/pom.xml

# Run Python transformer tests
test-transformer:
    python3 -m pip install -r auditflow-transformer/requirements-dev.txt -q --break-system-packages 2>/dev/null || python3 -m pip install -r auditflow-transformer/requirements-dev.txt -q
    cd auditflow-transformer && python3 -m pytest -v --tb=short

# Run Python sink tests
test-sink:
    python3 -m pip install -r auditflow-sink/requirements-dev.txt -q --break-system-packages 2>/dev/null || python3 -m pip install -r auditflow-sink/requirements-dev.txt -q
    cd auditflow-sink && python3 -m pytest -v --tb=short

# ─────────────────────────────────────────────────────────────────────────────
# End-to-end smoke test (stack must be running)
# ─────────────────────────────────────────────────────────────────────────────

# Run the happy-path E2E test:
#   1. Publish an audit event to the backend
#   2. The backend publishes it to RabbitMQ
#   3. The backend consumes it, calls the zero transformer, then the logging_sink
#   4. The logging_sink prints the event to the sink container logs
#
# After running this, check the sink logs:  just log sink
e2e:
    @echo "Publishing test audit event to http://localhost:8080/api/v1/audit/publish ..."
    @curl -s -X POST http://localhost:8080/api/v1/audit/publish \
        -H "Content-Type: application/json" \
        -d '{ \
            "eventId": "00000000-e2e0-0000-0000-000000000001", \
            "eventType": "e2e.test.event", \
            "sourceSystem": "auditflow/e2e", \
            "tenantId": "LOCAL-DEV", \
            "extra": { \
                "sessionId": "dev-session-01", \
                "userId": "dev-user", \
                "action_name": "e2e_test", \
                "action_status": "SUCCESS", \
                "action_message": "End-to-end test event" \
            } \
        }' | python3 -m json.tool 2>/dev/null || true
    @echo ""
    @echo "Event published. Check the sink logs to confirm it was processed:"
    @echo "  just log sink"
    @echo ""
    @echo "You should see an 'Audit Event Logged' entry in the sink output."

# Open all service UIs in the browser (macOS / Linux)
open-ui:
    open "http://localhost:8080/swagger-ui.html" 2>/dev/null || xdg-open "http://localhost:8080/swagger-ui.html"
    open "http://localhost:8081/docs"            2>/dev/null || xdg-open "http://localhost:8081/docs"
    open "http://localhost:8082/docs"            2>/dev/null || xdg-open "http://localhost:8082/docs"
    open "http://localhost:15673"                2>/dev/null || xdg-open "http://localhost:15673"
