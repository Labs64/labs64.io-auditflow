# AuditFlow — Root justfile
#
# Prerequisites: just, docker, docker compose, curl, jq (optional, for pretty output)
#
# Quick start:
#   just up          → start stack (default)
#   just up obs      → start stack + observability
#   just test        → run all tests
#   just down        → stop everything
#   just clean       → stop + remove volumes (full reset)

# ─────────────────────────────────────────────────────────────────────────────
# List available recipes
# ─────────────────────────────────────────────────────────────────────────────
default:
    @just --list

# ─────────────────────────────────────────────────────────────────────────────
# Private helpers
# ─────────────────────────────────────────────────────────────────────────────

# Print all service URLs
_urls:
    @echo ""
    @echo "  Backend API:        http://localhost:8080/api/v1"
    @echo "  Backend Health:     http://localhost:8080/actuator/health"
    @echo "  Backend Swagger UI: http://localhost:8080/swagger-ui.html"
    @echo "  Transformer API:    http://localhost:8081/docs"
    @echo "  Sink API:           http://localhost:8082/docs"
    @echo "  RabbitMQ UI:        http://localhost:15673  (guest/guest)"
    @echo "  OTel Collector:     localhost:4317 (gRPC) / localhost:4318 (HTTP)"
    @echo "  Grafana:            http://localhost:3000   (admin/admin)"
    @echo "  Prometheus:         http://localhost:9090"
    @echo "  Tempo:              http://localhost:3200"
    @echo "  Loki:               http://localhost:3100"

# Stop all known stacks to free ports
_stop-all:
    @docker compose -f docker-compose.yml -f docker-compose-observability.yml --profile full down 2>/dev/null || true

# ─────────────────────────────────────────────────────────────────────────────
# Stack
# ─────────────────────────────────────────────────────────────────────────────

# Build JAR, images, start stack. Pass "obs" to include observability.
up obs="": build-be
    @just _stop-all
    @docker compose -f docker-compose.yml {{ if obs == "obs" { "-f docker-compose-observability.yml" } else { "" } }} up --build -d
    @just _urls

# Stop and remove all containers (keeps images)
down:
    @just _stop-all

# Stop and remove containers AND volumes (full clean)
clean:
    @docker compose -f docker-compose.yml -f docker-compose-observability.yml --profile full down -v --remove-orphans 2>/dev/null || true

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
# Build
# ─────────────────────────────────────────────────────────────────────────────

# Build the Spring Boot JAR (required before building the backend Docker image)
build-be:
    mvn -B clean package -DskipTests --file auditflow-be/pom.xml

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
# Example notebooks
# ─────────────────────────────────────────────────────────────────────────────

# Open the getting-started notebook (requires: pip install jupyter requests)
notebook-getting-started:
    @echo "Prerequisites: pip install jupyter requests && just up"
    jupyter lab examples/getting-started.ipynb

# Open the load-testing notebook (requires: pip install jupyter requests)
notebook-load-test:
    @echo "Prerequisites: pip install jupyter requests && just up"
    jupyter lab examples/load-tests.ipynb
