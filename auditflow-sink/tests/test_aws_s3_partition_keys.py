"""aws_s3_sink `partition-format`: {field} placeholders put event fields into the key."""
import pytest

from sinks import aws_s3_sink

TIMESTAMP = "2026-10-02T14:26:39.181Z"
EVENT_ID = "b1c694a0-972c-42bb-b1bc-3e098ecc5fe9"
FORMAT = "vendor_number={extra.vendorNumber}/year=%Y/month=%m/day=%d/actionName={extra.actionName}/"


def key(extra=None, fmt=FORMAT, **top):
    event = {"eventId": EVENT_ID, "tenantId": "netlicensing", "timestamp": TIMESTAMP,
             "extra": extra if extra is not None else {}, **top}
    return aws_s3_sink.build_object_key("tenants/", True, fmt, "jsonl", False, event)


def test_placeholders_and_date_follow_the_tenant_in_the_given_order():
    assert key({"vendorNumber": "VDEMO", "actionName": "product_create"}) == (
        f"tenants/tenant=netlicensing/vendor_number=VDEMO/year=2026/month=10/day=02/"
        f"actionName=product_create/20261002-142639-{EVENT_ID}.json")


def test_slash_in_a_value_cannot_add_a_path_level():
    assert "/actionName=product_create/" in key({"vendorNumber": "V", "actionName": "product/create"})


@pytest.mark.parametrize("value", ["../../x", "..", ".", "a/../b", "tenant=other/"])
def test_traversal_and_separators_are_neutralised(value):
    segments = key({"vendorNumber": value, "actionName": "a"}).split("/")
    assert ".." not in segments and "." not in segments
    assert len([s for s in segments if s.startswith("vendor_number=")]) == 1
    assert len(segments) == 8  # tenants, tenant, vendor_number, year, month, day, actionName, file


@pytest.mark.parametrize("extra", [{}, {"vendorNumber": None}, {"vendorNumber": ""}, {"vendorNumber": {"a": 1}}])
def test_missing_or_unusable_value_is_unknown(extra):
    assert "/vendor_number=unknown/" in key(extra)


def test_value_cannot_inject_a_date_directive():
    assert "/vendor_number=_Y/" in key({"vendorNumber": "%Y"})


def test_promoted_top_level_field_is_found():
    assert "/actionName=login/" in key({"vendorNumber": "V"}, actionName="login")


def test_long_value_is_truncated():
    assert f"/vendor_number={'x' * 128}/" in key({"vendorNumber": "x" * 500})


def test_default_format_is_unchanged():
    assert key({"vendorNumber": "V"}, fmt="year=%Y/month=%m/day=%d/") == (
        f"tenants/tenant=netlicensing/year=2026/month=10/day=02/20261002-142639-{EVENT_ID}.json")


def test_top_level_field_placeholder_after_the_day():
    fmt = "vendor_number={extra.vendorNumber}/year=%Y/month=%m/day=%d/eventType={eventType}/actionName={extra.actionName}/"
    assert key({"vendorNumber": "VDEMO", "actionName": "product/create"}, fmt=fmt,
               eventType="netlicensing.request.completed") == (
        "tenants/tenant=netlicensing/vendor_number=VDEMO/year=2026/month=10/day=02/"
        "eventType=netlicensing.request.completed/actionName=product_create/"
        f"20261002-142639-{EVENT_ID}.json")


# --- placeholder options: default and allowed values (a key segment a lifecycle rule matches) ---

CLASSES = "retention={extra.payloadRetentionDays|default=30|allowed=7,14,30,90,365}/year=%Y/"


@pytest.mark.parametrize("value, segment", [(7, "7"), ("365", "365"), (30, "30")])
def test_an_allowed_value_is_used(value, segment):
    assert f"/retention={segment}/" in key({"payloadRetentionDays": value}, fmt=CLASSES)


@pytest.mark.parametrize("extra", [{}, {"payloadRetentionDays": None}, {"payloadRetentionDays": 45},
                                   {"payloadRetentionDays": "../x"}, {"payloadRetentionDays": {"a": 1}}])
def test_a_missing_or_not_allowed_value_gets_the_default_and_is_counted(extra, monkeypatch, caplog):
    seen = []
    monkeypatch.setattr(aws_s3_sink, "_TELEMETRY", type("T", (), {
        "partition_value_defaulted": lambda self, sink_id, field: seen.append(field)})())
    assert "/retention=30/" in key(extra, fmt=CLASSES)
    assert seen == ["extra.payloadRetentionDays"]
    assert "using the default" in caplog.text
    assert "../x" not in caplog.text  # the value is never logged


def test_a_default_without_allowed_only_fills_a_missing_value():
    fmt = "retention={extra.payloadRetentionDays|default=30}/"
    assert "/retention=30/" in key({}, fmt=fmt)
    assert "/retention=45/" in key({"payloadRetentionDays": 45}, fmt=fmt)


@pytest.mark.parametrize("fmt", [
    "r={extra.x|allowed=1,2}/",               # allowed without a default
    "r={extra.x|default=3|allowed=1,2}/",     # default not allowed
    "r={extra.x|default=a/b}/",               # default not key-safe
    "r={extra.x|fallback=1}/",                # unknown option
    "r={extra.x|default=1|default=2}/",       # repeated option
])
def test_a_wrong_option_fails_the_write_instead_of_writing_an_unexpected_key(fmt):
    with pytest.raises(ValueError):
        key({"x": "1"}, fmt=fmt)
