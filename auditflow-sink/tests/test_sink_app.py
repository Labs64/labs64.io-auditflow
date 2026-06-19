"""API-level tests for the sink service."""
from fastapi.testclient import TestClient

import sink

client = TestClient(sink.app)


def test_list_sinks_includes_logging_sink():
    response = client.get("/registry")
    assert response.status_code == 200
    ids = [s["id"] for s in response.json()["sinks"]]
    assert "logging_sink" in ids


def test_logging_sink_processes_event():
    response = client.post(
        "/sink/logging_sink",
        json={"event_data": {"eventId": "abc"}, "properties": {"format": "json"}},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "success"
    assert body["sink"] == "logging_sink"


def test_unknown_sink_returns_404():
    response = client.post(
        "/sink/definitely_not_a_real_sink",
        json={"event_data": {}, "properties": {}},
    )
    assert response.status_code == 404


def test_malformed_sink_id_returns_400():
    response = client.post(
        "/sink/bad-id",
        json={"event_data": {}, "properties": {}},
    )
    assert response.status_code == 400


def test_registry_exposes_logging_sink_metadata():
    response = client.get("/registry")
    assert response.status_code == 200
    logging_sink = next(s for s in response.json()["sinks"] if s["id"] == "logging_sink")
    assert logging_sink["version"] == "1.0.0"
    assert "log-level" in logging_sink["properties"]


def test_registry_reload():
    response = client.post("/registry/reload")
    assert response.status_code == 200
    assert response.json()["reloaded"] is True


def test_new_catalogue_sinks_are_registered():
    ids = [s["id"] for s in client.get("/registry").json()["sinks"]]
    for expected in ("datadog_sink", "splunk_sink", "snowflake_sink"):
        assert expected in ids


def test_datadog_sink_missing_api_key_errors():
    # require_properties raises -> endpoint returns 500 (no network call attempted).
    response = client.post(
        "/sink/datadog_sink",
        json={"event_data": {"eventId": "abc"}, "properties": {}},
    )
    assert response.status_code == 500
