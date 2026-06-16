"""API-level tests for the sink service (P1-3)."""
from fastapi.testclient import TestClient

import sink

client = TestClient(sink.app)


def test_list_sinks_includes_logging_sink():
    response = client.get("/sinks")
    assert response.status_code == 200
    body = response.json()
    ids = [s["id"] for s in body["available_sinks"]]
    assert "logging_sink" in ids
    assert body["count"] == len(body["available_sinks"])


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
