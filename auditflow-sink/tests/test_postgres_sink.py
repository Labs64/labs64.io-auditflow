"""postgres_sink: connection reuse, multi-row batch insert, and a table name that cannot carry SQL."""
import json

import psycopg
import pytest

from sinks import postgres_sink

PROPS = {"host": "localhost", "database": "audit_db", "user": "admin", "password": "password",
         "table": "audit_events"}


class FakeConnection:
    """Records statements; ``fail`` maps a call number (1-based) to the exception to raise."""

    def __init__(self, server, fail=None):
        self.server = server
        self.fail = fail or {}
        self.closed = False
        self.broken = False
        self.commits = 0
        self.rollbacks = 0
        self.calls = 0

    def cursor(self):
        return self

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, statement, params):
        self.calls += 1
        error = self.fail.get(self.calls)
        if error is not None:
            raise error
        check = self.server.reject
        if check is not None and any(check(p) for p in params):
            raise psycopg.DataError("unsupported Unicode escape sequence")
        check = self.server.violates
        if check is not None and any(check(p) for p in params):
            raise psycopg.IntegrityError("violates check constraint")
        self.server.pending = (statement, list(params))

    def commit(self):
        self.commits += 1
        self.server.statements.append(self.server.pending)

    def rollback(self):
        self.rollbacks += 1

    def close(self):
        self.closed = True


class FakeServer:
    def __init__(self):
        self.connections = []
        self.connect_kwargs = []
        self.statements = []     # committed (statement, params)
        self.pending = None
        self.reject = None       # predicate on one payload: the database refuses its data
        self.violates = None     # predicate on one payload: a constraint refuses the row
        self.next_fail = []      # per new connection: its ``fail`` map

    def connect(self, **kwargs):
        self.connect_kwargs.append(kwargs)
        conn = FakeConnection(self, self.next_fail.pop(0) if self.next_fail else None)
        self.connections.append(conn)
        return conn

    def rows(self):
        return [json.loads(p) for _, params in self.statements for p in params]


@pytest.fixture
def server(monkeypatch):
    fake = FakeServer()
    monkeypatch.setattr(postgres_sink.psycopg, "connect", fake.connect)
    monkeypatch.setattr(postgres_sink, "_IDLE", {})
    return fake


def event(i):
    return {"eventId": f"e{i}", "tenantId": "acme"}


# --- configuration ---

def test_missing_properties():
    with pytest.raises(ValueError, match="Missing required properties: database, user, password, table"):
        postgres_sink.process({}, {"host": "localhost"})


@pytest.mark.parametrize("table", ["audit_events", "audit.audit_events", "Audit_Events_2", "_t"])
def test_plain_and_schema_qualified_table_names_are_accepted(server, table):
    postgres_sink.process(event(1), {**PROPS, "table": table})
    assert server.statements[0][0].startswith(f"INSERT INTO {table} (event_data) VALUES ")


@pytest.mark.parametrize("table", [
    "audit_events; DROP TABLE users",
    "audit_events (event_data) VALUES ('x'); --",
    'audit"events',
    "audit events",
    "a.b.c",
    "1table",
    "audit-events",
    "t" * 64,
    "",
])
def test_a_table_name_that_is_not_an_identifier_is_refused_before_connecting(server, table):
    with pytest.raises(ValueError):
        postgres_sink.process(event(1), {**PROPS, "table": table})
    assert server.connections == []


def test_an_invalid_connect_timeout_is_a_configuration_error(server):
    with pytest.raises(ValueError, match="connect-timeout"):
        postgres_sink.process(event(1), {**PROPS, "connect-timeout": "soon"})


# --- single events and connection reuse ---

def test_successful_insert(server):
    result = postgres_sink.process(event(1), PROPS)

    assert result == {"sent": True, "destination": "postgres", "host": "localhost",
                      "database": "audit_db", "table": "audit_events"}
    assert server.connect_kwargs == [dict(host="localhost", port="5432", dbname="audit_db", user="admin",
                                          password="password", connect_timeout=10)]
    statement, params = server.statements[0]
    assert statement == "INSERT INTO audit_events (event_data) VALUES (%s) ON CONFLICT DO NOTHING"
    assert json.loads(params[0]) == event(1)      # the event is a bound parameter, never SQL text
    assert server.connections[0].commits == 1


def test_the_connection_is_reused_for_the_next_event(server):
    for i in range(5):
        postgres_sink.process(event(i), PROPS)
    assert len(server.connections) == 1
    assert server.connections[0].closed is False
    assert [row["eventId"] for row in server.rows()] == ["e0", "e1", "e2", "e3", "e4"]


def test_a_different_database_or_user_gets_its_own_connection(server):
    postgres_sink.process(event(1), PROPS)
    postgres_sink.process(event(2), {**PROPS, "database": "other"})
    postgres_sink.process(event(3), {**PROPS, "password": "rotated"})
    postgres_sink.process(event(4), PROPS)
    assert len(server.connections) == 3


def test_no_more_than_the_idle_limit_is_kept(server):
    connections = [postgres_sink._connect(postgres_sink._settings(PROPS)) for _ in range(6)]
    key = postgres_sink._target(postgres_sink._settings(PROPS))
    for conn in connections:
        postgres_sink._release(key, conn)
    assert sum(1 for c in connections if not c.closed) == postgres_sink._MAX_IDLE_PER_TARGET
    assert sum(1 for c in connections if c.closed) == 2


def test_a_connection_the_server_closed_while_idle_is_replaced_and_the_insert_repeated(server):
    postgres_sink.process(event(1), PROPS)
    server.connections[0].fail = {2: psycopg.OperationalError("server closed the connection unexpectedly")}

    postgres_sink.process(event(2), PROPS)

    assert len(server.connections) == 2
    assert server.connections[0].closed is True
    assert [row["eventId"] for row in server.rows()] == ["e1", "e2"]
    # and the fresh connection is the one kept for later
    postgres_sink.process(event(3), PROPS)
    assert len(server.connections) == 2


def test_a_closed_idle_connection_is_not_handed_out(server):
    postgres_sink.process(event(1), PROPS)
    server.connections[0].closed = True
    postgres_sink.process(event(2), PROPS)
    assert len(server.connections) == 2


def test_a_failure_on_a_fresh_connection_is_not_repeated(server):
    server.next_fail = [{1: psycopg.OperationalError("connection refused")}]
    with pytest.raises(RuntimeError, match="Database error: connection refused"):
        postgres_sink.process(event(1), PROPS)
    assert len(server.connections) == 1
    assert server.connections[0].closed is True
    assert server.statements == []


def test_database_error_rolls_back_and_drops_the_connection(server):
    server.next_fail = [{1: psycopg.Error("Connection lost")}]
    with pytest.raises(RuntimeError, match="Database error: Connection lost"):
        postgres_sink.process({"test": "data"}, PROPS)
    conn = server.connections[0]
    assert conn.rollbacks == 1 and conn.closed is True
    assert postgres_sink._IDLE == {}


def test_a_failed_connect_is_a_retryable_error(monkeypatch):
    def refuse(**kwargs):
        raise psycopg.OperationalError("could not translate host name")
    monkeypatch.setattr(postgres_sink.psycopg, "connect", refuse)
    monkeypatch.setattr(postgres_sink, "_IDLE", {})
    with pytest.raises(RuntimeError, match="Database error"):
        postgres_sink.process(event(1), PROPS)


def test_refused_data_is_a_rejected_event_and_keeps_the_connection(server):
    server.reject = lambda payload: "bad" in payload
    with pytest.raises(ValueError, match="refused the event data"):
        postgres_sink.process({"eventId": "bad"}, PROPS)
    conn = server.connections[0]
    assert conn.rollbacks == 1 and conn.closed is False
    postgres_sink.process(event(1), PROPS)
    assert len(server.connections) == 1


# --- batch ---

def test_a_batch_is_one_multi_row_insert_in_one_transaction(server):
    events = [event(i) for i in range(3)]
    outcomes = postgres_sink.process_batch(events, PROPS)

    assert [o["sent"] for o in outcomes] == [True, True, True]
    assert len(server.statements) == 1
    statement, params = server.statements[0]
    assert statement == "INSERT INTO audit_events (event_data) VALUES (%s), (%s), (%s) ON CONFLICT DO NOTHING"
    assert [json.loads(p) for p in params] == events
    assert server.connections[0].commits == 1


def test_a_failed_batch_fails_every_event_as_retryable(server):
    server.next_fail = [{1: psycopg.OperationalError("too many connections")}]
    outcomes = postgres_sink.process_batch([event(1), event(2)], PROPS)
    assert all(isinstance(o, RuntimeError) for o in outcomes)
    assert not any(isinstance(o, ValueError) for o in outcomes)
    assert server.statements == []


def test_one_refused_event_does_not_take_the_batch_with_it(server):
    server.reject = lambda payload: "poison" in payload
    events = [event(1), {"eventId": "poison"}, event(3)]

    outcomes = postgres_sink.process_batch(events, PROPS)

    assert isinstance(outcomes[0], dict) and isinstance(outcomes[2], dict)
    assert isinstance(outcomes[1], ValueError)                 # not retried
    assert [row["eventId"] for row in server.rows()] == ["e1", "e3"]
    assert len(server.connections) == 1                         # all on the reused connection


def test_a_constraint_violation_stores_the_other_events_and_stays_retryable(server):
    server.violates = lambda payload: "e2" in payload
    outcomes = postgres_sink.process_batch([event(1), event(2), event(3)], PROPS)
    assert isinstance(outcomes[0], dict) and isinstance(outcomes[2], dict)
    assert isinstance(outcomes[1], RuntimeError) and not isinstance(outcomes[1], ValueError)
    assert [row["eventId"] for row in server.rows()] == ["e1", "e3"]


def test_a_batch_with_a_bad_table_name_raises_for_the_whole_call(server):
    with pytest.raises(ValueError):
        postgres_sink.process_batch([event(1)], {**PROPS, "table": "x; DROP TABLE y"})
    assert server.connections == []


def test_the_batch_endpoint_marks_refused_data_as_not_retryable(server, monkeypatch):
    from fastapi.testclient import TestClient
    import sink

    monkeypatch.setattr(sink.registry, "resolve", lambda sink_id: postgres_sink.process)
    server.reject = lambda payload: "poison" in payload
    response = TestClient(sink.app).post("/sink/postgres_sink/batch", json={
        "events": [event(1), {"eventId": "poison"}], "properties": PROPS})
    body = response.json()
    assert response.status_code == 200 and body["status"] == "partial"
    assert body["results"][0]["status"] == "success"
    assert body["results"][1] == {"index": 1, "status": "error", "retryable": False,
                                  "error": body["results"][1]["error"]}
