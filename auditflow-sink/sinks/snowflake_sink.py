"""Snowflake Sink - insert audit events into a Snowflake table as a VARIANT.

Requires the optional ``snowflake-connector-python`` package, which is intentionally NOT bundled
in the (Alpine) image to keep it slim and avoid native-build issues. Install it into the sink
image/venv to enable this sink::

    pip install snowflake-connector-python

The target table is expected to have a single VARIANT column; the event is inserted via
``PARSE_JSON``. Table/column/connection values come from operator configuration (trusted), not
from event data.
"""
import json
import logging

from auditflow_sdk import require_properties

__version__ = "1.0.0"

PROPERTIES = {
    "account": "Snowflake account identifier (required)",
    "user": "Username (required)",
    "password": "Password (required)",
    "database": "Database (required)",
    "schema": "Schema (required)",
    "table": "Target table with a single VARIANT column (required)",
    "warehouse": "Warehouse (optional)",
    "role": "Role (optional)",
    "column": "VARIANT column name (default: EVENT)",
}

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
    """Insert a single audit event into a Snowflake VARIANT column."""
    require_properties(properties, "account", "user", "password", "database", "schema", "table")

    try:
        import snowflake.connector  # lazy: optional dependency
    except ImportError as e:
        raise RuntimeError(
            "snowflake-connector-python is not installed; cannot use snowflake_sink "
            "(pip install snowflake-connector-python)"
        ) from e

    column = properties.get("column", "EVENT")
    table = properties["table"]

    connection = snowflake.connector.connect(
        account=properties["account"],
        user=properties["user"],
        password=properties["password"],
        database=properties["database"],
        schema=properties["schema"],
        warehouse=properties.get("warehouse"),
        role=properties.get("role"),
    )
    try:
        cursor = connection.cursor()
        # Identifiers are operator-configured (trusted); the event payload is parameterized.
        cursor.execute(
            f"INSERT INTO {table} ({column}) SELECT PARSE_JSON(%s)",
            (json.dumps(event_data),),
        )
        cursor.close()
    finally:
        connection.close()

    logger.info("Inserted audit event into Snowflake table '%s'", table)
    return {"delivered": True, "table": table}
