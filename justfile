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
# Full stack (all services + RabbitMQ)
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

# Stop and remove all containers (keeps images)
down:
    docker compose down

# Stop and remove containers AND volumes (full clean)
clean:
    docker compose down -v --remove-orphans

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
# Infrastructure only (RabbitMQ) — for running services outside Docker
# ─────────────────────────────────────────────────────────────────────────────

# Start only RabbitMQ (for running services directly on the host)
infra-up:
    @# Stop the full stack if running to free the ports
    docker compose down 2>/dev/null || true
    docker compose -f docker-compose-infra.yml up -d
    @echo ""
    @echo "RabbitMQ started."
    @echo "  AMQP:           localhost:5673"
    @echo "  Management UI:  http://localhost:15673  (guest/guest)"

# Stop RabbitMQ
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

# Run all Java unit tests
test-be:
    mvn -B verify --file auditflow-be/pom.xml

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
