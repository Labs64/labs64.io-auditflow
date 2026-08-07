"""ClickHouse Sink - insert audit events into a ClickHouse table over the HTTP interface.

Expects a row-shaped dict whose keys are the target table's column names. Pair this sink with the
``audit_clickhouse`` transformer, which produces exactly that shape; the canonical DDL lives in
``examples/clickhouse/schema.sql``. Paired with ``zero`` instead, every delivery will fail.

Rows arrive one at a time because AuditFlow delivers one event per HTTP request, and single-row
INSERTs into MergeTree create one part per row (the road to TOO_MANY_PARTS). This sink therefore
relies on ClickHouse's server-side ``async_insert`` to batch them rather than buffering in the
sink, which would make it stateful and lose events on restart.

``wait_for_async_insert=1`` (the default here) means the response only arrives once the part is
flushed. That costs latency — up to the busy-timeout window per delivery — but keeps the success
signal honest: a 200 from this sink means the row is durably written, so AuditFlow's
retry/circuit-breaker/DLQ chain stays meaningful. Tune throughput by lowering
``async-insert-busy-timeout-ms``, not by disabling the wait.

Note: ``async_insert_busy_timeout_ms`` was superseded in ClickHouse 24.x by the adaptive
``async_insert_busy_timeout_min_ms``/``_max_ms`` pair. It still works as an alias for the max,
which is why a single property stays portable across versions.
"""
import json
import logging

import requests

from auditflow_sdk import require_properties

__version__ = "1.0.0"

PROPERTIES = {
    "service-url": "ClickHouse HTTP endpoint, e.g. http://clickhouse:8123 (required)",
    "database": "Target database (default: default)",
    "table": "Target table (required)",
    "username": "Basic auth username (optional)",
    "password": "Basic auth password (optional; supports ${secretRef:<key>})",
    "verify-ssl": "Verify TLS certificates: true/false (default: true)",
    "async-insert": "Use server-side async_insert batching: true/false (default: true)",
    "wait-for-async-insert": "Block until the insert is flushed: true/false (default: true)",
    "async-insert-busy-timeout-ms": "Server-side flush window in ms (default: 1000)",
    "timeout": "HTTP timeout in seconds; must exceed the flush window (default: 10)",
}

logger = logging.getLogger(__name__)


def _is_true(properties: dict, key: str, default: str = "true") -> bool:
    """Pipeline properties arrive as strings from YAML; parse booleans defensively."""
    return str(properties.get(key, default)).strip().lower() == "true"


def process(event_data: dict, properties: dict) -> dict:
    """Insert a single pre-shaped row into a ClickHouse table."""
    require_properties(properties, "service-url", "table")

    service_url = properties["service-url"].rstrip("/")
    database = properties.get("database", "default")
    table = properties["table"]
    timeout = float(properties.get("timeout", 10))

    # Identifiers are operator-configured (trusted) and interpolated into the query; the event
    # payload never touches the SQL text — it is the request body. Same boundary as snowflake_sink.
    params = {
        "query": f"INSERT INTO {database}.{table} FORMAT JSONEachRow",
        # AuditFlow emits ISO-8601 with a trailing Z; best_effort is what lets DateTime64 read it.
        "date_time_input_format": "best_effort",
        # Make transformer drift a dropped key rather than a failed delivery.
        "input_format_skip_unknown_fields": "1",
    }

    if _is_true(properties, "async-insert"):
        params["async_insert"] = "1"
        params["wait_for_async_insert"] = "1" if _is_true(properties, "wait-for-async-insert") else "0"
        params["async_insert_busy_timeout_ms"] = str(
            properties.get("async-insert-busy-timeout-ms", 1000))

    username = properties.get("username")
    auth = (username, properties.get("password") or "") if username else None

    body = json.dumps(event_data, separators=(",", ":")).encode("utf-8")

    try:
        response = requests.post(
            f"{service_url}/",
            params=params,
            data=body,
            auth=auth,
            verify=_is_true(properties, "verify-ssl"),
            timeout=timeout,
        )
        response.raise_for_status()
    except requests.exceptions.HTTPError as e:
        # HTTPError is a RequestException subclass, so this branch must come first.
        code = e.response.headers.get("X-ClickHouse-Exception-Code", "unknown")
        detail = e.response.text.strip()[:500]
        logger.error("ClickHouse rejected the insert into %s.%s (exception code %s): %s",
                     database, table, code, detail)
        raise RuntimeError(
            f"ClickHouse insert into {database}.{table} failed with HTTP "
            f"{e.response.status_code}, exception code {code}: {detail}"
        ) from e
    except requests.exceptions.RequestException as e:
        logger.error("Failed to reach ClickHouse at %s: %s", service_url, e)
        raise RuntimeError(f"Failed to reach ClickHouse at {service_url}: {e}") from e

    logger.info("Inserted audit event into ClickHouse table '%s.%s'", database, table)
    return {
        "delivered": True,
        "destination": "clickhouse",
        "database": database,
        "table": table,
        "status_code": response.status_code,
    }
