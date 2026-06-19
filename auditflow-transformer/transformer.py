from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
import sys
import os
import logging

from plugin_registry import PluginRegistry, PluginNotFoundError, VALID_ID

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
app_logger = logging.getLogger(__name__)

app = FastAPI(
    title="Labs64.IO :: AuditFlow - JSON Transformer",
    description="Transform JSON-based Labs64.IO AuditFlow structures using a specified transformer ID.",
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

from tracing import setup_tracing
setup_tracing(app, service_name="auditflow-transformer")

# Define base directory
current_dir = os.path.dirname(os.path.abspath(__file__))

# Add the 'transformers' folder for internal transformations
internal_transformers_path = os.path.join(current_dir, 'transformers')
sys.path.append(internal_transformers_path)
app_logger.info("Added internal transformers path: %s", internal_transformers_path)

# Conditionally add the 'transformers_bootstrap' folder for external transformations, if it exists
external_transformers_path = os.path.join(current_dir, 'transformers_bootstrap')
if os.path.exists(external_transformers_path):
    sys.path.append(external_transformers_path)
    app_logger.info("Added external (bootstrap) transformers path: %s", external_transformers_path)
else:
    app_logger.warning("External transformers directory not found: %s. Skipping.", external_transformers_path)

# Discover and validate transformers once at startup into an allow-list.
# Only allow-listed ids are resolvable; unknown ids are rejected before any import.
registry = PluginRegistry(
    base_dir=current_dir,
    dir_specs=[("transformers", "internal"), ("transformers_bootstrap", "external")],
    entry_point="transform",
).discover()


@app.post('/transform/{transformer_id}')
async def transform(
        transformer_id: str,
        json_data: dict
):
    """
    Transforms Labs64.IO AuditFlow JSON structures based on a transformer ID.

    The transformer is resolved from the startup allow-list (modules shipped in 'transformers/'
    or mounted in 'transformers_bootstrap/'). An id that is not on the allow-list returns 404 and
    is never imported. The JSON payload contains the data to be transformed.
    """
    try:
        # Reject malformed ids (path traversal / arbitrary import) with 400 before resolving.
        if not VALID_ID.fullmatch(transformer_id):
            raise HTTPException(
                status_code=400,
                detail=f"Invalid transformer ID '{transformer_id}'. Only alphanumeric characters and underscores are allowed."
            )

        # Resolve against the allow-list. Unknown ids return 404 and never trigger an import.
        try:
            transformation_function = registry.resolve(transformer_id)
        except PluginNotFoundError:
            raise HTTPException(
                status_code=404,
                detail=f"Transformer '{transformer_id}' is not available. "
                       f"See GET /transformers for the registered transformers."
            )

        # Apply the transformation, passing the automatically parsed json_data
        transformed_data = transformation_function(json_data)

        return JSONResponse(content=transformed_data, status_code=200)

    except HTTPException as http_exc:
        # Re-raise HTTPException to be handled by FastAPI's error handling
        raise http_exc
    except Exception as e:
        app_logger.error("An unexpected error occurred in transform endpoint: %s", e, exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"An unexpected error occurred while processing event through transformer '{transformer_id}': {e}"
        )


@app.get('/transformers')
async def list_transformers():
    """
    List all available (allow-listed) transformer modules. Also doubles as the container
    healthcheck. Modules discovered but excluded due to load/contract errors are reported
    under 'errors'.
    """
    return JSONResponse(
        content={
            "available_transformers": registry.list_available(),
            "count": len(registry.list_available()),
            "errors": registry.errors(),
        },
        status_code=200
    )


@app.get('/registry')
async def registry_details():
    """Detailed registry view: per-transformer version, description, and documented properties."""
    return JSONResponse(
        content={"transformers": registry.details(), "errors": registry.errors()},
        status_code=200
    )


@app.post('/registry/reload')
async def registry_reload():
    """Re-scan the transformer directories (hot-reload of newly mounted bootstrap modules)."""
    registry.reload()
    return JSONResponse(
        content={"reloaded": True, "count": len(registry.list_available()), "errors": registry.errors()},
        status_code=200
    )
