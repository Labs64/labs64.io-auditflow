"""Business telemetry abstraction for the AuditFlow Sink.

Application code depends only on this module — never on OpenTelemetry APIs.
Infrastructure telemetry (HTTP spans, metrics, logs) comes from `opentelemetry-instrument`
auto-instrumentation, enabled by the deployment (see entrypoint.sh); this module carries
only domain signals auto-instrumentation cannot derive.
"""


class NoopBusinessTelemetry:
    """No-op implementation — used when OpenTelemetry is not installed."""

    def sink_completed(self, sink_id, success):
        pass


class OtelBusinessTelemetry:
    """Adds business events to the current auto-instrumented span via opentelemetry-api.

    Without a configured SDK (no auto-instrumentation), the API returns a non-recording
    span, so every call is inherently a safe no-op.
    """

    def __init__(self, trace_api):
        self._trace = trace_api

    def sink_completed(self, sink_id, success):
        self._trace.get_current_span().add_event(
            "auditflow.sink.completed",
            {"auditflow.sink": str(sink_id), "auditflow.success": bool(success)},
        )


def get_business_telemetry():
    """Select the OTel-backed implementation when the API is importable, else no-op.

    The opentelemetry packages are installed by the Dockerfile (requirements-otel.txt,
    infrastructure-owned) and are absent in plain local dev — both paths must work.
    """
    try:
        from opentelemetry import trace
    except ImportError:
        return NoopBusinessTelemetry()
    return OtelBusinessTelemetry(trace)
