from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
import sys
import os
import logging

from plugin_registry import PluginRegistry, PluginNotFoundError, VALID_ID
from health import set_ready, health, readiness, liveness, service_info, suppress_health_access_logs

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
app_logger = logging.getLogger(__name__)

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
    swagger_ui_parameters={"displayRequestDuration": True}
)

# Health check endpoints
app.get('/health')(health)
app.get('/ready')(readiness)
app.get('/live')(liveness)
app.get('/info')(service_info)

from tracing import setup_telemetry
setup_telemetry(app, service_name="auditflow-sink")

@app.on_event("startup")
async def startup_event():
    """Set service as ready after startup completes."""
    suppress_health_access_logs()
    set_ready(True)
    app_logger.info("Sink service started and ready")

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
registry = PluginRegistry(
    base_dir=current_dir,
    dir_specs=[("sinks", "internal"), ("sinks_bootstrap", "external")],
    entry_point="process",
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
        result = process_function(event_data, properties)

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
        raise HTTPException(
            status_code=500,
            detail=f"An unexpected error occurred while processing event through sink '{sink_id}': {e}"
        )


@app.get('/registry')
async def registry_details():
    """Detailed registry view: per-sink version, description, and documented properties. Also doubles as the container healthcheck."""
    return JSONResponse(
        content={"sinks": registry.details(), "errors": registry.errors()},
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
