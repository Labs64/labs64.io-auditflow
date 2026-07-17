package io.labs64.audit.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;

/**
 * OpenTelemetry-backed implementation. Uses only opentelemetry-api: with the Java Agent
 * attached, events land on the current auto-instrumented span; without it, the API is a
 * built-in no-op. Never touches the OpenTelemetry SDK.
 */
public class OtelBusinessTelemetry implements BusinessTelemetry {

    private static final AttributeKey<String> EVENT_ID = AttributeKey.stringKey("auditflow.event.id");
    private static final AttributeKey<String> EVENT_TYPE = AttributeKey.stringKey("auditflow.event.type");
    private static final AttributeKey<String> PIPELINE = AttributeKey.stringKey("auditflow.pipeline");
    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("auditflow.pipeline.outcome");
    private static final AttributeKey<String> TENANT = AttributeKey.stringKey("auditflow.tenant");
    private static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("auditflow.tenant.provider");
    private static final AttributeKey<String> TENANT_OUTCOME = AttributeKey.stringKey("auditflow.tenant.outcome");

    @Override
    public void auditEventReceived(String eventId, String eventType) {
        Span.current().addEvent("auditflow.event.received", Attributes.of(
                EVENT_ID, String.valueOf(eventId),
                EVENT_TYPE, String.valueOf(eventType)));
    }

    @Override
    public void pipelineCompleted(String pipelineName, String outcome) {
        Span.current().addEvent("auditflow.pipeline.completed", Attributes.of(
                PIPELINE, String.valueOf(pipelineName),
                OUTCOME, String.valueOf(outcome)));
    }

    @Override
    public void tenantEvent(String tenantId, String provider, String outcome) {
        Span.current().addEvent("auditflow.tenant.event", Attributes.of(
                TENANT, String.valueOf(tenantId),
                PROVIDER, String.valueOf(provider),
                TENANT_OUTCOME, String.valueOf(outcome)));
    }
}
