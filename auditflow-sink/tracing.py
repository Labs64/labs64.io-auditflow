"""OpenTelemetry setup for the AuditFlow Transformer service.

Exports spans over OTLP/HTTP. The endpoint is read from the standard
OTEL_EXPORTER_OTLP_ENDPOINT env var; if unset, tracing is a no-op exporter so the
service runs fine without a collector.
"""
import os

from opentelemetry import trace
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor


def setup_tracing(app, service_name: str) -> None:
    provider = TracerProvider(resource=Resource.create({"service.name": service_name}))
    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
    if endpoint:
        provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(provider)
    # FastAPIInstrumentor extracts the incoming W3C traceparent header automatically,
    # continuing the trace started by the Spring Boot backend.
    FastAPIInstrumentor.instrument_app(app)
