"""audit_loki must not invent values for absent `extra` keys, and must not drop unknown ones.

`extra` is an open map: the keys below are a convention, not a schema. A stream label of
"unknown_action" is indistinguishable from a publisher that really sent "unknown_action", and a log
line of "N/A" destroys the only human-readable field in the event.
"""
from datetime import datetime

import pytest

from transformers import audit_loki

FULL_EVENT = {
    "timestamp": "2025-07-04T10:00:00Z",
    "eventTime": "2025-07-04T09:59:55Z",
    "eventId": "1234-5678",
    "correlationId": "corr-1",
    "eventType": "audit.test",
    "sourceSystem": "test-system",
    "tenantId": "t_mock",
    "geolocation": {"lat": 1.23, "lon": 4.56, "countryCode": "US"},
    "extra": {
        "actionName": "TEST_ACTION",
        "actionStatus": "SUCCESS",
        "actionMessage": "Test successful",
        "userId": "u1",
        "sessionId": "s1",
        "durationMs": 34,
        "responseStatus": 200,
    },
}


def _only_stream(result):
    assert list(result) == ["streams"]
    assert len(result["streams"]) == 1
    return result["streams"][0]


def _parts(result):
    stream_obj = _only_stream(result)
    nano, line, metadata = stream_obj["values"][0]
    return stream_obj["stream"], nano, line, metadata


@pytest.mark.parametrize("status,level", [
    ("SUCCESS", "INFO"), ("FAILURE", "ERROR"), ("DENIED", "WARN"), ("PENDING", "WARN"),
    ("success", "INFO"),
])
def test_get_log_level_maps_the_outcome_vocabulary(status, level):
    assert audit_loki.get_log_level(status) == level


@pytest.mark.parametrize("status", [None, "", "WEIRD", 7])
def test_get_log_level_returns_none_rather_than_inventing_a_level(status):
    assert audit_loki.get_log_level(status) is None


def test_full_event_populates_labels_and_metadata():
    stream, nano, line, metadata = _parts(audit_loki.transform(FULL_EVENT))

    assert stream == {
        "job": "auditflow",
        "service_name": "test-system",
        "tenant_id": "t_mock",
        "event_type": "audit.test",
        "action_name": "TEST_ACTION",
        "action_status": "SUCCESS",
    }
    assert line == "Test successful"

    # eventTime is the business time and wins over the server receipt timestamp.
    expected = str(int(datetime.fromisoformat("2025-07-04T09:59:55+00:00").timestamp() * 1_000_000_000))
    assert nano == expected

    assert metadata["level"] == "INFO"
    assert metadata["eventId"] == "1234-5678"
    assert metadata["correlationId"] == "corr-1"
    assert metadata["userId"] == "u1"
    assert metadata["sessionId"] == "s1"
    assert metadata["durationMs"] == "34"
    assert metadata["responseStatus"] == "200"
    assert metadata["country_code"] == "US"
    assert metadata["latitude"] == "1.23"
    assert metadata["longitude"] == "4.56"


def test_timestamp_is_used_when_event_time_is_absent():
    event = dict(FULL_EVENT)
    del event["eventTime"]
    _, nano, _, _ = _parts(audit_loki.transform(event))
    expected = str(int(datetime.fromisoformat("2025-07-04T10:00:00+00:00").timestamp() * 1_000_000_000))
    assert nano == expected


def test_unrecognised_extra_keys_reach_structured_metadata():
    # The whole point: a deployment's own field names must not vanish.
    event = dict(FULL_EVENT, extra={**FULL_EVENT["extra"], "invoiceRef": "INV-1", "retry": 2,
                                    "nested": {"b": 1, "a": 2}})
    _, _, _, metadata = _parts(audit_loki.transform(event))
    assert metadata["invoiceRef"] == "INV-1"
    assert metadata["retry"] == "2"
    assert metadata["nested"] == '{"a":2,"b":1}'


def test_custom_keys_do_not_become_stream_labels():
    # Labels are an index dimension in Loki; an unbounded label set is a cardinality failure.
    event = dict(FULL_EVENT, extra={**FULL_EVENT["extra"], "invoiceRef": "INV-1"})
    stream, _, _, _ = _parts(audit_loki.transform(event))
    assert "invoiceRef" not in stream
    assert "invoice_ref" not in stream


def test_absent_well_known_keys_are_omitted_not_faked():
    event = {"eventType": "audit.test", "sourceSystem": "test-system",
             "timestamp": "2025-07-04T10:00:00Z", "extra": {"invoiceRef": "INV-1"}}
    stream, _, line, metadata = _parts(audit_loki.transform(event))

    assert "action_name" not in stream
    assert "action_status" not in stream
    assert "tenant_id" not in stream
    assert "level" not in metadata
    assert "userId" not in metadata
    assert "country_code" not in metadata
    # The log line falls back to the event classification, never to a placeholder.
    assert line == "audit.test"
    assert metadata["invoiceRef"] == "INV-1"


def test_empty_event_fabricates_nothing():
    stream, nano, line, metadata = _parts(audit_loki.transform({}))
    assert stream == {"job": "auditflow"}
    assert line == ""
    assert metadata == {}
    # Loki rejects a zero timestamp as out of range; fall back to now(), not to the epoch.
    assert int(nano) > 1_600_000_000_000_000_000


def test_unparseable_timestamp_falls_back_to_now():
    _, nano, _, _ = _parts(audit_loki.transform({"timestamp": "not-a-date"}))
    assert int(nano) > 1_600_000_000_000_000_000


def test_env_mapping_renames_a_metadata_field(monkeypatch):
    monkeypatch.setenv("AUDITFLOW_PROMOTED_KEYS", '{"invoiceRef": "invoice_ref"}')
    transform = audit_loki.make_transform()
    _, _, _, metadata = _parts(transform({"eventType": "e", "extra": {"invoiceRef": "INV-1"}}))
    assert metadata["invoice_ref"] == "INV-1"
    assert "invoiceRef" not in metadata


def test_promoted_covers_the_well_known_vocabulary():
    from auditflow_sdk import WELL_KNOWN_EXTRA
    assert set(audit_loki.transform.promoted) == set(WELL_KNOWN_EXTRA)
