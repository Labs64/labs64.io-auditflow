"""The shared `extra` promotion vocabulary and its deployment-level override.

`resolve_promoted` turns operator-supplied configuration into field/column names, so its rejection
behaviour matters as much as its merge behaviour: a malformed mapping must fail loudly at import
rather than silently drop a reporting dimension.
"""
import pytest

from auditflow_sdk import WELL_KNOWN_EXTRA, resolve_promoted, stringify_value

ENV_GLOBAL = "AUDITFLOW_PROMOTED_KEYS"


def test_well_known_vocabulary_is_the_documented_seven_keys():
    assert set(WELL_KNOWN_EXTRA) == {
        "actionName", "actionStatus", "actionMessage", "userId",
        "sessionId", "durationMs", "responseStatus",
    }
    # Values are the snake_case field/column names the bundled transformers use.
    assert WELL_KNOWN_EXTRA["actionName"] == "action_name"
    assert WELL_KNOWN_EXTRA["responseStatus"] == "response_status"


def test_base_is_returned_unchanged_when_no_env_is_set(monkeypatch):
    monkeypatch.delenv(ENV_GLOBAL, raising=False)
    base = {"actionName": "action_name"}
    assert resolve_promoted(base) == {"actionName": "action_name"}


def test_resolve_does_not_mutate_the_caller_s_base(monkeypatch):
    monkeypatch.setenv(ENV_GLOBAL, '{"invoiceRef": "invoice_ref"}')
    base = {"actionName": "action_name"}
    resolve_promoted(base)
    assert base == {"actionName": "action_name"}


def test_global_env_adds_keys(monkeypatch):
    monkeypatch.setenv(ENV_GLOBAL, '{"invoiceRef": "invoice_ref", "mrrDelta": "mrr_delta"}')
    resolved = resolve_promoted({"actionName": "action_name"})
    assert resolved == {
        "actionName": "action_name",
        "invoiceRef": "invoice_ref",
        "mrrDelta": "mrr_delta",
    }


def test_global_env_overrides_the_base_mapping(monkeypatch):
    # An operator retargeting a column must win over the module's built-in choice.
    monkeypatch.setenv(ENV_GLOBAL, '{"actionName": "op_name"}')
    assert resolve_promoted({"actionName": "action_name"}) == {"actionName": "op_name"}


def test_module_scoped_env_overrides_the_global_env(monkeypatch):
    monkeypatch.setenv(ENV_GLOBAL, '{"invoiceRef": "global_name"}')
    monkeypatch.setenv(f"{ENV_GLOBAL}_AUDIT_CLICKHOUSE", '{"invoiceRef": "module_name"}')
    resolved = resolve_promoted({}, module_id="audit_clickhouse")
    assert resolved == {"invoiceRef": "module_name"}


def test_module_scoped_env_is_ignored_without_a_module_id(monkeypatch):
    monkeypatch.delenv(ENV_GLOBAL, raising=False)
    monkeypatch.setenv(f"{ENV_GLOBAL}_AUDIT_CLICKHOUSE", '{"invoiceRef": "invoice_ref"}')
    assert resolve_promoted({}) == {}


def test_blank_env_is_treated_as_unset(monkeypatch):
    monkeypatch.setenv(ENV_GLOBAL, "   ")
    assert resolve_promoted({"userId": "user_id"}) == {"userId": "user_id"}


def test_malformed_json_raises(monkeypatch):
    monkeypatch.setenv(ENV_GLOBAL, "{not json")
    with pytest.raises(ValueError, match="not valid JSON"):
        resolve_promoted({})


def test_json_that_is_not_an_object_raises(monkeypatch):
    monkeypatch.setenv(ENV_GLOBAL, '["invoiceRef"]')
    with pytest.raises(ValueError, match="must be a JSON object"):
        resolve_promoted({})


@pytest.mark.parametrize("target", ["invoice ref", "2invoice", "invoice-ref", "", "drop table"])
def test_invalid_target_field_name_raises(monkeypatch, target):
    # These names reach SQL identifier position in the ClickHouse insert path.
    monkeypatch.setenv(ENV_GLOBAL, '{"invoiceRef": "%s"}' % target)
    with pytest.raises(ValueError, match="target field name"):
        resolve_promoted({})


def test_non_string_target_raises(monkeypatch):
    monkeypatch.setenv(ENV_GLOBAL, '{"invoiceRef": 7}')
    with pytest.raises(ValueError, match="target field name"):
        resolve_promoted({})


@pytest.mark.parametrize("value,expected", [
    ("text", "text"),
    (True, "true"),
    (False, "false"),
    (7, "7"),
    (1.5, "1.5"),
    (None, "null"),
    ({"b": 1, "a": 2}, '{"a":2,"b":1}'),
    ([1, "x"], '[1,"x"]'),
])
def test_stringify_value(value, expected):
    assert stringify_value(value) == expected
