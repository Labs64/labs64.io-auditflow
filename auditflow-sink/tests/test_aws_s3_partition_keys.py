"""aws_s3_sink `partition-format`: {field} placeholders put event fields into the key."""
import pytest

from sinks import aws_s3_sink

TIMESTAMP = "2026-10-02T14:26:39.181Z"
EVENT_ID = "b1c694a0-972c-42bb-b1bc-3e098ecc5fe9"
FORMAT = "vendor_id={extra.vendor_id}/year=%Y/month=%m/day=%d/actionName={extra.actionName}/"


def key(extra=None, fmt=FORMAT, **top):
    event = {"eventId": EVENT_ID, "tenantId": "netlicensing", "timestamp": TIMESTAMP,
             "extra": extra if extra is not None else {}, **top}
    return aws_s3_sink.build_object_key("tenants/", True, fmt, "jsonl", False, event)


def test_placeholders_and_date_follow_the_tenant_in_the_given_order():
    assert key({"vendor_id": "VDEMO", "actionName": "product_create"}) == (
        f"tenants/tenant=netlicensing/vendor_id=VDEMO/year=2026/month=10/day=02/"
        f"actionName=product_create/20261002-142639-{EVENT_ID}.json")


def test_slash_in_a_value_cannot_add_a_path_level():
    assert "/actionName=product_create/" in key({"vendor_id": "V", "actionName": "product/create"})


@pytest.mark.parametrize("value", ["../../x", "..", ".", "a/../b", "tenant=other/"])
def test_traversal_and_separators_are_neutralised(value):
    segments = key({"vendor_id": value, "actionName": "a"}).split("/")
    assert ".." not in segments and "." not in segments
    assert len([s for s in segments if s.startswith("vendor_id=")]) == 1
    assert len(segments) == 8  # tenants, tenant, vendor_id, year, month, day, actionName, file


@pytest.mark.parametrize("extra", [{}, {"vendor_id": None}, {"vendor_id": ""}, {"vendor_id": {"a": 1}}])
def test_missing_or_unusable_value_is_unknown(extra):
    assert "/vendor_id=unknown/" in key(extra)


def test_value_cannot_inject_a_date_directive():
    assert "/vendor_id=_Y/" in key({"vendor_id": "%Y"})


def test_promoted_top_level_field_is_found():
    assert "/actionName=login/" in key({"vendor_id": "V"}, actionName="login")


def test_long_value_is_truncated():
    assert f"/vendor_id={'x' * 128}/" in key({"vendor_id": "x" * 500})


def test_default_format_is_unchanged():
    assert key({"vendor_id": "V"}, fmt="year=%Y/month=%m/day=%d/") == (
        f"tenants/tenant=netlicensing/year=2026/month=10/day=02/20261002-142639-{EVENT_ID}.json")


def test_top_level_field_placeholder_after_the_day():
    fmt = "vendor_id={extra.vendor_id}/year=%Y/month=%m/day=%d/eventType={eventType}/actionName={extra.actionName}/"
    assert key({"vendor_id": "VDEMO", "actionName": "product/create"}, fmt=fmt,
               eventType="netlicensing.request.completed") == (
        "tenants/tenant=netlicensing/vendor_id=VDEMO/year=2026/month=10/day=02/"
        "eventType=netlicensing.request.completed/actionName=product_create/"
        f"20261002-142639-{EVENT_ID}.json")
