"""The two times of an event in the transformers.

EVENT TIME (``eventTime``: when the action happened, the publisher's claim) is the time every report is
grouped on and every log entry is placed at. RECEIPT TIME (``timestamp``: when AuditFlow received the event,
its own clock) is passed through under its own name, never presented as the event time. A transformer reads
them through ``auditflow_sdk.event_time`` / ``received_at`` and invents no third time.
"""
import re
from datetime import datetime, timezone
from pathlib import Path

import pytest

import auditflow_sdk
from transformers import audit_clickhouse, audit_loki, audit_opensearch

# A backfill: it happened on 1 September, AuditFlow received it on 2 October.
BACKFILL = {"eventId": "b1c694a0-972c-42bb-b1bc-3e098ecc5fe9", "tenantId": "acme", "eventType": "api.call",
            "sourceSystem": "core", "timestamp": "2026-10-02T14:26:39Z", "eventTime": "2026-09-01T08:00:00Z"}
NO_EVENT_TIME = {k: v for k, v in BACKFILL.items() if k != "eventTime"}


def test_sdk_event_time_is_the_event_time_and_receipt_time_only_when_it_is_missing():
    assert auditflow_sdk.event_time(BACKFILL) == "2026-09-01T08:00:00Z"
    assert auditflow_sdk.event_time(NO_EVENT_TIME) == "2026-10-02T14:26:39Z"
    assert auditflow_sdk.event_time({}) is None
    assert auditflow_sdk.received_at(BACKFILL) == "2026-10-02T14:26:39Z"


@pytest.mark.parametrize("transform", [audit_clickhouse.transform, audit_opensearch.transform])
def test_documents_carry_both_times_under_their_own_names(transform):
    document = transform(BACKFILL)
    assert document["event_time"] == "2026-09-01T08:00:00Z"
    assert document["timestamp"] == "2026-10-02T14:26:39Z"


@pytest.mark.parametrize("transform", [audit_clickhouse.transform, audit_opensearch.transform])
def test_an_event_queued_before_ingest_set_eventTime_still_gets_an_event_time(transform):
    document = transform(NO_EVENT_TIME)
    assert document["event_time"] == "2026-10-02T14:26:39Z"
    assert document["timestamp"] == "2026-10-02T14:26:39Z"


def test_loki_places_the_line_at_the_event_time():
    nanos = int(audit_loki.transform(BACKFILL)["streams"][0]["values"][0][0])
    assert nanos == int(datetime(2026, 9, 1, 8, 0, tzinfo=timezone.utc).timestamp() * 1_000_000_000)


def test_loki_falls_back_to_the_receipt_time_only_when_there_is_no_event_time():
    nanos = int(audit_loki.transform(NO_EVENT_TIME)["streams"][0]["values"][0][0])
    assert nanos == int(datetime(2026, 10, 2, 14, 26, 39, tzinfo=timezone.utc).timestamp() * 1_000_000_000)


# --- the rule itself ---

TRANSFORMERS = sorted((Path(__file__).resolve().parents[1] / "transformers").glob("*.py"))
CLOCK_CALL = re.compile(r"datetime\.now\(|datetime\.utcnow\(|time\.time\(|time\.time_ns\(")
TIME_KEY = re.compile(r"""(get\(\s*['"](timestamp|eventTime)['"]|\[\s*['"](timestamp|eventTime)['"]\s*\])""")


@pytest.mark.parametrize("module", TRANSFORMERS, ids=lambda p: p.name)
def test_no_transformer_reads_the_time_keys_directly(module):
    assert not TIME_KEY.findall(module.read_text()), (
        f"{module.name} reads 'timestamp' or 'eventTime' itself: use auditflow_sdk.event_time (when the "
        f"action happened) or received_at (when AuditFlow received it)")


@pytest.mark.parametrize("module", TRANSFORMERS, ids=lambda p: p.name)
def test_no_transformer_invents_a_third_time_except_lokis_last_resort(module):
    # audit_loki: Loki rejects a zero timestamp, so an event with no time at all (which never leaves
    # AuditFlow's ingest) is placed now. Every other transformer carries the event's own times only.
    if module.name == "audit_loki.py":
        pytest.skip("documented last resort")
    assert not CLOCK_CALL.findall(module.read_text()), f"{module.name} reads the clock"
