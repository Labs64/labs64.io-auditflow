"""Batch delivery: POST /sink/<id>/batch and aws_s3_sink.process_batch."""
import gzip
import json
import sys
import types

import pytest
from fastapi.testclient import TestClient

import sink
from sinks import aws_s3_sink

client = TestClient(sink.app)


def _fake_sink_module(monkeypatch, name, process, process_batch=None):
    module = types.ModuleType(name)
    process.__module__ = name
    module.process = process
    if process_batch is not None:
        module.process_batch = process_batch
    monkeypatch.setitem(sys.modules, name, module)
    monkeypatch.setattr(sink.registry, "resolve", lambda sink_id: process)


def test_without_process_batch_each_event_goes_through_process(monkeypatch):
    seen = []

    def process(event, properties):
        if event.get("fail") == "data":
            raise ValueError("bad field")
        if event.get("fail") == "io":
            raise ConnectionError("timeout")
        seen.append(event["eventId"])
        return {"ok": True}

    _fake_sink_module(monkeypatch, "fake_sink_a", process)
    response = client.post("/sink/fake/batch", json={"events": [
        {"eventId": "1"}, {"eventId": "2", "fail": "data"}, {"eventId": "3", "fail": "io"}], "properties": {}})
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "partial"
    assert [r["status"] for r in body["results"]] == ["success", "error", "error"]
    assert body["results"][1]["retryable"] is False   # ValueError: bad input, never retry
    assert body["results"][2]["retryable"] is True    # transient
    assert seen == ["1"]


def test_process_batch_is_used_when_the_sink_has_one(monkeypatch):
    calls = []

    def process(event, properties):
        raise AssertionError("process must not be called when process_batch exists")

    def process_batch(events, properties):
        calls.append(len(events))
        return [{"ok": True} for _ in events]

    _fake_sink_module(monkeypatch, "fake_sink_b", process, process_batch)
    response = client.post("/sink/fake/batch", json={"events": [{"eventId": "1"}, {"eventId": "2"}]})
    assert response.status_code == 200
    assert response.json()["status"] == "success"
    assert calls == [2]


def test_process_batch_with_the_wrong_number_of_outcomes_fails_the_whole_call(monkeypatch):
    _fake_sink_module(monkeypatch, "fake_sink_c", lambda e, p: None, lambda events, p: [None])
    response = client.post("/sink/fake/batch", json={"events": [{"eventId": "1"}, {"eventId": "2"}]})
    assert response.status_code == 500


@pytest.mark.parametrize("body", [{}, {"events": []}, {"events": "x"}])
def test_invalid_batch_is_400(monkeypatch, body):
    _fake_sink_module(monkeypatch, "fake_sink_d", lambda e, p: None)
    assert client.post("/sink/fake/batch", json=body).status_code == 400


def test_unknown_sink_is_404():
    assert client.post("/sink/definitely_not_a_real_sink/batch", json={"events": [{}]}).status_code == 404


def test_malformed_sink_id_is_400():
    assert client.post("/sink/bad-id/batch", json={"events": [{}]}).status_code == 400


# --- aws_s3_sink.process_batch ---

class RecordingS3:
    def __init__(self, fail_keys=()):
        self.puts = []
        self.fail_keys = fail_keys

    def put_object(self, **kwargs):
        if any(k in kwargs["Key"] for k in self.fail_keys):
            raise RuntimeError("SlowDown")
        self.puts.append(kwargs)
        return {"ETag": '"x"'}


@pytest.fixture
def s3(monkeypatch):
    fake = RecordingS3()
    monkeypatch.setattr(aws_s3_sink, "_get_s3_client", lambda *a: fake)
    return fake


PROPS = {"bucket": "b", "prefix": "tenants/",
         "partition-format": "vendor_id={extra.vendor_id}/year=%Y/month=%m/day=%d/"}


def event(i, vendor):
    return {"eventId": f"00000000-0000-0000-0000-00000000000{i}", "tenantId": "acme",
            "timestamp": "2026-10-02T14:26:39Z", "extra": {"vendor_id": vendor}}


def test_one_jsonl_object_per_key_prefix(s3):
    events = [event(1, "V1"), event(2, "V2"), event(3, "V1")]
    outcomes = aws_s3_sink.process_batch(events, PROPS)
    assert all(isinstance(o, dict) for o in outcomes)
    assert len(s3.puts) == 2
    by_prefix = {p["Key"].rsplit("/", 1)[0]: p for p in s3.puts}
    v1 = by_prefix["tenants/tenant=acme/vendor_id=V1/year=2026/month=10/day=02"]
    lines = v1["Body"].decode().splitlines()
    assert [json.loads(line)["eventId"] for line in lines] == [events[0]["eventId"], events[2]["eventId"]]
    assert v1["Key"].endswith(".jsonl")
    assert v1["Metadata"]["event-count"] == "2"


def test_same_group_gets_the_same_object_name_so_a_redelivery_overwrites(s3):
    events = [event(1, "V1"), event(3, "V1")]
    aws_s3_sink.process_batch(events, PROPS)
    aws_s3_sink.process_batch(list(reversed(events)), PROPS)
    assert s3.puts[0]["Key"] == s3.puts[1]["Key"]


def test_compressed_batch(s3):
    aws_s3_sink.process_batch([event(1, "V1")], {**PROPS, "compress": "true"})
    put = s3.puts[0]
    assert put["Key"].endswith(".jsonl.gz")
    assert json.loads(gzip.decompress(put["Body"]).decode().strip())["eventId"] == event(1, "V1")["eventId"]


def test_a_failed_put_fails_only_its_group(monkeypatch):
    fake = RecordingS3(fail_keys=("vendor_id=V2",))
    monkeypatch.setattr(aws_s3_sink, "_get_s3_client", lambda *a: fake)
    outcomes = aws_s3_sink.process_batch([event(1, "V1"), event(2, "V2")], PROPS)
    assert isinstance(outcomes[0], dict)
    assert isinstance(outcomes[1], Exception)


def test_missing_bucket_is_a_value_error():
    with pytest.raises(ValueError):
        aws_s3_sink.process_batch([event(1, "V1")], {})
