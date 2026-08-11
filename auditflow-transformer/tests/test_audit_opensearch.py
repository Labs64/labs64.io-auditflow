"""audit_opensearch promotes the well-known vocabulary and passes everything else through.

Promotion is symmetric with audit_clickhouse: the same 7 keys, the same snake_case names. What a
document store does differently is nothing — that symmetry is what makes the model teachable.
"""
import pytest

from transformers import audit_opensearch

FULL_EVENT = {
    "timestamp": "2026-08-07T10:15:30Z",
    "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
    "eventType": "api.call",
    "sourceSystem": "netlicensing/core",
    "tenantId": "V12345678",
    "geolocation": {"lat": 48.1264019, "lon": 11.5407647, "countryCode": "DE",
                    "country": "Germany", "region": "Bavaria", "city": "Munich"},
    "extra": {
        "actionName": "login", "actionStatus": "SUCCESS", "actionMessage": "ok",
        "userId": "customer123", "sessionId": "sess456", "durationMs": 34,
        "responseStatus": 200,
    },
}


def test_promoted_map_is_the_shared_well_known_vocabulary():
    from auditflow_sdk import WELL_KNOWN_EXTRA
    assert audit_opensearch.transform.promoted == dict(WELL_KNOWN_EXTRA)


def test_all_seven_well_known_keys_are_promoted_to_top_level():
    doc = audit_opensearch.transform(FULL_EVENT)
    assert doc["action_name"] == "login"
    assert doc["action_status"] == "SUCCESS"
    assert doc["action_message"] == "ok"
    assert doc["user_id"] == "customer123"
    assert doc["session_id"] == "sess456"
    assert doc["duration_ms"] == 34
    assert doc["response_status"] == 200


def test_a_fully_populated_event_leaves_nothing_in_the_extra_object():
    assert "extra" not in audit_opensearch.transform(FULL_EVENT)


def test_promoted_values_keep_their_json_type():
    # Unlike the ClickHouse Map(String, String), an OpenSearch document keeps native types.
    doc = audit_opensearch.transform(FULL_EVENT)
    assert isinstance(doc["duration_ms"], int)
    assert isinstance(doc["response_status"], int)


def test_unrecognised_extra_keys_stay_in_the_extra_object():
    event = dict(FULL_EVENT, extra={**FULL_EVENT["extra"], "invoiceRef": "INV-1", "retry": 2})
    doc = audit_opensearch.transform(event)
    assert doc["extra"] == {"invoiceRef": "INV-1", "retry": 2}


def test_absent_well_known_keys_are_omitted():
    event = {"eventType": "api.call", "timestamp": "2026-08-07T10:15:30Z",
             "extra": {"invoiceRef": "INV-1"}}
    doc = audit_opensearch.transform(event)
    for field in ("action_name", "action_status", "action_message", "user_id",
                  "session_id", "duration_ms", "response_status"):
        assert field not in doc
    assert doc["extra"] == {"invoiceRef": "INV-1"}


def test_geo_point_is_built_only_when_both_coordinates_are_present():
    doc = audit_opensearch.transform(FULL_EVENT)
    assert doc["location"] == {"lat": 48.1264019, "lon": 11.5407647}
    assert doc["location_city"] == "Munich"

    partial = dict(FULL_EVENT, geolocation={"countryCode": "DE"})
    assert "location" not in audit_opensearch.transform(partial)


def test_empty_event_produces_no_fabricated_fields():
    assert audit_opensearch.transform({}) == {}


def test_env_mapping_promotes_a_deployment_s_own_key(monkeypatch):
    monkeypatch.setenv("AUDITFLOW_PROMOTED_KEYS", '{"invoiceRef": "invoice_ref"}')
    transform = audit_opensearch.make_transform()
    doc = transform({"eventType": "api.call", "extra": {"invoiceRef": "INV-1", "other": "x"}})
    assert doc["invoice_ref"] == "INV-1"
    assert doc["extra"] == {"other": "x"}


def test_malformed_env_mapping_raises(monkeypatch):
    monkeypatch.setenv("AUDITFLOW_PROMOTED_KEYS", '{"invoiceRef": "bad name"}')
    with pytest.raises(ValueError, match="target field name"):
        audit_opensearch.make_transform()
