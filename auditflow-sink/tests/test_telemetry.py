"""Business telemetry abstraction tests — app code must work with and without OpenTelemetry."""
import builtins
import sys

import telemetry


def test_returns_noop_when_opentelemetry_missing(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, *args, **kwargs):
        if name == "opentelemetry" or name.startswith("opentelemetry."):
            raise ImportError(name)
        return real_import(name, *args, **kwargs)

    monkeypatch.delitem(sys.modules, "opentelemetry", raising=False)
    monkeypatch.setattr(builtins, "__import__", fake_import)
    impl = telemetry.get_business_telemetry()
    assert isinstance(impl, telemetry.NoopBusinessTelemetry)
    impl.sink_completed("loki_sink", True)


def test_selected_implementation_is_always_safe():
    impl = telemetry.get_business_telemetry()
    impl.sink_completed("loki_sink", True)
    impl.sink_completed("loki_sink", False)


def test_noop_is_safe():
    telemetry.NoopBusinessTelemetry().sink_completed(None, None)
