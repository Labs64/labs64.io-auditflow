# AuditFlow — Root justfile
#
# Prerequisites: just, docker, docker compose, curl, jq (optional, for pretty output)
#
# Quick start:
#   just up          → start stack (default)
#   just up otel     → start stack + observability
#   just test        → run all tests
#   just down        → stop everything
#   just clean       → stop + remove volumes (full reset)

# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

# List available recipes
default:
    @just --list

# ─────────────────────────────────────────────────────────────────────────────
# Private helpers
# ─────────────────────────────────────────────────────────────────────────────

# Print all service URLs
_urls:
    @echo ""
    @echo "  Backend API:        http://localhost:8080"
    @echo "  Backend Health:     http://localhost:8080/actuator/health"
    @echo "  Backend Swagger UI: http://localhost:8080/swagger-ui.html"
    @echo "  Transformer API:    http://localhost:8081/docs"
    @echo "  Sink API:           http://localhost:8082/docs"
    @echo "  RabbitMQ UI:        http://localhost:15673  (guest/guest)"
    @echo "  ClickHouse Play:    http://localhost:8123/play  (auditflow/auditflow)"
    @echo ""
    @echo "  ClickHouse round trip: just notebook-getting-started  (section 6)"
    @echo "  Demo event data:       just ch-seed                   (then see examples/clickhouse/NETLICENSING_EVENTS.md)"
    @echo "  OTel Collector:     localhost:4317 (gRPC) / localhost:4318 (HTTP)"
    @echo "  Grafana:            http://localhost:3000   (admin/admin)"
    @echo "  Prometheus:         http://localhost:9090"
    @echo "  Tempo:              http://localhost:3200"
    @echo "  Loki:               http://localhost:3100"

# Stop all known stacks to free ports
_stop-all:
    @docker compose -f docker-compose.yml -f docker-compose-observability.yml down 2>/dev/null || true

# ─────────────────────────────────────────────────────────────────────────────
# Stack
# ─────────────────────────────────────────────────────────────────────────────

# Build JAR, images, start stack.
# Args (any order, space-separated, case-sensitive):
#   obs | otel | yes | true | 1  → add the observability overlay (docker-compose-observability.yml)
# Unrecognized tokens (e.g. wrong case, typos) are ignored but print a warning to stderr.
#
# Build the JAR, then build and start the stack, optionally with the observability overlay
up *args: build-be
    @just _stop-all
    @for token in {{ args }}; do \
        case "$token" in \
            obs|otel|yes|true|1) ;; \
            *) echo "WARN: just up: unrecognized argument '$token' (accepted: obs, otel, yes, true, 1)" >&2 ;; \
        esac; \
    done
    @docker compose \
        -f docker-compose.yml \
        {{ if args =~ '(^|\s)(obs|otel|yes|true|1)(\s|$)' { "-f docker-compose-observability.yml" } else { "" } }} \
        up --build -d
    @just _urls

# Stop and remove all containers (keeps images)
down:
    @just _stop-all

# Stop and remove all containers and volumes
clean:
    @docker compose -f docker-compose.yml -f docker-compose-observability.yml down -v --remove-orphans 2>/dev/null || true

# Show current container status
status:
    docker compose ps

# Follow the logs of all services
logs:
    docker compose logs -f

# Follow the logs of a single service
log service:
    docker compose logs -f {{ service }}

# ─────────────────────────────────────────────────────────────────────────────
# Build
# ─────────────────────────────────────────────────────────────────────────────

# Build and install the API client into the local Maven repository
build-api:
    mvn -B clean install -DskipTests --file auditflow-api/pom.xml

# Build the backend Spring Boot JAR, building the API client first
build-be: build-api
    mvn -B clean package -DskipTests --file auditflow-be/pom.xml

# ─────────────────────────────────────────────────────────────────────────────
# Testing
# ─────────────────────────────────────────────────────────────────────────────

# Run the API, backend, transformer, sink and E2E tests
test: test-api test-be test-transformer test-sink test-e2e

# Run the API client tests
test-api:
    mvn -B verify --file auditflow-api/pom.xml

# Run the backend tests, building the API client from this checkout
test-be:
    mvn -B verify -pl auditflow-be -am

# Run Python transformer tests
test-transformer:
    python3 -m pip install -r auditflow-transformer/requirements-dev.txt -q --break-system-packages 2>/dev/null || python3 -m pip install -r auditflow-transformer/requirements-dev.txt -q
    cd auditflow-transformer && python3 -m pytest -v --tb=short

# Run Python sink tests
test-sink:
    python3 -m pip install -r auditflow-sink/requirements-dev.txt -q --break-system-packages 2>/dev/null || python3 -m pip install -r auditflow-sink/requirements-dev.txt -q
    cd auditflow-sink && python3 -m pytest -v --tb=short

# Run E2E tests for this module via labs64.io-tests
test-e2e:
    @just -f ../labs64.io-tests/justfile test-module auditflow

# ─────────────────────────────────────────────────────────────────────────────
# ClickHouse (default stack — see the clickhouse-analytics pipeline in tenants/_platform.yaml)
# ─────────────────────────────────────────────────────────────────────────────

# The publish → sink → query round trip is covered by examples/getting-started.ipynb
# (section 6) — run it with `just notebook-getting-started`.

# Run a SQL query against the ClickHouse audit database
ch query:
    @docker compose exec -T clickhouse clickhouse-client \
        --user "${CLICKHOUSE_USERNAME:-auditflow}" \
        --password "${CLICKHOUSE_PASSWORD:-auditflow}" \
        --database audit \
        --query {{ quote(query) }}

# Show the most recently stored audit events, newest first
ch-events limit="20":
    @just ch "SELECT event_time, tenant_id, event_type, action_status, licensee_number, product_number, action_method, gross_amount, currency, extra FROM audit_events FINAL ORDER BY event_time DESC LIMIT {{ limit }} FORMAT PrettyCompactMonoBlock"

# Count events grouped by tenant, event type, API method and status
ch-stats:
    @just ch "SELECT tenant_id, event_type, action_method, action_status, count() AS events, min(event_time) AS first_seen, max(event_time) AS last_seen FROM audit_events FINAL GROUP BY tenant_id, event_type, action_method, action_status ORDER BY events DESC FORMAT PrettyCompactMonoBlock"

# Open an interactive ClickHouse shell
ch-shell:
    @docker compose exec clickhouse clickhouse-client \
        --user "${CLICKHOUSE_USERNAME:-auditflow}" \
        --password "${CLICKHOUSE_PASSWORD:-auditflow}" \
        --database audit

# Publish synthetic NetLicensing API + Payment Gateway events so the queries in
# examples/clickhouse/NETLICENSING_EVENTS.md have data.
# Pass extra flags through, e.g.: just ch-seed "--tenant demo"
#
# Publish synthetic NetLicensing and Payment Gateway events to query against
ch-seed *args:
    @python3 examples/clickhouse/seed_events.py {{ args }}

# ─────────────────────────────────────────────────────────────────────────────
# Example notebooks
# ─────────────────────────────────────────────────────────────────────────────

# Open the getting-started notebook in JupyterLab
notebook-getting-started:
    @echo "Prerequisites: pip install jupyter requests && just up"
    jupyter lab examples/getting-started.ipynb

# Open the load-testing notebook in JupyterLab
notebook-load-test:
    @echo "Prerequisites: pip install jupyter requests && just up"
    jupyter lab examples/load-tests.ipynb
