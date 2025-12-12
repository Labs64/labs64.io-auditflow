from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse
from typing import Any, Dict
import importlib
import sys
import os
import logging

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

# Define base directory
current_dir = os.path.dirname(os.path.abspath(__file__))

# Add the 'transformers' folder for internal transformations
internal_transformers_path = os.path.join(current_dir, 'transformers')
sys.path.append(internal_transformers_path)
app_logger.info(f"Added internal transformers path: {internal_transformers_path}")

# Conditionally add the 'transformers_bootstrap' folder for external transformations, if it exists
external_transformers_path = os.path.join(current_dir, 'transformers_bootstrap')
if os.path.exists(external_transformers_path):
    sys.path.append(external_transformers_path)
    app_logger.info(f"Added external (bootstrap) transformers path: {external_transformers_path}")
else:
    app_logger.warning(f"External transformers directory not found: {external_transformers_path}. Skipping.")


@app.post('/transform/{transformer_id}')
async def transform(
        transformer_id: str,
        json_data: Dict[str, Any]
):
    """
    Transforms Labs64.IO AuditFlow JSON structures based on a transformer ID.

    This endpoint dynamically loads and applies a transformation
    based on the `transformer_id` provided in the URL path.

    It searches for a module named `{transformer_id}.py`
    in configured paths (internal 'transformers/' and optionally 'transformers_bootstrap/').

    If the module or its 'transform' function isn't found, it returns an error.
    The JSON payload should contain the data to be transformed.
    """
    try:
        # Attempt to dynamically import the transformation module using the transformer_id directly.
        try:
            transform_module = importlib.import_module(transformer_id)
            app_logger.info(f"Successfully loaded transformation module: {transformer_id}.py")
        except ModuleNotFoundError:
            raise HTTPException(status_code=404, detail=f"Transformation module not found for ID: '{transformer_id}'!")
        except Exception as e:
            app_logger.error(f"Error loading transformation module '{transformer_id}': {e}")
            raise HTTPException(status_code=500, detail=f"Failed to load transformation for ID '{transformer_id}': {e}")

        try:
            transformation_function = getattr(transform_module, 'transform')

        except AttributeError:
            raise HTTPException(status_code=500, detail=f"Transformation function 'transform' not found in module for ID: '{transformer_id}'. "
                                                        f"(Module: {transform_module.__name__}.py)")
        except Exception as e:
            app_logger.error(f"Error getting transform function from module '{transform_module.__name__}': {e}")
            raise HTTPException(status_code=500, detail=f"Failed to get transform function for ID '{transformer_id}': {e}")

        # Apply the dynamically loaded transformation, passing the automatically parsed json_data
        transformed_data = transformation_function(json_data)

        return JSONResponse(content=transformed_data, status_code=200)

    except HTTPException as http_exc:
        # Re-raise HTTPException to be handled by FastAPI's error handling
        raise http_exc
    except Exception as e:
        app_logger.error(f"An unexpected error occurred in transform endpoint: {e}", exc_info=True)
        raise HTTPException(
            status_code=500,
            detail=f"An unexpected error occurred while processing event through transformer '{transformer_id}': {e}"
        )


@app.get('/transformers')
async def list_transformers():
    """
    List all available transformer modules.

    Returns a list of available transformer IDs that can be used in the /transformer/{transformer_id} endpoint.
    """
    available_transformers = []

    # Check internal transformers
    internal_transformers_path = os.path.join(current_dir, 'transformers')
    if os.path.exists(internal_transformers_path):
        for file in os.listdir(internal_transformers_path):
            if file.endswith('.py') and not file.startswith('__'):
                transformer_id = file[:-3]  # Remove .py extension
                available_transformers.append({
                    "id": transformer_id,
                    "type": "internal",
                    "path": f"transformers/{file}"
                })

    # Check external transformers
    external_transformers_path = os.path.join(current_dir, 'transformers_bootstrap')
    if os.path.exists(external_transformers_path):
        for file in os.listdir(external_transformers_path):
            if file.endswith('.py') and not file.startswith('__'):
                transformer_id = file[:-3]  # Remove .py extension
                available_transformers.append({
                    "id": transformer_id,
                    "type": "external",
                    "path": f"transformers_bootstrap/{file}"
                })

    return JSONResponse(
        content={
            "available_transformers": available_transformers,
            "count": len(available_transformers)
        },
        status_code=200
    )
