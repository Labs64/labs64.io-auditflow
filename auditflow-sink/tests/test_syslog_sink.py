"""CEF formatting must survive an arbitrary `extra` block.

`extra` is an open map, so its keys and values are publisher input. Two consequences: CEF must not
invent values for keys the event never carried, and it must escape the CEF metacharacters, or a
value containing `=` or a newline silently corrupts the record (or forges a second one).
"""
import pytest

from sinks import syslog_sink

EVENT = {
    "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
    "eventType": "api.call",
    "sourceSystem": "netlicensing/core",
    "extra": {"actionName": "login", "actionStatus": "SUCCESS"},
}


def header(message):
    """CEF header fields 0-6, splitting only on unescaped pipes."""
    fields, current, escaped = [], "", False
    for char in message:
        if escaped:
            current, escaped = current + char, False
        elif char == "\\":
            current, escaped = current + char, True
        elif char == "|" and len(fields) < 7:
            fields.append(current)
            current = ""
        else:
            current += char
    fields.append(current)
    return fields


def test_full_event_produces_the_expected_header_and_extensions():
    message = syslog_sink.format_cef(EVENT)
    fields = header(message)
    assert fields[0] == "CEF:0"
    assert fields[1] == "Labs64"
    assert fields[2] == "AuditFlow"
    assert fields[4] == "api.call"          # signature id
    assert fields[5] == "login"             # name
    extension = fields[7]
    assert "act=login" in extension
    assert "outcome=SUCCESS" in extension
    assert "src=netlicensing/core" in extension
    assert "externalId=fedcba98-7654-3210-fedc-ba9876543210" in extension


def test_absent_action_keys_are_omitted_not_defaulted():
    message = syslog_sink.format_cef({"eventType": "api.call", "extra": {}})
    assert "unknown" not in message
    assert "act=" not in message
    assert "outcome=" not in message


def test_name_falls_back_to_event_type_when_action_name_is_absent():
    message = syslog_sink.format_cef({"eventType": "api.call", "extra": {}})
    assert header(message)[5] == "api.call"


def test_custom_extra_keys_reach_the_extension():
    event = dict(EVENT, extra={**EVENT["extra"], "invoiceRef": "INV-1", "retry": 2})
    extension = header(syslog_sink.format_cef(event))[7]
    assert "invoiceRef=INV-1" in extension
    assert "retry=2" in extension


def test_extension_values_escape_equals_backslash_and_newline():
    event = {"eventType": "api.call",
             "extra": {"note": "a=b", "path": r"C:\tmp", "multi": "line1\nline2"}}
    extension = header(syslog_sink.format_cef(event))[7]
    assert r"note=a\=b" in extension
    assert r"path=C:\\tmp" in extension
    assert r"multi=line1\nline2" in extension
    # A raw newline would let a value forge a second syslog record.
    assert "\n" not in extension


def test_header_fields_escape_pipe_and_backslash():
    event = {"eventType": "api|call", "extra": {"actionName": r"log\in"}}
    fields = header(syslog_sink.format_cef(event))
    assert fields[4] == r"api\|call"
    assert fields[5] == r"log\\in"


def test_header_fields_do_not_escape_equals():
    # `=` is only a delimiter in the extension, and escaping it in the header corrupts the name.
    fields = header(syslog_sink.format_cef({"eventType": "a=b", "extra": {}}))
    assert fields[4] == "a=b"


def test_non_scalar_extension_values_are_json_encoded():
    event = {"eventType": "api.call", "extra": {"nested": {"b": 1, "a": 2}, "flag": True}}
    extension = header(syslog_sink.format_cef(event))[7]
    # JSON quotes are not CEF metacharacters, so they are not escaped — only \, = and newlines are.
    assert 'nested={"a":2,"b":1}' in extension
    assert "flag=true" in extension


def test_event_with_no_extra_at_all_still_formats():
    message = syslog_sink.format_cef({"eventType": "api.call"})
    assert message.startswith("CEF:0|Labs64|AuditFlow|")
    assert "unknown" not in message
