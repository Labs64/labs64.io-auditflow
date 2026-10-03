from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from concurrent.futures import ThreadPoolExecutor
import sys
import os
import logging

from contextlib import asynccontextmanager
from plugin_registry import PluginRegistry, PluginNotFoundError, VALID_ID
from health import set_ready, health, readiness, liveness, service_info, suppress_health_access_logs

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
app_logger = logging.getLogger(__name__)

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Set service as ready after startup completes."""
    suppress_health_access_logs()
    set_ready(True)
    app_logger.info("Sink service started and ready")
    yield

app = FastAPI(
    title="Labs64.IO :: AuditFlow - Event Sink Service",
    description="Process and send transformed audit events to various destinations using pluggable sinks.",
    version="0.0.1",
    contact={
        "name": "Labs64 Support",
        "url": "https://www.labs64.com/contact/",
        "email": "info@labs64.com",
    },
    license_info={
        "name": "LGPL v3.0",
        "url": "https://raw.githubusercontent.com/Labs64/labs64.io-auditflow/refs/heads/master/LICENSE",
    },
    swagger_ui_parameters={"displayRequestDuration": True},
    lifespan=lifespan
)

# Health check endpoints
app.get('/health')(health)
app.get('/ready')(readiness)
app.get('/live')(liveness)
app.get('/info')(service_info)

from telemetry import get_business_telemetry
business_telemetry = get_business_telemetry()

# Define base directory
current_dir = os.path.dirname(os.path.abspath(__file__))

# Add the 'sinks' folder for internal sink implementations
internal_sinks_path = os.path.join(current_dir, 'sinks')
sys.path.append(internal_sinks_path)
app_logger.info("Added internal sinks path: %s", internal_sinks_path)

# Conditionally add the 'sinks_bootstrap' folder for external/custom sinks, if it exists
external_sinks_path = os.path.join(current_dir, 'sinks_bootstrap')
if os.path.exists(external_sinks_path):
    sys.path.append(external_sinks_path)
    app_logger.info("Added external (bootstrap) sinks path: %s", external_sinks_path)
else:
    app_logger.warning("External sinks directory not found: %s. Skipping.", external_sinks_path)

# Discover and validate sinks once at startup into an allow-list.
# Only allow-listed ids are resolvable; unknown ids are rejected before any import.
# Sources, lowest precedence first: shipped 'sinks/', pip-installed wheels advertising the
# 'auditflow.sinks' entry-point group, then anything mounted in 'sinks_bootstrap/'.
registry = PluginRegistry(
    base_dir=current_dir,
    dir_specs=[("sinks", "internal"), ("sinks_bootstrap", "external")],
    entry_point="process",
    entry_point_group="auditflow.sinks",
).discover()


@app.post('/sink/{sink_id}')
async def sink(
        sink_id: str,
        request_body: dict
):
    """
    Send transformed audit events to a destination sink.

    The sink is resolved from the startup allow-list (modules shipped in 'sinks/' or mounted in
    'sinks_bootstrap/'). An id that is not on the allow-list returns 404 and is never imported.

    The sink module must provide a 'process(event_data, properties)' function.

    Returns:
    - Success response with sink processing details
    """
    try:
        # Reject malformed ids (path traversal / arbitrary import) with 400 before resolving.
        if not VALID_ID.fullmatch(sink_id):
            raise HTTPException(
                status_code=400,
                detail=f"Invalid sink ID '{sink_id}'. Only alphanumeric characters and underscores are allowed."
            )

        # Resolve against the allow-list. Unknown ids return 404 and never trigger an import.
        try:
            process_function = registry.resolve(sink_id)
        except PluginNotFoundError:
            raise HTTPException(
                status_code=404,
                detail=f"Sink '{sink_id}' is not available. See GET /sinks for the registered sinks."
            )

        event_data = request_body.get("event_data", {})
        properties = request_body.get("properties", {})

        # Execute the sink processing
        event_id = event_data.get("eventId", "unknown")
        app_logger.info("Processing event '%s' type='%s' through sink '%s'",
                        event_id, event_data.get("eventType", ""), sink_id)
        # Sinks do blocking I/O (S3, HTTP, JDBC). Run them in the thread pool: called directly from
        # this coroutine they would block the event loop and serialize every event on the pod.
        result = await run_in_threadpool(process_function, event_data, properties)
        business_telemetry.sink_completed(sink_id, True)

        # Return success response
        return JSONResponse(
            content={
                "status": "success",
                "sink": sink_id,
                "message": f"Event processed successfully by sink '{sink_id}'",
                "result": result if result else "Event sent to destination"
            },
            status_code=200
        )

    except HTTPException as http_exc:
        # Re-raise HTTPException to be handled by FastAPI's error handling
        raise http_exc
    except Exception as e:
        app_logger.error("An unexpected error occurred in sink endpoint: %s", e, exc_info=True)
        business_telemetry.sink_completed(sink_id, False)
        raise HTTPException(
            status_code=500,
            detail=f"An unexpected error occurred while processing event through sink '{sink_id}': {e}"
        )


MAX_BATCH_EVENTS = 1000


def _resolve_sink(sink_id: str):
    if not VALID_ID.fullmatch(sink_id):
        raise HTTPException(
            status_code=400,
            detail=f"Invalid sink ID '{sink_id}'. Only alphanumeric characters and underscores are allowed."
        )
    try:
        return registry.resolve(sink_id)
    except PluginNotFoundError:
        raise HTTPException(
            status_code=404,
            detail=f"Sink '{sink_id}' is not available. See GET /sinks for the registered sinks."
        )


# A sink without process_batch gets the events of a batch through process(), a few at a time. One by
# one, a batch of 100 events at 150 ms each would outlast the backend's timeout for the call: the
# backend would then retry a batch the sink is still delivering.
FALLBACK_BATCH_WORKERS = 8


def _batch_function(process_function):
    """The sink module's ``process_batch``, or None when it only has ``process``."""
    module = sys.modules.get(getattr(process_function, "__module__", ""), None)
    process_batch = getattr(module, "process_batch", None) if module else None
    return process_batch if callable(process_batch) else None


def _process_each(process_function, events, properties):
    def one(event):
        try:
            return process_function(event, properties)
        except Exception as e:  # noqa: BLE001 - reported per event
            return e

    if len(events) == 1:
        return [one(events[0])]
    with ThreadPoolExecutor(max_workers=min(FALLBACK_BATCH_WORKERS, len(events)),
                            thread_name_prefix="sink-batch") as pool:
        return list(pool.map(one, events))


def _run_batch(process_function, events, properties):
    """Deliver a batch, returning one result dict per event (same order).

    A sink module may provide ``process_batch(events, properties)`` returning one entry per event:
    ``None``/a result dict for success, or an ``Exception`` for that event's failure. Without it,
    events go through ``process``, a few at a time. A failure is retryable unless it is a
    ``ValueError`` (bad data or configuration: retrying the same input cannot succeed).
    """
    process_batch = _batch_function(process_function)
    if process_batch is not None:
        outcomes = process_batch(events, properties)
        if not isinstance(outcomes, list) or len(outcomes) != len(events):
            raise RuntimeError("process_batch must return one outcome per event")
    else:
        outcomes = _process_each(process_function, events, properties)
    results = []
    for i, outcome in enumerate(outcomes):
        if isinstance(outcome, Exception):
            app_logger.error("Batch event %d failed: %s", i, outcome)
            results.append({"index": i, "status": "error", "retryable": not isinstance(outcome, ValueError),
                            "error": str(outcome)[:500]})
        else:
            results.append({"index": i, "status": "success"})
    return results


@app.post('/sink/{sink_id}/batch')
async def sink_batch(
        sink_id: str,
        request_body: dict
):
    """
    Send several transformed events to a sink in one call, with a result per event.

    Body: ``{"events": [...], "properties": {...}}``. A whole-call error (unknown sink, the sink
    raising for the batch as a whole) is a 4xx/5xx like the single-event endpoint; per-event failures
    are reported in ``results`` with ``retryable`` so the caller retries only what can succeed.
    """
    try:
        process_function = _resolve_sink(sink_id)
        events = request_body.get("events")
        if not isinstance(events, list) or not events:
            raise HTTPException(status_code=400, detail="'events' must be a non-empty list")
        if len(events) > MAX_BATCH_EVENTS:
            raise HTTPException(status_code=400, detail=f"At most {MAX_BATCH_EVENTS} events per batch")
        properties = request_body.get("properties", {})
        app_logger.info("Processing batch of %d event(s) through sink '%s'", len(events), sink_id)
        # Blocking I/O: run in the thread pool, never on the event loop.
        results = await run_in_threadpool(_run_batch, process_function, events, properties)
        failed = sum(1 for r in results if r["status"] != "success")
        business_telemetry.sink_completed(sink_id, failed == 0)
        return JSONResponse(content={"status": "success" if failed == 0 else "partial", "sink": sink_id,
                                     "results": results}, status_code=200)
    except HTTPException:
        raise
    except Exception as e:
        app_logger.error("An unexpected error occurred in sink batch endpoint: %s", e, exc_info=True)
        business_telemetry.sink_completed(sink_id, False)
        raise HTTPException(
            status_code=500,
            detail=f"An unexpected error occurred while processing a batch through sink '{sink_id}': {e}"
        )


@app.get('/registry')
async def registry_details():
    """Detailed registry view: per-sink version, description, documented properties, and whether the
    sink writes a batch in one call (``batch``). Also doubles as the container healthcheck."""
    sinks = registry.details()
    for entry in sinks:
        try:
            entry["batch"] = _batch_function(registry.resolve(entry["id"])) is not None
        except PluginNotFoundError:
            entry["batch"] = False
    return JSONResponse(
        content={"sinks": sinks, "errors": registry.errors()},
        status_code=200
    )


@app.post('/registry/reload')
async def registry_reload():
    """Re-scan the sink directories (hot-reload of newly mounted bootstrap modules)."""
    registry.reload()
    return JSONResponse(
        content={"reloaded": True, "count": len(registry.list_available()), "errors": registry.errors()},
        status_code=200
    )
