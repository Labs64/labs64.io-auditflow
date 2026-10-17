"""The two times of an event, and which one each sink uses.

An event has an EVENT TIME (``eventTime``: when the action happened, the publisher's claim) and a RECEIPT
TIME (``timestamp``: when AuditFlow received it, AuditFlow's own clock). A sink uses one of them for each job
and never reads the keys itself (``auditflow_sdk.event_time`` / ``received_at``), never invents a third time
(the moment of delivery), and says which one it used. See the module docstring of ``auditflow_sdk``.
"""
import io
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

import auditflow_sdk
from sinks import aws_cloudwatch_sink, aws_s3_sink, azure_blob_sink, gcs_sink, loki_sink, syslog_sink

# A backfilled event: it happened on 1 September and AuditFlow received it on 2 October.
RECEIPT = "2026-10-02T14:26:39.181Z"
EVENT_TIME = "2026-09-01T08:00:00Z"
EVENT_ID = "b1c694a0-972c-42bb-b1bc-3e098ecc5fe9"
BACKFILL = {"eventId": EVENT_ID, "tenantId": "acme", "timestamp": RECEIPT, "eventTime": EVENT_TIME,
            "eventType": "api.call", "sourceSystem": "core"}


# --- archives: laid out by RECEIPT time, never by a publisher's claim ---

def test_s3_key_uses_the_receipt_day_not_the_event_day():
    key = aws_s3_sink.build_object_key("tenants/", True, "year=%Y/month=%m/day=%d/", "jsonl", False, BACKFILL)
    assert key == f"tenants/tenant=acme/year=2026/month=10/day=02/20261002-142639-{EVENT_ID}.json"
    assert "2026/month=09" not in key


def test_gcs_and_azure_names_use_the_receipt_day_not_the_event_day():
    gcs = gcs_sink.build_object_name("p/", True, "year=%Y/month=%m/day=%d/", False, BACKFILL)
    azure = azure_blob_sink.build_blob_name("p/", True, "year=%Y/month=%m/day=%d/", False, BACKFILL)
    for name in (gcs, azure):
        assert "year=2026/month=10/day=02/" in name and name.endswith(f"20261002-142639-{EVENT_ID}.json")


def test_a_redelivery_of_the_same_event_gets_the_same_key():
    again = {**BACKFILL}
    first = aws_s3_sink.build_object_key("t/", True, "year=%Y/", "jsonl", False, BACKFILL)
    assert first == aws_s3_sink.build_object_key("t/", True, "year=%Y/", "jsonl", False, again)


def test_s3_object_metadata_carries_both_times_under_their_own_names(monkeypatch):
    puts = []

    class S3:
        def put_object(self, **kwargs):
            puts.append(kwargs)
            return {"ETag": '"x"'}

    monkeypatch.setattr(aws_s3_sink, "_get_s3_client", lambda *a: S3())
    aws_s3_sink.process(BACKFILL, {"bucket": "b", "prefix": "tenants/"})

    metadata = puts[0]["Metadata"]
    assert metadata["received-at"] == "2026-10-02T14:26:39.181000+00:00"
    assert metadata["event-time"] == "2026-09-01T08:00:00+00:00"
    assert "timestamp" not in metadata


# --- log destinations: an entry sits at the EVENT time, unless the destination cannot take it ---

def test_loki_places_a_line_at_the_event_time_not_at_delivery():
    payload = loki_sink._to_loki(BACKFILL)
    nanos = int(payload["streams"][0]["values"][0][0])
    assert nanos == int(datetime(2026, 9, 1, 8, 0, tzinfo=timezone.utc).timestamp() * 1_000_000_000)


def test_syslog_header_carries_the_event_time(monkeypatch):
    sent = []
    monkeypatch.setattr(syslog_sink, "send_udp", lambda host, port, message: sent.append(message))
    syslog_sink.process(BACKFILL, {"host": "syslog.example", "format": "json"})
    assert re.match(r"<\d+>Sep 01 08:00:00 ", sent[0]), sent[0]


def test_cloudwatch_places_entries_at_the_receipt_time_of_the_newest_event():
    # CloudWatch refuses entries older than 14 days and unordered calls, so it cannot take an old eventTime.
    now = datetime.now(timezone.utc)
    older = {**BACKFILL, "timestamp": (now - timedelta(minutes=5)).isoformat()}
    newer = {**BACKFILL, "timestamp": (now - timedelta(minutes=1)).isoformat()}
    millis = aws_cloudwatch_sink._entry_millis([older, newer])
    assert millis == int((now - timedelta(minutes=1)).timestamp() * 1000)


def test_cloudwatch_never_asks_for_an_entry_older_than_it_accepts():
    ancient = {**BACKFILL, "timestamp": "2026-01-01T00:00:00Z"}
    millis = aws_cloudwatch_sink._entry_millis([ancient])
    oldest_accepted = datetime.now(timezone.utc) - timedelta(days=14)
    assert millis > oldest_accepted.timestamp() * 1000


# --- the rule itself: sinks go through the SDK and invent no third time ---

SINKS = sorted((Path(__file__).resolve().parents[1] / "sinks").glob("*.py"))

# Reading the clock to stamp an entry is the delivery time. The only legitimate use: CloudWatch bounding an
# entry to the 14 days it accepts (aws_cloudwatch_sink._entry_millis).
CLOCK_CALL = re.compile(r"datetime\.now\(|datetime\.utcnow\(|time\.time\(|time\.time_ns\(")
CLOCK_ALLOWED = {"aws_cloudwatch_sink.py"}

# A sink reads neither time key directly: auditflow_sdk.event_time / received_at / with_receipt_time are the
# one place that knows what they mean.
TIME_KEY = re.compile(r"""(get\(\s*['"](timestamp|eventTime)['"]|\[\s*['"](timestamp|eventTime)['"]\s*\])""")


@pytest.mark.parametrize("sink_file", SINKS, ids=lambda p: p.name)
def test_no_sink_stamps_a_third_time(sink_file):
    if sink_file.name in CLOCK_ALLOWED or sink_file.name.startswith("__"):
        pytest.skip("reads the clock for a documented reason")
    assert not CLOCK_CALL.findall(sink_file.read_text()), (
        f"{sink_file.name} reads the clock: an entry's time is the event time or the receipt time "
        f"(auditflow_sdk.event_time / received_at), never the moment of delivery")


@pytest.mark.parametrize("sink_file", SINKS, ids=lambda p: p.name)
def test_no_sink_reads_the_time_keys_directly(sink_file):
    assert not TIME_KEY.findall(sink_file.read_text()), (
        f"{sink_file.name} reads 'timestamp' or 'eventTime' itself: use auditflow_sdk.event_time (when the "
        f"action happened) or received_at (when AuditFlow received it)")


def test_the_old_ambiguous_helper_is_gone():
    assert not hasattr(auditflow_sdk, "event_datetime")
