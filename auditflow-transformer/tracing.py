"""OpenTelemetry setup for the AuditFlow Transformer service.

Exports traces, logs, and metrics over OTLP/HTTP. The endpoint is read from the
standard OTEL_EXPORTER_OTLP_ENDPOINT env var; if unset, telemetry is a no-op so the
service runs fine without a collector. The proto-http exporters append the correct
per-signal path (/v1/traces, /v1/logs, /v1/metrics) to that base endpoint.
"""
import logging
import os

from opentelemetry import metrics, trace
from opentelemetry._logs import set_logger_provider
from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
from opentelemetry.sdk._logs.export import BatchLogRecordProcessor
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor


def setup_telemetry(app, service_name: str) -> None:
    resource = Resource.create({"service.name": service_name})
    endpoint = os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")

    # Traces
    tracer_provider = TracerProvider(resource=resource)
    if endpoint:
        tracer_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
    trace.set_tracer_provider(tracer_provider)

    if endpoint:
        # Metrics — FastAPI instrumentation + any app meters export over OTLP.
        metric_reader = PeriodicExportingMetricReader(OTLPMetricExporter())
        metrics.set_meter_provider(MeterProvider(resource=resource, metric_readers=[metric_reader]))

        # Logs — bridge stdlib logging to OTLP so log records reach the collector → Loki.
        logger_provider = LoggerProvider(resource=resource)
        logger_provider.add_log_record_processor(BatchLogRecordProcessor(OTLPLogExporter()))
        set_logger_provider(logger_provider)
        handler = LoggingHandler(level=logging.NOTSET, logger_provider=logger_provider)
        logging.getLogger().addHandler(handler)
        
        for logger_name in ["uvicorn", "uvicorn.error", "uvicorn.access", "fastapi"]:
            logging.getLogger(logger_name).addHandler(handler)

    # FastAPIInstrumentor extracts the incoming W3C traceparent header automatically,
    # continuing the trace started by the Spring Boot backend.
    FastAPIInstrumentor.instrument_app(app)
