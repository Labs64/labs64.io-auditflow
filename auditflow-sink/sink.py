from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
import importlib
import sys
import os
import logging

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

# Define base directory
current_dir = os.path.dirname(os.path.abspath(__file__))

# Add the 'sinks' folder for internal sink implementations
internal_sinks_path = os.path.join(current_dir, 'sinks')
sys.path.append(internal_sinks_path)
app_logger.info(f"Added internal sinks path: {internal_sinks_path}")

# Conditionally add the 'sinks_bootstrap' folder for external/custom sinks, if it exists
external_sinks_path = os.path.join(current_dir, 'sinks_bootstrap')
if os.path.exists(external_sinks_path):
    sys.path.append(external_sinks_path)
    app_logger.info(f"Added external (bootstrap) sinks path: {external_sinks_path}")
else:
    app_logger.warning(f"External sinks directory not found: {external_sinks_path}. Skipping.")


@app.post('/sink/{sink_id}')
async def sink(
        sink_id: str,
        event_data: dict,
        properties: dict = None
):
    """
    Send transformed audit events to a destination sink.

    This endpoint dynamically loads and executes a sink implementation
    based on the `sink_id` provided in the URL path.

    It searches for a module named `{sink_id}.py` in configured paths
    (internal 'sinks/' and optionally 'sinks_bootstrap/').

    The sink module must provide a 'process' function that accepts:
    - event_data: The transformed event data (dict)
    - properties: Configuration properties for the sink (dict)

    Returns:
    - Success response with sink processing details
    """
    try:
        # Attempt to dynamically import the sink module using the sink_id
        try:
            sink_module = importlib.import_module(sink_id)
            app_logger.info(f"Successfully loaded sink module: {sink_id}.py")
        except ModuleNotFoundError:
            raise HTTPException(
                status_code=404,
                detail=f"Sink module not found for ID: '{sink_id}'. "
                       f"Please ensure {sink_id}.py exists in sinks/ or sinks_bootstrap/ directory."
            )
        except Exception as e:
            app_logger.error(f"Error loading sink module '{sink_id}': {e}")
            raise HTTPException(
                status_code=500,
                detail=f"Failed to load sink module '{sink_id}': {e}"
            )

        # Get the process function from the sink module
        try:
            process_function = getattr(sink_module, 'process')
        except AttributeError:
            raise HTTPException(
                status_code=500,
                detail=f"Process function 'process' not found in sink module '{sink_id}'. "
                       f"Each sink must implement a 'process(event_data, properties)' function."
            )
        except Exception as e:
            app_logger.error(f"Error getting process function from module '{sink_module.__name__}': {e}")
            raise HTTPException(
                status_code=500,
                detail=f"Failed to get process function for sink '{sink_id}': {e}"
            )

        # Initialize properties if not provided
        if properties is None:
            properties = {}

        # Execute the sink processing
        app_logger.info(f"Processing event through sink '{sink_id}'")
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
        app_logger.error(f"An unexpected error occurred in sink endpoint: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"An unexpected error occurred while processing event through sink '{sink_id}': {e}"
        )


@app.get('/sinks')
async def list_sinks():
    """
    List all available sink modules.

    Returns a list of available sink IDs that can be used in the /sink/{sink_id} endpoint.
    """
    available_sinks = []

    # Check internal sinks
    internal_sinks_path = os.path.join(current_dir, 'sinks')
    if os.path.exists(internal_sinks_path):
        for file in os.listdir(internal_sinks_path):
            if file.endswith('.py') and not file.startswith('__'):
                sink_id = file[:-3]  # Remove .py extension
                available_sinks.append({
                    "id": sink_id,
                    "type": "internal",
                    "path": f"sinks/{file}"
                })

    # Check external sinks
    external_sinks_path = os.path.join(current_dir, 'sinks_bootstrap')
    if os.path.exists(external_sinks_path):
        for file in os.listdir(external_sinks_path):
            if file.endswith('.py') and not file.startswith('__'):
                sink_id = file[:-3]  # Remove .py extension
                available_sinks.append({
                    "id": sink_id,
                    "type": "external",
                    "path": f"sinks_bootstrap/{file}"
                })

    return JSONResponse(
        content={
            "available_sinks": available_sinks,
            "count": len(available_sinks)
        },
        status_code=200
    )
