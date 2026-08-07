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
retry/circuit-breaker/DLQ chain stays meaningful. Never trade the wait away for throughput.

What actually sets the batch size
---------------------------------
Because every caller blocks until its own flush, the number of rows a flush can collect is bounded
by the number of *concurrent in-flight deliveries*, not by the length of the flush window. Measured
against ClickHouse 25.3 (see the property notes below), ``max_rows_per_flush`` equalled the client
concurrency in every run. Raising ``async-insert-busy-timeout-ms`` therefore does **not** widen
batches: it just makes every caller wait longer, which lowers throughput, which lowers the arrival
rate — at 8 concurrent deliveries, going from a 200 ms to a 3000 ms window bought 5.1 → 6.6 rows
per flush while p50 latency went 210 ms → 2552 ms. Fewer parts come from more concurrent
deliveries (more sink replicas / more consumer concurrency), not from a longer window.

ClickHouse settings, as verified on 25.3
----------------------------------------
``async_insert_busy_timeout_ms`` is a genuine alias — ``system.settings.alias_for`` reports
``async_insert_busy_timeout_max_ms``, so a single property does stay portable. But it sets only the
*max*: since 24.2 ``async_insert_use_adaptive_busy_timeout`` is on by default, so the effective
window floats between ``async_insert_busy_timeout_min_ms`` (50 ms) and that max. Setting the alias
alone therefore does not pin the window — which is why the min and the adaptive toggle are exposed
as their own properties rather than left implicit.

The default window is 200 ms, matching ClickHouse's own default rather than the 1000 ms this sink
used to send. At 32 concurrent deliveries 1000 ms measured worse on every axis (24.7 vs 31.5 rows
per flush, 24 vs 18 new parts, 1016 ms vs 198 ms p50); at 8 it bought 5.1 → 6.0 rows per flush for
a 4x p50 penalty. Pinning the window (``async-insert-use-adaptive-busy-timeout: false``) raises
moderate-concurrency batching further — 5.1 → 7.9 rows per flush, 52 → 35 new parts at equal p50 —
but costs 3.4x p50 when deliveries are *not* concurrent, so it is opt-in rather than the default.

``async_insert_max_data_size`` and ``async_insert_max_query_number`` are deliberately *not* exposed.
Both are ceilings that only ever flush a batch **earlier**, so neither can widen one, and measurement
confirmed they are not the binding constraint here: at ~473 bytes per audit row the 10 MiB data-size
default is ~22 000 rows, and ``async_insert_max_query_number`` had no effect at all (ClickHouse only
honours it when ``async_insert_deduplicate`` is enabled, which this sink leaves off).
"""
import json
import logging

import requests

from auditflow_sdk import require_properties

__version__ = "1.1.0"

PROPERTIES = {
    "service-url": "ClickHouse HTTP endpoint, e.g. http://clickhouse:8123 (required)",
    "database": "Target database (default: default)",
    "table": "Target table (required)",
    "username": "Basic auth username (optional)",
    "password": "Basic auth password (optional; supports ${secretRef:<key>})",
    "verify-ssl": "Verify TLS certificates: true/false (default: true)",
    "async-insert": "Use server-side async_insert batching: true/false (default: true)",
    "wait-for-async-insert": "Block until the insert is flushed: true/false (default: true)",
    "async-insert-busy-timeout-ms": "Server-side flush window in ms; ClickHouse aliases this to "
                                    "async_insert_busy_timeout_max_ms (default: 200)",
    "async-insert-use-adaptive-busy-timeout": "Let ClickHouse float the flush window between the "
                                              "min and max: true/false (default: true). Set false "
                                              "to pin it at the max — only worth it when sink "
                                              "deliveries are genuinely concurrent",
    "async-insert-busy-timeout-min-ms": "Lower bound of the adaptive window in ms; no effect once "
                                        "async-insert-use-adaptive-busy-timeout is false "
                                        "(optional, ClickHouse default: 50)",
    "timeout": "HTTP timeout in seconds; must stay comfortably above the flush window "
               "(default: 10)",
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
            properties.get("async-insert-busy-timeout-ms", 200))
        # On by default (ClickHouse's own default). AuditFlow does not control how many deliveries
        # are in flight, and the adaptive window is what makes that safe: it collapses toward the
        # 50ms min when rows are sparse (nothing to batch, so the window is pure latency) and
        # stretches toward the max when they are dense. Pin it only if you know deliveries are
        # concurrent — measured 5.1 -> 7.9 rows per flush there, but a 3.4x p50 penalty when they
        # are not.
        adaptive = _is_true(properties, "async-insert-use-adaptive-busy-timeout", "true")
        params["async_insert_use_adaptive_busy_timeout"] = "1" if adaptive else "0"
        # Only meaningful while the adaptive window is on; sent only when the operator sets it.
        busy_timeout_min_ms = properties.get("async-insert-busy-timeout-min-ms")
        if busy_timeout_min_ms is not None:
            params["async_insert_busy_timeout_min_ms"] = str(busy_timeout_min_ms)

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
