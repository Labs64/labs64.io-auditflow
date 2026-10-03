"""Snowflake Sink - insert audit events into a Snowflake table as a VARIANT.

Requires the optional ``snowflake-connector-python`` package, which is intentionally NOT bundled
in the (Alpine) image to keep it slim and avoid native-build issues. Install it into the sink
image/venv to enable this sink::

    pip install snowflake-connector-python

The target table is expected to have a single VARIANT column; the event is inserted via
``PARSE_JSON``. Table and column names come from operator configuration, not from event data, and
must be plain identifiers; a name that needs quoting is refused.
"""
import json
import logging

from auditflow_sdk import require_properties, sql_identifier

__version__ = "1.2.0"

PROPERTIES = {
    "account": "Snowflake account identifier (required)",
    "user": "Username (required)",
    "password": "Password (required)",
    "database": "Database (required)",
    "schema": "Schema (required)",
    "table": "Target table with a single VARIANT column, optionally qualified (SCHEMA.TABLE or "
             "DATABASE.SCHEMA.TABLE). Letters, digits, underscores and $ only (required)",
    "warehouse": "Warehouse (optional)",
    "role": "Role (optional)",
    "column": "VARIANT column name: letters, digits, underscores and $ only (default: EVENT)",
}

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
    """Insert a single audit event into a Snowflake VARIANT column."""
    return _insert([event_data], properties)


def process_batch(events: list, properties: dict) -> list:
    """Insert a batch of events with ONE connection and ONE multi-row INSERT.

    Snowflake charges for every statement and a connection takes about a second to open, so a batch
    is where this sink gains the most. The statement is all or nothing: if it fails, every event of
    the batch is retried. Readers de-duplicate by ``eventId`` (at-least-once).
    """
    try:
        result = _insert(events, properties)
    except ValueError:
        raise  # a missing property: fail the call, the events wait for the fixed configuration
    except Exception as e:  # noqa: BLE001 - nothing was inserted: every event failed
        logger.error("Snowflake batch insert of %d event(s) failed: %s", len(events), e)
        return [RuntimeError(f"Snowflake insert failed: {e}")] * len(events)
    return [result] * len(events)


def _insert(events: list, properties: dict) -> dict:
    require_properties(properties, "account", "user", "password", "database", "schema", "table")
    # The names are part of the statement, so only plain identifiers are accepted (a ValueError
    # otherwise, before a connection is opened). The table may be qualified: SCHEMA.TABLE or
    # DATABASE.SCHEMA.TABLE.
    table = sql_identifier(properties["table"], "table name", max_parts=3, dollar=True)
    column = sql_identifier(properties.get("column", "EVENT"), "column name", dollar=True)

    try:
        import snowflake.connector  # lazy: optional dependency
    except ImportError as e:
        raise RuntimeError(
            "snowflake-connector-python is not installed; cannot use snowflake_sink "
            "(pip install snowflake-connector-python)"
        ) from e

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
        # The identifiers were checked above; the event payload is parameterized.
        # PARSE_JSON is not allowed inside a VALUES list, so the rows are selected from one.
        if len(events) == 1:
            cursor.execute(
                f"INSERT INTO {table} ({column}) SELECT PARSE_JSON(%s)",
                (json.dumps(events[0]),),
            )
        else:
            rows = ", ".join(["(%s)"] * len(events))
            cursor.execute(
                f"INSERT INTO {table} ({column}) SELECT PARSE_JSON(column1) FROM VALUES {rows}",
                tuple(json.dumps(event) for event in events),
            )
        cursor.close()
    finally:
        connection.close()

    logger.info("Inserted %d audit event(s) into Snowflake table '%s'", len(events), table)
    return {"delivered": True, "table": table}
