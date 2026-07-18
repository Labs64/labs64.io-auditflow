"""
PostgreSQL Sink - Send events to a PostgreSQL database table.

This sink connects to a PostgreSQL database via psycopg and inserts the event data.
"""
import logging
import json
import psycopg

__version__ = "1.0.0"

PROPERTIES = {
    "host": "Database host (required)",
    "port": "Database port (default: 5432)",
    "database": "Database name (required)",
    "user": "Database user (required)",
    "password": "Database password (required)",
    "table": "Table name to insert into (required)",
}

logger = logging.getLogger(__name__)


def process(event_data: dict, properties: dict) -> dict:
    """
    Process an audit event by inserting it into PostgreSQL.
    """
    # Validate required properties
    required_props = ['host', 'database', 'user', 'password', 'table']
    missing = [prop for prop in required_props if not properties.get(prop)]
    if missing:
        raise ValueError(f"Missing required propert{'y' if len(missing) == 1 else 'ies'}: {', '.join(missing)}")

    host = properties['host']
    port = properties.get('port', '5432')
    database = properties['database']
    user = properties['user']
    password = properties['password']
    table = properties['table']

    conn = None
    try:
        # Establish connection
        conn = psycopg.connect(
            host=host,
            port=port,
            dbname=database,
            user=user,
            password=password
        )
        
        with conn.cursor() as cursor:
            # We insert the raw event_data as a JSONB column named 'event_data'.
            # Alternatively, you could map fields to specific columns.
            insert_query = f"INSERT INTO {table} (event_data) VALUES (%s)"
            cursor.execute(insert_query, (json.dumps(event_data),))
        
        conn.commit()
        
        return {
            "sent": True,
            "destination": "postgres",
            "host": host,
            "database": database,
            "table": table
        }
        
    except psycopg.Error as e:
        if conn:
            conn.rollback()
        logger.error(f"Failed to insert into PostgreSQL: {e}")
        raise RuntimeError(f"Database error: {e}")
    finally:
        if conn:
            conn.close()
