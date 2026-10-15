"""Uvicorn access lines: health probes and successful delivery calls are not logged, failures are."""
import logging

import health


def access(method, path, status):
    """An access record as Uvicorn's access logger emits it."""
    return logging.LogRecord("uvicorn.access", logging.INFO, __file__, 0, '%s - "%s %s HTTP/%s" %d',
                             ("10.0.0.1:5000", method, path, "1.1", status), None)


def test_a_successful_delivery_call_is_not_logged(monkeypatch):
    monkeypatch.delenv("AUDITFLOW_ACCESS_LOG_OK", raising=False)
    assert health.HealthAccessLogFilter().filter(access("POST", "/transform/zero", 200)) is False


def test_a_failed_delivery_call_keeps_its_line(monkeypatch):
    monkeypatch.delenv("AUDITFLOW_ACCESS_LOG_OK", raising=False)
    for status in (400, 404, 422, 500, 503):
        assert health.HealthAccessLogFilter().filter(access("POST", "/transform/zero", status)) is True


def test_successful_delivery_lines_can_be_switched_back_on(monkeypatch):
    monkeypatch.setenv("AUDITFLOW_ACCESS_LOG_OK", "true")
    assert health.HealthAccessLogFilter().filter(access("POST", "/transform/zero", 200)) is True


def test_health_probes_are_still_dropped_and_other_paths_still_logged(monkeypatch):
    monkeypatch.delenv("AUDITFLOW_ACCESS_LOG_OK", raising=False)
    f = health.HealthAccessLogFilter()
    assert f.filter(access("GET", "/health", 200)) is False
    assert f.filter(access("GET", "/registry", 200)) is True
    assert f.filter(access("POST", "/registry/reload", 200)) is True
