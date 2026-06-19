"""API-level tests for the transformer service."""
from fastapi.testclient import TestClient

import transformer

client = TestClient(transformer.app)


def test_list_transformers_includes_zero():
    response = client.get("/registry")
    assert response.status_code == 200
    ids = [t["id"] for t in response.json()["transformers"]]
    assert "zero" in ids


def test_zero_transform_is_passthrough():
    payload = {"eventId": "11111111-1111-1111-1111-111111111111", "eventType": "api.call"}
    response = client.post("/transform/zero", json=payload)
    assert response.status_code == 200
    assert response.json() == payload


def test_unknown_transformer_returns_404():
    response = client.post("/transform/definitely_not_a_real_transformer", json={"x": 1})
    assert response.status_code == 404


def test_malformed_transformer_id_returns_400():
    # Hyphen is not allowed by the id regex — rejected before any resolution.
    response = client.post("/transform/bad-id", json={"x": 1})
    assert response.status_code == 400


def test_registry_exposes_zero_metadata():
    response = client.get("/registry")
    assert response.status_code == 200
    zero = next(t for t in response.json()["transformers"] if t["id"] == "zero")
    assert zero["version"] == "1.0.0"


def test_registry_reload():
    response = client.post("/registry/reload")
    assert response.status_code == 200
    assert response.json()["reloaded"] is True
