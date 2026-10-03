"""process_batch of the sinks that write a batch in one call (aws_s3_sink is in test_sink_batch.py,
postgres_sink in test_postgres_sink.py).

Every test checks the contract of ``POST /sink/<id>/batch``: one outcome per event, in input order,
a dict for a delivered event and an Exception for a failed one, where a ValueError is not retried.
"""
import gzip
import json
import sys
import types
from datetime import datetime, timezone

import pytest
import requests
from fastapi.testclient import TestClient

import auditflow_sdk
import sink
from sinks import (aws_cloudwatch_sink, aws_s3_sink, azure_blob_sink, clickhouse_sink, datadog_sink, gcs_sink,
                   loki_sink, opensearch_sink, snowflake_sink, splunk_sink)

client = TestClient(sink.app)


def event(i, timestamp="2026-10-02T14:26:39Z"):
    return {"eventId": f"00000000-0000-0000-0000-00000000000{i}", "tenantId": "acme", "timestamp": timestamp,
            "eventType": "api.call", "sourceSystem": "core"}


def delivered(outcomes):
    return [isinstance(o, dict) for o in outcomes]


def retryable(outcome):
    return isinstance(outcome, Exception) and not isinstance(outcome, ValueError)


class FakeResponse:
    def __init__(self, status_code=200, payload=None, text="", headers=None):
        self.status_code = status_code
        self._payload = payload if payload is not None else {}
        self.text = text
        self.headers = headers or {}

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.exceptions.HTTPError(f"{self.status_code} Error", response=self)


class FakeHttp:
    """Stands in for requests.post: answers from a queue (last answer repeats), records the calls."""

    def __init__(self, *answers):
        self.answers = list(answers) or [FakeResponse()]
        self.calls = []

    def __call__(self, url, **kwargs):
        self.calls.append({"url": url, **kwargs})
        answer = self.answers.pop(0) if len(self.answers) > 1 else self.answers[0]
        if isinstance(answer, Exception):
            raise answer
        return answer(kwargs) if callable(answer) else answer


@pytest.fixture
def http(monkeypatch):
    def install(*answers):
        fake = FakeHttp(*answers)
        monkeypatch.setattr(requests, "post", fake)
        return fake
    return install


# --- shared helpers (auditflow_sdk) ---

def test_chunk_indexes_respects_count_and_size_and_keeps_order():
    assert auditflow_sdk.chunk_indexes([1, 1, 1, 1, 1], max_count=2, max_bytes=100) == [[0, 1], [2, 3], [4]]
    assert auditflow_sdk.chunk_indexes([40, 40, 40], max_count=10, max_bytes=100) == [[0, 1], [2]]
    assert auditflow_sdk.chunk_indexes([500, 1], max_count=10, max_bytes=100) == [[0], [1]]   # oversized: alone
    assert auditflow_sdk.chunk_indexes([], max_count=10, max_bytes=100) == []


def test_batch_object_name_is_the_same_for_the_same_events_in_any_order():
    early, late = event(1, "2026-10-02T14:26:39Z"), event(2, "2026-10-02T18:00:00Z")
    name = auditflow_sdk.batch_object_name([early, late], compress=False)
    assert name == auditflow_sdk.batch_object_name([late, early], compress=False)
    assert name.startswith("20261002-142639-batch-") and name.endswith(".jsonl")
    assert auditflow_sdk.batch_object_name([early], compress=True).endswith(".jsonl.gz")
    assert name != auditflow_sdk.batch_object_name([early], compress=False)


def test_event_datetime_reads_the_event_timestamp_and_treats_a_naive_one_as_utc():
    assert auditflow_sdk.event_datetime({"timestamp": "2026-10-02T14:26:39Z"}) == \
        datetime(2026, 10, 2, 14, 26, 39, tzinfo=timezone.utc)
    assert auditflow_sdk.event_datetime({"timestamp": "2026-10-02T14:26:39"}).tzinfo is not None
    assert auditflow_sdk.event_datetime({"timestamp": "not a date"}).tzinfo is not None
    assert auditflow_sdk.event_datetime({}).tzinfo is not None


def test_jsonl_body_is_stable_when_compressed():
    group = [event(1), event(2)]
    assert auditflow_sdk.jsonl_body(group, True) == auditflow_sdk.jsonl_body(group, True)
    lines = gzip.decompress(auditflow_sdk.jsonl_body(group, True)).decode().splitlines()
    assert [json.loads(line)["eventId"] for line in lines] == [e["eventId"] for e in group]


def test_deliver_each_reports_every_event_on_its_own():
    def process(e, properties):
        if e["eventId"].endswith("2"):
            raise auditflow_sdk.RejectedEvent("bad")
        return {"ok": True}
    outcomes = auditflow_sdk.deliver_each([event(1), event(2), event(3)], {}, process)
    assert delivered(outcomes) == [True, False, True]
    assert isinstance(outcomes[1], ValueError)


NOT_IDENTIFIERS = [
    "audit_events; DROP TABLE users",
    "audit_events FORMAT JSONEachRow SETTINGS x=1",
    "audit_events (c) SELECT 1 --",
    "t/*x*/",
    'audit"events',
    "`audit`",
    "audit events",
    "audit-events",
    "1table",
    "a..b",
    ".a",
    "a.",
    "",
    "   ",
    "t\nx",
]


@pytest.mark.parametrize("value", ["audit_events", "_t", "Audit_Events_2", "  padded  "])
def test_sql_identifier_accepts_plain_names(value):
    assert auditflow_sdk.sql_identifier(value, "table name") == value.strip()


@pytest.mark.parametrize("value", NOT_IDENTIFIERS + [None, "a.b", "A$B"])
def test_sql_identifier_refuses_anything_else(value):
    with pytest.raises(ValueError, match="Invalid table name"):
        auditflow_sdk.sql_identifier(value, "table name")


def test_sql_identifier_parts_length_and_dollar_are_configurable():
    assert auditflow_sdk.sql_identifier("db.schema.t", "table name", max_parts=3) == "db.schema.t"
    with pytest.raises(ValueError):
        auditflow_sdk.sql_identifier("a.b.c.d", "table name", max_parts=3)
    assert auditflow_sdk.sql_identifier("A$B", "table name", dollar=True) == "A$B"
    with pytest.raises(ValueError):
        auditflow_sdk.sql_identifier("$A", "table name", dollar=True)       # must not start with $
    assert auditflow_sdk.sql_identifier("t" * 63, "table name", max_length=63)
    with pytest.raises(ValueError):
        auditflow_sdk.sql_identifier("t" * 64, "table name", max_length=63)
    with pytest.raises(ValueError):
        auditflow_sdk.sql_identifier("ok." + "t" * 64, "table name", max_parts=2, max_length=63)


# --- the service: registry flag and sinks without process_batch ---

NATIVE_BATCH = {"aws_s3_sink", "gcs_sink", "azure_blob_sink", "clickhouse_sink", "snowflake_sink", "postgres_sink",
                "opensearch_sink", "aws_cloudwatch_sink", "datadog_sink", "splunk_sink", "loki_sink"}


def test_registry_says_which_sinks_write_a_batch_in_one_call():
    sinks = {s["id"]: s["batch"] for s in client.get("/registry").json()["sinks"]}
    assert {sink_id for sink_id, batch in sinks.items() if batch} == NATIVE_BATCH
    for one_by_one in ("logging_sink", "webhook_sink", "syslog_sink", "netlicensing_sink"):
        assert sinks[one_by_one] is False


def test_a_sink_without_process_batch_keeps_the_event_order_in_its_answer(monkeypatch):
    import threading
    import time
    threads = set()

    def process(e, properties):
        threads.add(threading.get_ident())
        time.sleep(0.02 if e["n"] % 2 else 0)     # finish out of order
        if e["n"] == 3:
            raise ConnectionError("down")
        return {"n": e["n"]}

    module = types.ModuleType("fake_slow_sink")
    process.__module__ = "fake_slow_sink"
    module.process = process
    monkeypatch.setitem(sys.modules, "fake_slow_sink", module)
    results = sink._run_batch(process, [{"n": n} for n in range(20)], {})
    assert [r["index"] for r in results] == list(range(20))
    assert [r["status"] for r in results] == ["error" if n == 3 else "success" for n in range(20)]
    assert results[3]["retryable"] is True
    assert 1 < len(threads) <= sink.FALLBACK_BATCH_WORKERS


def test_s3_batch_name_does_not_depend_on_the_order_of_events_with_different_times(monkeypatch):
    puts = []

    class S3:
        def put_object(self, **kwargs):
            puts.append(kwargs["Key"])
            return {}
    monkeypatch.setattr(aws_s3_sink, "_get_s3_client", lambda *a: S3())
    events = [event(1, "2026-10-02T14:26:39Z"), event(2, "2026-10-02T09:00:00Z")]
    aws_s3_sink.process_batch(events, {"bucket": "b"})
    aws_s3_sink.process_batch(list(reversed(events)), {"bucket": "b"})
    assert puts[0] == puts[1]
    assert "/20261002-090000-batch-" in puts[0]


# --- clickhouse_sink ---

CH = {"service-url": "http://clickhouse:8123", "database": "audit", "table": "audit_events"}


def test_clickhouse_batch_is_one_insert_with_a_row_per_line(http):
    fake = http(FakeResponse(200))
    rows = [{"event_id": "a"}, {"event_id": "b"}, {"event_id": "c"}]
    outcomes = clickhouse_sink.process_batch(rows, CH)
    assert delivered(outcomes) == [True, True, True]
    assert len(fake.calls) == 1
    assert fake.calls[0]["params"]["query"] == "INSERT INTO audit.audit_events FORMAT JSONEachRow"
    assert [json.loads(line) for line in fake.calls[0]["data"].decode().split("\n")] == rows


def test_clickhouse_single_event_body_is_unchanged(http):
    fake = http(FakeResponse(200))
    clickhouse_sink.process({"event_id": "a"}, CH)
    assert fake.calls[0]["data"] == b'{"event_id":"a"}'


def test_clickhouse_unreadable_row_fails_alone(http):
    def answer(kwargs):
        bad = b'"bad"' in kwargs["data"]
        return FakeResponse(400, text="Cannot parse input", headers={"X-ClickHouse-Exception-Code": "27"}) if bad \
            else FakeResponse(200)
    fake = http(answer)
    outcomes = clickhouse_sink.process_batch([{"event_id": "a"}, {"event_id": "bad"}, {"event_id": "c"}], CH)
    assert delivered(outcomes) == [True, False, True]
    assert len(fake.calls) == 4          # the batch, then the three rows one by one


def test_clickhouse_server_error_fails_the_whole_batch_without_resending_rows(http):
    fake = http(FakeResponse(500, text="Memory limit exceeded"))
    outcomes = clickhouse_sink.process_batch([{"event_id": "a"}, {"event_id": "b"}], CH)
    assert all(retryable(o) for o in outcomes)
    assert len(fake.calls) == 1


def test_clickhouse_unreachable_fails_the_whole_batch(http):
    http(requests.exceptions.ConnectionError("refused"))
    outcomes = clickhouse_sink.process_batch([{"event_id": "a"}, {"event_id": "b"}], CH)
    assert all(retryable(o) for o in outcomes)


def test_clickhouse_missing_property_fails_the_call():
    with pytest.raises(ValueError):
        clickhouse_sink.process_batch([{"event_id": "a"}], {})


@pytest.mark.parametrize("name", NOT_IDENTIFIERS[:-3] + ["audit.audit_events"])
@pytest.mark.parametrize("key", ["table", "database"])
def test_clickhouse_refuses_a_name_that_is_not_an_identifier_before_sending(http, key, name):
    fake = http(FakeResponse(200))
    for call in (lambda p: clickhouse_sink.process({"event_id": "a"}, p),
                 lambda p: clickhouse_sink.process_batch([{"event_id": "a"}, {"event_id": "b"}], p)):
        with pytest.raises(ValueError, match=f"Invalid {key} name"):
            call({**CH, key: name})
    assert fake.calls == []


def test_clickhouse_default_database_and_plain_names_still_work(http):
    fake = http(FakeResponse(200))
    clickhouse_sink.process({"event_id": "a"}, {"service-url": "http://clickhouse:8123", "table": "Audit_Events_2"})
    assert fake.calls[0]["params"]["query"] == "INSERT INTO default.Audit_Events_2 FORMAT JSONEachRow"


# --- snowflake_sink ---

SF = {"account": "acc", "user": "u", "password": "p", "database": "db", "schema": "s", "table": "AUDIT"}


@pytest.fixture
def snowflake(monkeypatch):
    state = types.SimpleNamespace(connects=0, executed=[], closed=0, fail=None)

    class Cursor:
        def execute(self, statement, params):
            if state.fail:
                raise state.fail
            state.executed.append((statement, params))

        def close(self):
            pass

    class Connection:
        def cursor(self):
            return Cursor()

        def close(self):
            state.closed += 1

    def connect(**kwargs):
        state.connects += 1
        return Connection()

    package = types.ModuleType("snowflake")
    connector = types.ModuleType("snowflake.connector")
    connector.connect = connect
    package.connector = connector
    monkeypatch.setitem(sys.modules, "snowflake", package)
    monkeypatch.setitem(sys.modules, "snowflake.connector", connector)
    return state


def test_snowflake_batch_is_one_connection_and_one_statement(snowflake):
    events = [event(1), event(2), event(3)]
    outcomes = snowflake_sink.process_batch(events, SF)
    assert delivered(outcomes) == [True, True, True]
    assert snowflake.connects == 1 and snowflake.closed == 1
    statement, params = snowflake.executed[0]
    assert statement == "INSERT INTO AUDIT (EVENT) SELECT PARSE_JSON(column1) FROM VALUES (%s), (%s), (%s)"
    assert [json.loads(p) for p in params] == events


def test_snowflake_single_event_statement_is_unchanged(snowflake):
    snowflake_sink.process(event(1), SF)
    assert snowflake.executed[0][0] == "INSERT INTO AUDIT (EVENT) SELECT PARSE_JSON(%s)"


def test_snowflake_failed_statement_fails_every_event_and_closes_the_connection(snowflake):
    snowflake.fail = RuntimeError("warehouse suspended")
    outcomes = snowflake_sink.process_batch([event(1), event(2)], SF)
    assert all(retryable(o) for o in outcomes)
    assert snowflake.closed == 1


def test_snowflake_missing_property_fails_the_call(snowflake):
    with pytest.raises(ValueError):
        snowflake_sink.process_batch([event(1)], {"account": "acc"})


@pytest.mark.parametrize("name", NOT_IDENTIFIERS[:-3])
@pytest.mark.parametrize("key", ["table", "column"])
def test_snowflake_refuses_a_name_that_is_not_an_identifier_before_connecting(snowflake, key, name):
    for call in (lambda p: snowflake_sink.process(event(1), p),
                 lambda p: snowflake_sink.process_batch([event(1), event(2)], p)):
        with pytest.raises(ValueError, match=f"Invalid {key} name"):
            call({**SF, key: name})
    assert snowflake.connects == 0 and snowflake.executed == []


def test_snowflake_accepts_a_qualified_table_and_a_dollar_in_names(snowflake):
    snowflake_sink.process(event(1), {**SF, "table": "AUDIT_DB.PUBLIC.EVENTS$V2", "column": "PAYLOAD"})
    assert snowflake.executed[0][0] == "INSERT INTO AUDIT_DB.PUBLIC.EVENTS$V2 (PAYLOAD) SELECT PARSE_JSON(%s)"
    with pytest.raises(ValueError, match="Invalid table name"):
        snowflake_sink.process(event(1), {**SF, "table": "A.B.C.D"})
    with pytest.raises(ValueError, match="Invalid column name"):
        snowflake_sink.process(event(1), {**SF, "column": "T.EVENT"})


def test_a_bad_name_fails_the_batch_call_so_the_events_wait_for_the_fixed_configuration(monkeypatch, snowflake):
    monkeypatch.setattr(sink.registry, "resolve", lambda sink_id: snowflake_sink.process)
    response = client.post("/sink/snowflake_sink/batch", json={
        "events": [event(1)], "properties": {**SF, "table": "AUDIT; DROP TABLE X"}})
    assert response.status_code == 500          # a 5xx is retried by the backend, not dead-lettered
    assert snowflake.connects == 0


# --- gcs_sink ---

class FakeBucket:
    def __init__(self, fail_names=()):
        self.uploads = []
        self.fail_names = fail_names

    def blob(self, name):
        bucket = self

        class Blob:
            metadata = None

            def upload_from_string(self, body, content_type=None):
                if any(part in name for part in bucket.fail_names):
                    raise RuntimeError("503 backendError")
                bucket.uploads.append({"name": name, "body": body, "content_type": content_type,
                                       "metadata": self.metadata})
        return Blob()


@pytest.fixture
def gcs(monkeypatch):
    bucket = FakeBucket()
    storage = types.SimpleNamespace(Client=lambda project=None: types.SimpleNamespace(bucket=lambda name: bucket))
    monkeypatch.setattr(gcs_sink, "storage", storage)
    return bucket


GCS = {"bucket": "b", "prefix": "audit/"}


def test_gcs_batch_is_one_jsonl_object_per_folder(gcs):
    events = [event(1), event(2, "2026-10-03T00:00:01Z"), event(3)]
    outcomes = gcs_sink.process_batch(events, GCS)
    assert delivered(outcomes) == [True, True, True]
    by_folder = {u["name"].rsplit("/", 1)[0]: u for u in gcs.uploads}
    assert set(by_folder) == {"audit/year=2026/month=10/day=02", "audit/year=2026/month=10/day=03"}
    first = by_folder["audit/year=2026/month=10/day=02"]
    assert [json.loads(line)["eventId"] for line in first["body"].decode().splitlines()] == \
        [events[0]["eventId"], events[2]["eventId"]]
    assert first["content_type"] == "application/x-ndjson" and first["metadata"] == {"event-count": "2"}
    assert outcomes[0]["object"] == first["name"]


def test_gcs_redelivering_the_same_group_overwrites_the_same_object(gcs):
    events = [event(1), event(3)]
    gcs_sink.process_batch(events, GCS)
    gcs_sink.process_batch(list(reversed(events)), GCS)
    assert gcs.uploads[0]["name"] == gcs.uploads[1]["name"]


def test_gcs_compressed_batch(gcs):
    gcs_sink.process_batch([event(1)], {**GCS, "compress": "true"})
    upload = gcs.uploads[0]
    assert upload["name"].endswith(".jsonl.gz") and upload["content_type"] == "application/gzip"
    assert json.loads(gzip.decompress(upload["body"]).decode().strip())["eventId"] == event(1)["eventId"]


def test_gcs_failed_upload_fails_only_its_group(monkeypatch):
    bucket = FakeBucket(fail_names=("day=03",))
    monkeypatch.setattr(gcs_sink, "storage", types.SimpleNamespace(
        Client=lambda project=None: types.SimpleNamespace(bucket=lambda name: bucket)))
    outcomes = gcs_sink.process_batch([event(1), event(2, "2026-10-03T00:00:01Z")], GCS)
    assert isinstance(outcomes[0], dict) and retryable(outcomes[1])


def test_gcs_missing_bucket_fails_the_call(gcs):
    with pytest.raises(ValueError):
        gcs_sink.process_batch([event(1)], {})


# --- azure_blob_sink ---

@pytest.fixture
def azure(monkeypatch):
    state = types.SimpleNamespace(uploads=[], exists_calls=0, fail_names=())

    class BlobClient:
        def __init__(self, name):
            self.name = name

        def upload_blob(self, body, overwrite=False, content_settings=None, metadata=None):
            if any(part in self.name for part in state.fail_names):
                raise RuntimeError("ServerBusy")
            state.uploads.append({"name": self.name, "body": body, "overwrite": overwrite,
                                  "content_type": content_settings.content_type, "metadata": metadata})

    class ContainerClient:
        def exists(self):
            state.exists_calls += 1
            return True

        def get_blob_client(self, name):
            return BlobClient(name)

    class Service:
        @staticmethod
        def from_connection_string(value):
            return Service()

        def get_container_client(self, name):
            return ContainerClient()

    monkeypatch.setattr(azure_blob_sink, "BlobServiceClient", Service)
    monkeypatch.setattr(azure_blob_sink, "ContentSettings",
                        lambda content_type=None: types.SimpleNamespace(content_type=content_type), raising=False)
    return state


AZ = {"container": "c", "connection-string": "UseDevelopmentStorage=true", "prefix": "audit/"}


def test_azure_batch_is_one_jsonl_blob_per_folder(azure):
    events = [event(1), event(2), event(3, "2026-10-03T00:00:01Z")]
    outcomes = azure_blob_sink.process_batch(events, AZ)
    assert delivered(outcomes) == [True, True, True]
    assert len(azure.uploads) == 2 and azure.exists_calls == 1
    first = next(u for u in azure.uploads if "day=02" in u["name"])
    assert len(first["body"].decode().splitlines()) == 2
    assert first["overwrite"] is True and first["content_type"] == "application/x-ndjson"
    assert first["metadata"] == {"event_count": "2"}


def test_azure_redelivering_the_same_group_overwrites_the_same_blob(azure):
    events = [event(1), event(2)]
    azure_blob_sink.process_batch(events, AZ)
    azure_blob_sink.process_batch(list(reversed(events)), AZ)
    assert azure.uploads[0]["name"] == azure.uploads[1]["name"]


def test_azure_failed_upload_fails_only_its_group(azure):
    azure.fail_names = ("day=03",)
    outcomes = azure_blob_sink.process_batch([event(1), event(3, "2026-10-03T00:00:01Z")], AZ)
    assert isinstance(outcomes[0], dict) and retryable(outcomes[1])


def test_azure_missing_credentials_fail_the_call(azure):
    with pytest.raises(ValueError):
        azure_blob_sink.process_batch([event(1)], {"container": "c"})


# --- opensearch_sink ---

OS = {"service-url": "http://opensearch:9200", "service-path": "/audit-logs/_doc"}


def bulk_answer(*statuses):
    items = []
    for i, status in enumerate(statuses):
        entry = {"_index": "audit-logs", "_id": f"id{i}", "status": status}
        if status >= 400:
            entry["error"] = {"type": "mapper_parsing_exception", "reason": "failed to parse field"}
        else:
            entry["result"] = "created"
        items.append({"index": entry})
    return FakeResponse(200, {"errors": any(s >= 400 for s in statuses), "items": items})


def test_opensearch_batch_is_one_bulk_request(http):
    fake = http(bulk_answer(201, 201))
    events = [event(1), event(2)]
    outcomes = opensearch_sink.process_batch(events, OS)
    assert delivered(outcomes) == [True, True]
    assert outcomes[1]["document_id"] == "id1"
    call = fake.calls[0]
    assert len(fake.calls) == 1 and call["url"] == "http://opensearch:9200/audit-logs/_bulk"
    assert call["headers"]["Content-Type"] == "application/x-ndjson"
    lines = call["data"].decode().split("\n")
    assert lines[-1] == ""                                     # the bulk body must end with a newline
    assert [json.loads(line) for line in lines[:-1]] == [{"index": {}}, events[0], {"index": {}}, events[1]]


def test_opensearch_outcomes_follow_the_status_of_each_document(http):
    http(bulk_answer(201, 400, 429, 503))
    outcomes = opensearch_sink.process_batch([event(i) for i in range(1, 5)], OS)
    assert isinstance(outcomes[0], dict)
    assert isinstance(outcomes[1], ValueError)                 # the document itself: not retried
    assert retryable(outcomes[2]) and retryable(outcomes[3])   # throttled, shard failure: retried


def test_opensearch_create_path_uses_the_create_action(http):
    fake = http(FakeResponse(200, {"items": [{"create": {"status": 201, "_id": "x"}}]}))
    outcomes = opensearch_sink.process_batch([event(1)], {**OS, "service-path": "/audit-logs/_create"})
    assert isinstance(outcomes[0], dict)
    assert json.loads(fake.calls[0]["data"].decode().split("\n")[0]) == {"create": {}}


def test_opensearch_failed_request_fails_every_event(http):
    http(FakeResponse(503, text="unavailable"))
    assert all(retryable(o) for o in opensearch_sink.process_batch([event(1), event(2)], OS))


def test_opensearch_answer_without_an_item_per_document_fails_every_event(http):
    http(FakeResponse(200, {"items": [{"index": {"status": 201}}]}))
    assert all(retryable(o) for o in opensearch_sink.process_batch([event(1), event(2)], OS))


def test_opensearch_path_without_a_bulk_form_sends_the_events_one_by_one(http):
    fake = http(FakeResponse(201, {"_id": "x", "_index": "audit-logs", "result": "created"}))
    outcomes = opensearch_sink.process_batch([event(1), event(2)],
                                             {**OS, "service-path": "/audit-logs/_doc?pipeline=geo"})
    assert delivered(outcomes) == [True, True]
    assert [c["url"] for c in fake.calls] == ["http://opensearch:9200/audit-logs/_doc?pipeline=geo"] * 2


def test_opensearch_adds_a_timestamp_only_where_it_is_missing(http):
    fake = http(bulk_answer(201, 201))
    opensearch_sink.process_batch([{"eventId": "a"}, event(2)], OS)
    lines = fake.calls[0]["data"].decode().split("\n")
    assert "timestamp" in json.loads(lines[1]) and json.loads(lines[3])["timestamp"] == event(2)["timestamp"]


# --- aws_cloudwatch_sink ---

@pytest.fixture
def cloudwatch(monkeypatch):
    state = types.SimpleNamespace(puts=[], describes=0, fail_on_call=None, rejected=None)

    class Logs:
        def describe_log_groups(self, logGroupNamePrefix):
            state.describes += 1
            return {"logGroups": [{"logGroupName": logGroupNamePrefix}]}

        def describe_log_streams(self, logGroupName, logStreamNamePrefix):
            state.describes += 1
            return {"logStreams": [{"logStreamName": logStreamNamePrefix}]}

        def put_log_events(self, logGroupName, logStreamName, logEvents):
            state.puts.append(logEvents)
            if state.fail_on_call == len(state.puts):
                raise RuntimeError("ThrottlingException")
            return {"rejectedLogEventsInfo": state.rejected} if state.rejected else {}

    monkeypatch.setattr(aws_cloudwatch_sink, "boto3", types.SimpleNamespace(client=lambda service, **kw: Logs()))
    return state


CW = {"log-group": "/auditflow/acme"}


def test_cloudwatch_batch_is_one_put_and_one_check_of_group_and_stream(cloudwatch):
    events = [event(1), event(2), event(3)]
    outcomes = aws_cloudwatch_sink.process_batch(events, CW)
    assert delivered(outcomes) == [True, True, True]
    assert len(cloudwatch.puts) == 1 and cloudwatch.describes == 2
    assert [json.loads(e["message"]) for e in cloudwatch.puts[0]] == events
    assert len({e["timestamp"] for e in cloudwatch.puts[0]}) == 1


def test_cloudwatch_batch_is_split_at_the_size_limit_and_a_failed_call_fails_only_its_events(cloudwatch, monkeypatch):
    size = len(json.dumps(event(1)).encode()) + aws_cloudwatch_sink._EVENT_OVERHEAD_BYTES
    monkeypatch.setattr(aws_cloudwatch_sink, "_MAX_BYTES_PER_CALL", 2 * size)
    cloudwatch.fail_on_call = 2
    outcomes = aws_cloudwatch_sink.process_batch([event(i) for i in range(1, 6)], CW)
    assert [len(p) for p in cloudwatch.puts] == [2, 2, 1]
    assert delivered(outcomes) == [True, True, False, False, True]
    assert retryable(outcomes[2])


def test_cloudwatch_events_it_did_not_store_are_reported(cloudwatch):
    cloudwatch.rejected = {"tooOldLogEventEndIndex": 1}
    outcomes = aws_cloudwatch_sink.process_batch([event(1), event(2)], CW)
    assert isinstance(outcomes[0], ValueError) and isinstance(outcomes[1], dict)


def test_cloudwatch_missing_log_group_fails_the_call(cloudwatch):
    with pytest.raises(ValueError):
        aws_cloudwatch_sink.process_batch([event(1)], {})


# --- datadog_sink ---

DD = {"api-key": "key"}


def test_datadog_batch_is_one_request_with_an_entry_per_event(http):
    fake = http(FakeResponse(202))
    events = [event(1), event(2)]
    outcomes = datadog_sink.process_batch(events, DD)
    assert delivered(outcomes) == [True, True]
    assert len(fake.calls) == 1
    assert [json.loads(entry["message"]) for entry in fake.calls[0]["json"]] == events


def test_datadog_batch_is_split_at_the_entry_limit_and_a_refused_request_fails_only_its_events(http, monkeypatch):
    monkeypatch.setattr(datadog_sink, "_MAX_ENTRIES", 2)
    fake = http(FakeResponse(202), FakeResponse(429), FakeResponse(202))
    outcomes = datadog_sink.process_batch([event(i) for i in range(1, 6)], DD)
    assert [len(c["json"]) for c in fake.calls] == [2, 2, 1]
    assert delivered(outcomes) == [True, True, False, False, True]
    assert retryable(outcomes[2])


def test_datadog_missing_key_fails_the_call():
    with pytest.raises(ValueError):
        datadog_sink.process_batch([event(1)], {})


# --- splunk_sink ---

SP = {"hec-url": "https://splunk:8088/services/collector", "token": "t", "index": "audit"}


def test_splunk_batch_is_one_request_with_the_events_one_after_another(http):
    fake = http(FakeResponse(200))
    events = [event(1), event(2)]
    outcomes = splunk_sink.process_batch(events, SP)
    assert delivered(outcomes) == [True, True]
    assert len(fake.calls) == 1
    payloads = [json.loads(line) for line in fake.calls[0]["data"].decode().split("\n")]
    assert [p["event"] for p in payloads] == events
    assert all(p["index"] == "audit" and p["sourcetype"] == "_json" for p in payloads)


def test_splunk_single_event_request_is_unchanged(http):
    fake = http(FakeResponse(200))
    splunk_sink.process(event(1), SP)
    assert fake.calls[0]["json"] == {"event": event(1), "sourcetype": "_json", "source": "auditflow", "index": "audit"}


def test_splunk_unreadable_event_fails_alone(http):
    def answer(kwargs):
        body = kwargs.get("data") or json.dumps(kwargs.get("json")).encode()
        return FakeResponse(400, text='{"text":"Invalid data format","code":6}') if b"0003" in body else FakeResponse(200)
    fake = http(answer)
    outcomes = splunk_sink.process_batch([event(1), event(3), event(2)], SP)
    assert delivered(outcomes) == [True, False, True]
    assert len(fake.calls) == 4


def test_splunk_unavailable_fails_the_whole_batch_without_resending(http):
    fake = http(FakeResponse(503))
    outcomes = splunk_sink.process_batch([event(1), event(2)], SP)
    assert all(retryable(o) for o in outcomes) and len(fake.calls) == 1


# --- loki_sink ---

LOKI = {"service-url": "http://loki:3100"}


def loki_event(ts, line, status="SUCCESS"):
    return {"streams": [{"stream": {"job": "auditflow", "action_status": status}, "values": [[ts, line]]}]}


def test_loki_batch_is_one_push_with_equal_label_sets_merged_and_ordered(http):
    fake = http(FakeResponse(204))
    outcomes = loki_sink.process_batch(
        [loki_event("300", "c"), loki_event("100", "a"), loki_event("200", "b", "FAILURE")], LOKI)
    assert delivered(outcomes) == [True, True, True]
    assert len(fake.calls) == 1 and fake.calls[0]["url"] == "http://loki:3100/loki/api/v1/push"
    streams = {s["stream"]["action_status"]: s["values"] for s in fake.calls[0]["json"]["streams"]}
    assert streams == {"SUCCESS": [["100", "a"], ["300", "c"]], "FAILURE": [["200", "b"]]}


def test_loki_events_not_shaped_by_a_transformer_are_wrapped(http):
    fake = http(FakeResponse(204))
    loki_sink.process_batch([event(1), event(2)], LOKI)
    streams = fake.calls[0]["json"]["streams"]
    assert len(streams) == 1 and len(streams[0]["values"]) == 2
    assert streams[0]["stream"] == {"job": "auditflow", "event_type": "api.call", "source_system": "core"}


def test_loki_refused_entry_fails_alone(http):
    def answer(kwargs):
        return FakeResponse(400, text="entry too far behind") if "old" in json.dumps(kwargs["json"]) \
            else FakeResponse(204)
    fake = http(answer)
    outcomes = loki_sink.process_batch([loki_event("300", "c"), loki_event("1", "old"), loki_event("200", "b")], LOKI)
    assert delivered(outcomes) == [True, False, True]
    assert len(fake.calls) == 4


def test_loki_unavailable_fails_the_whole_batch_without_resending(http):
    fake = http(FakeResponse(503))
    outcomes = loki_sink.process_batch([loki_event("1", "a"), loki_event("2", "b")], LOKI)
    assert all(retryable(o) for o in outcomes) and len(fake.calls) == 1


def test_loki_tenant_header_is_sent(http):
    fake = http(FakeResponse(204))
    loki_sink.process_batch([loki_event("1", "a")], {**LOKI, "tenant-id": "acme"})
    assert fake.calls[0]["headers"]["X-Scope-OrgID"] == "acme"
