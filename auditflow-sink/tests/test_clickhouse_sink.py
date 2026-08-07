import json

import pytest
import requests
from unittest.mock import MagicMock, patch

from sinks import clickhouse_sink

BASE_PROPERTIES = {
    "service-url": "http://clickhouse:8123",
    "database": "audit",
    "table": "audit_events",
}

ROW = {"event_id": "fedcba98-7654-3210-fedc-ba9876543210", "tenant_id": "t_mock",
       "event_type": "audit.test", "extra": {"sessionId": "sess456"}}


def _ok_response():
    response = MagicMock()
    response.status_code = 200
    response.raise_for_status.return_value = None
    return response


def test_missing_required_properties():
    with pytest.raises(ValueError, match="Missing required properties: service-url, table"):
        clickhouse_sink.process(ROW, {})


@patch("requests.post")
def test_query_uses_jsoneachrow_and_async_insert_settings(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(ROW, BASE_PROPERTIES)

    params = mock_post.call_args.kwargs["params"]
    assert params["query"] == "INSERT INTO audit.audit_events FORMAT JSONEachRow"
    assert params["async_insert"] == "1"
    assert params["wait_for_async_insert"] == "1"
    # ClickHouse aliases this to async_insert_busy_timeout_max_ms; 200 matches the server default.
    assert params["async_insert_busy_timeout_ms"] == "200"
    # Adaptive by default, matching ClickHouse: it collapses toward the 50ms min when deliveries
    # are sparse, so the window never becomes pure latency for un-batchable traffic.
    assert params["async_insert_use_adaptive_busy_timeout"] == "1"
    # Only sent when the operator asks for it.
    assert "async_insert_busy_timeout_min_ms" not in params
    # AuditFlow emits ISO-8601 with a trailing Z; DateTime64 only reads it with best_effort.
    assert params["date_time_input_format"] == "best_effort"
    assert params["input_format_skip_unknown_fields"] == "1"


@patch("requests.post")
def test_defaults_to_the_default_database(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(ROW, {"service-url": "http://clickhouse:8123", "table": "events"})

    assert mock_post.call_args.kwargs["params"]["query"] == \
        "INSERT INTO default.events FORMAT JSONEachRow"


@patch("requests.post")
def test_body_is_a_single_jsoneachrow_line(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(ROW, BASE_PROPERTIES)

    body = mock_post.call_args.kwargs["data"].decode("utf-8")
    assert "\n" not in body
    assert json.loads(body) == ROW


@patch("requests.post")
def test_async_insert_disabled_omits_the_settings(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(ROW, {**BASE_PROPERTIES, "async-insert": "false"})

    params = mock_post.call_args.kwargs["params"]
    assert "async_insert" not in params
    assert "wait_for_async_insert" not in params
    assert "async_insert_busy_timeout_ms" not in params
    assert "async_insert_use_adaptive_busy_timeout" not in params
    assert "async_insert_busy_timeout_min_ms" not in params


@patch("requests.post")
def test_wait_disabled_still_uses_async_insert(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(ROW, {**BASE_PROPERTIES, "wait-for-async-insert": "false"})

    params = mock_post.call_args.kwargs["params"]
    assert params["async_insert"] == "1"
    assert params["wait_for_async_insert"] == "0"


@patch("requests.post")
def test_custom_busy_timeout_is_forwarded(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(ROW, {**BASE_PROPERTIES, "async-insert-busy-timeout-ms": "250"})

    assert mock_post.call_args.kwargs["params"]["async_insert_busy_timeout_ms"] == "250"


@patch("requests.post")
def test_adaptive_busy_timeout_can_be_pinned_off(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(
        ROW, {**BASE_PROPERTIES, "async-insert-use-adaptive-busy-timeout": "false"})

    assert mock_post.call_args.kwargs["params"]["async_insert_use_adaptive_busy_timeout"] == "0"


@patch("requests.post")
def test_busy_timeout_min_is_forwarded_only_when_set(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(
        ROW, {**BASE_PROPERTIES, "async-insert-busy-timeout-min-ms": "150"})

    params = mock_post.call_args.kwargs["params"]
    assert params["async_insert_busy_timeout_min_ms"] == "150"
    # The alias only ever sets the max, so the pair has to be configurable independently.
    assert params["async_insert_busy_timeout_ms"] == "200"


@patch("requests.post")
def test_basic_auth_uses_the_resolved_password(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(
        ROW, {**BASE_PROPERTIES, "username": "auditflow", "password": "s3cret"})

    assert mock_post.call_args.kwargs["auth"] == ("auditflow", "s3cret")


@patch("requests.post")
def test_no_auth_when_username_absent(mock_post):
    mock_post.return_value = _ok_response()

    clickhouse_sink.process(ROW, BASE_PROPERTIES)

    assert mock_post.call_args.kwargs["auth"] is None


@patch("requests.post")
def test_successful_insert_returns_delivery_result(mock_post):
    mock_post.return_value = _ok_response()

    result = clickhouse_sink.process(ROW, BASE_PROPERTIES)

    assert result["delivered"] is True
    assert result["destination"] == "clickhouse"
    assert result["database"] == "audit"
    assert result["table"] == "audit_events"
    assert result["status_code"] == 200


@patch("requests.post")
def test_http_error_carries_the_clickhouse_exception_code(mock_post):
    # A bare status code is useless when read back out of a DLQ entry.
    response = MagicMock()
    response.status_code = 400
    response.headers = {"X-ClickHouse-Exception-Code": "62"}
    response.text = "Code: 62. DB::Exception: Syntax error"
    response.raise_for_status.side_effect = requests.exceptions.HTTPError(response=response)
    mock_post.return_value = response

    with pytest.raises(RuntimeError, match="exception code 62"):
        clickhouse_sink.process(ROW, BASE_PROPERTIES)


@patch("requests.post")
def test_transport_error_becomes_a_runtime_error(mock_post):
    mock_post.side_effect = requests.exceptions.ConnectionError("connection refused")

    with pytest.raises(RuntimeError, match="Failed to reach ClickHouse"):
        clickhouse_sink.process(ROW, BASE_PROPERTIES)
