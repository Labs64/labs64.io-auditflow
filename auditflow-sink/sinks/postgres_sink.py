"""
PostgreSQL Sink - Send events to a PostgreSQL database table.

Each event is stored as JSON in the ``event_data`` column of a table the operator creates::

    CREATE TABLE audit_events (
        id          BIGSERIAL PRIMARY KEY,
        received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        event_data  JSONB NOT NULL
    );
    -- optional, makes a redelivered event a no-op instead of a second row:
    CREATE UNIQUE INDEX audit_events_event_id ON audit_events ((event_data->>'eventId'));

Connections are reused: a few idle connections are kept per database and user, so an event does not
pay for a TCP and TLS handshake plus authentication. With ``batch.enabled`` on the pipeline a batch
is one multi-row ``INSERT`` in one transaction.

Delivery is at-least-once. The insert uses ``ON CONFLICT DO NOTHING``: with the unique index above a
repeated event is skipped; without it a repeat adds a row and readers de-duplicate by ``eventId``.
"""
import json
import logging
import threading

import psycopg

from auditflow_sdk import RejectedEvent, deliver_each, require_properties, sql_identifier

__version__ = "1.1.0"

PROPERTIES = {
    "host": "Database host (required)",
    "port": "Database port (default: 5432)",
    "database": "Database name (required)",
    "user": "Database user (required)",
    "password": "Database password (required; use ${secretRef:<key>})",
    "table": "Table to insert into, optionally schema-qualified (schema.table). Letters, digits and "
             "underscores only (required)",
    "connect-timeout": "Seconds to wait for a new connection (default: 10)",
}

logger = logging.getLogger(__name__)

# The table name is part of the SQL text and cannot be a bound parameter, so it is checked against
# plain identifier syntax instead of being quoted (auditflow_sdk.sql_identifier): an unquoted name
# keeps PostgreSQL's usual case folding, and nothing but a (schema-qualified) identifier can reach
# the statement. 63 is PostgreSQL's identifier length.
_MAX_IDENTIFIER_LENGTH = 63

# Idle connections per (host, port, database, user, password). A connection is used by one thread
# at a time: it is taken out of the list for the insert and put back after a clean commit.
_IDLE = {}
_IDLE_LOCK = threading.Lock()
_MAX_IDLE_PER_TARGET = 4
_MAX_TARGETS = 64


def process(event_data: dict, properties: dict) -> dict:
    """Insert one audit event into PostgreSQL."""
    settings = _settings(properties)
    _insert(settings, [json.dumps(event_data)])
    return _result(settings)


def process_batch(events: list, properties: dict) -> list:
    """Insert a batch of events with one multi-row INSERT in one transaction.

    If the database refuses the statement because of one event's data, the events are inserted one
    by one instead, so the others are stored and only the refused one fails (not retried). Any other
    failure fails the whole batch, which the backend retries.
    """
    settings = _settings(properties)
    try:
        _insert(settings, [json.dumps(event) for event in events])
    except RejectedEvent as e:
        if len(events) == 1:
            return [e]
        logger.warning("PostgreSQL refused a batch of %d event(s) (%s); inserting them one by one", len(events), e)
        return deliver_each(events, properties, process)
    except Exception as e:  # noqa: BLE001 - nothing was committed: every event failed
        if len(events) > 1 and isinstance(e.__cause__, psycopg.IntegrityError):
            # A constraint refused one row and with it the whole statement: store the others. The
            # refused event stays retryable, since a constraint can also be a schema to fix.
            logger.warning("A constraint refused a batch of %d event(s) (%s); inserting them one by one",
                           len(events), e)
            return deliver_each(events, properties, process)
        return [e] * len(events)
    return [_result(settings)] * len(events)


def _settings(properties: dict) -> dict:
    """Validated connection target and table. A ValueError is a configuration error."""
    require_properties(properties, 'host', 'database', 'user', 'password', 'table')
    table = sql_identifier(properties['table'], 'table name', max_parts=2, max_length=_MAX_IDENTIFIER_LENGTH)
    try:
        connect_timeout = int(properties.get('connect-timeout', 10))
    except (TypeError, ValueError):
        raise ValueError(f"Invalid connect-timeout '{properties.get('connect-timeout')}': use whole seconds")
    return {
        'host': properties['host'],
        'port': str(properties.get('port', '5432')),
        'database': properties['database'],
        'user': properties['user'],
        'password': properties['password'],
        'table': table,
        'connect_timeout': max(1, connect_timeout),
    }


def _result(settings: dict) -> dict:
    return {
        "sent": True,
        "destination": "postgres",
        "host": settings['host'],
        "database": settings['database'],
        "table": settings['table'],
    }


def _insert(settings: dict, payloads: list) -> None:
    """One INSERT with a row per payload, committed. Raises RejectedEvent when the database refuses
    the data, RuntimeError for everything else."""
    statement = (f"INSERT INTO {settings['table']} (event_data) VALUES "
                 + ", ".join(["(%s)"] * len(payloads)) + " ON CONFLICT DO NOTHING")
    key = _target(settings)
    conn, reused = _acquire(key, settings)
    try:
        try:
            _execute(conn, statement, payloads)
        except psycopg.OperationalError:
            if not reused:
                raise
            # The server closed this connection while it sat idle (restart, idle timeout, failover).
            # Nothing was committed on it, so the same insert is safe on a fresh connection.
            _close(conn)
            conn = _connect(settings)
            _execute(conn, statement, payloads)
    except psycopg.DataError as e:
        # The connection is fine, the data is not: give the connection back after a rollback.
        if _rollback(conn):
            _release(key, conn)
        else:
            _close(conn)
        raise RejectedEvent(f"PostgreSQL refused the event data: {e}") from e
    except psycopg.Error as e:
        _rollback(conn)
        _close(conn)
        logger.error("Failed to insert into PostgreSQL: %s", e)
        raise RuntimeError(f"Database error: {e}") from e
    except BaseException:
        _close(conn)
        raise
    _release(key, conn)
    logger.info("Inserted %d event(s) into PostgreSQL table '%s'", len(payloads), settings['table'])


def _execute(conn, statement: str, payloads: list) -> None:
    with conn.cursor() as cursor:
        cursor.execute(statement, payloads)
    conn.commit()


def _target(settings: dict) -> tuple:
    return (settings['host'], settings['port'], settings['database'], settings['user'], settings['password'])


def _connect(settings: dict):
    try:
        return psycopg.connect(
            host=settings['host'],
            port=settings['port'],
            dbname=settings['database'],
            user=settings['user'],
            password=settings['password'],
            connect_timeout=settings['connect_timeout'],
        )
    except psycopg.Error as e:
        logger.error("Failed to connect to PostgreSQL at %s:%s: %s", settings['host'], settings['port'], e)
        raise RuntimeError(f"Database error: {e}") from e


def _acquire(key: tuple, settings: dict):
    """An idle connection for the target, or a new one. Returns (connection, reused)."""
    with _IDLE_LOCK:
        idle = _IDLE.get(key)
        while idle:
            conn = idle.pop()
            if not _unusable(conn):
                return conn, True
            _close(conn)
    return _connect(settings), False


def _release(key: tuple, conn) -> None:
    """Keep the connection for the next event, unless it is broken or enough are idle already."""
    if _unusable(conn):
        _close(conn)
        return
    dropped = []
    with _IDLE_LOCK:
        if key not in _IDLE and len(_IDLE) >= _MAX_TARGETS:
            # Many distinct targets (rotated passwords, many tenants): start over rather than grow.
            for connections in _IDLE.values():
                dropped.extend(connections)
            _IDLE.clear()
        idle = _IDLE.setdefault(key, [])
        if len(idle) < _MAX_IDLE_PER_TARGET:
            idle.append(conn)
        else:
            dropped.append(conn)
    for stale in dropped:
        _close(stale)


def _unusable(conn) -> bool:
    return getattr(conn, 'closed', False) is True or getattr(conn, 'broken', False) is True


def _rollback(conn) -> bool:
    try:
        conn.rollback()
        return True
    except Exception:  # noqa: BLE001 - the connection is unusable; the caller closes it
        return False


def _close(conn) -> None:
    try:
        conn.close()
    except Exception:  # noqa: BLE001 - closing a dead connection may fail; nothing to do
        pass
