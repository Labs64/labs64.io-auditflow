package io.labs64.audit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.service.ConditionEvaluator;
import io.labs64.audit.service.PipelineDryRun;
import io.labs64.audit.tenant.TenantConfigParser;
import io.labs64.audit.tenant.TenantPipelineRegistry;

class PipelinesEndpointTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TenantPipelineRegistry registry = new TenantPipelineRegistry();
    private final PipelinesEndpoint endpoint = new PipelinesEndpoint(
            new PipelineDryRun(new ConditionEvaluator(), null, registry, mapper, 20, Duration.ofHours(24)),
            new TenantConfigParser(mapper), mapper, 50);

    private static Map<String, Object> inlineTenant(String id) {
        return Map.of("tenantId", id, "pipelines", List.of(Map.of(
                "name", "errors", "enabled", true,
                "condition", Map.of("rules", List.of(Map.of("field", "extra.responseStatus", "operator", "gte", "value", "400"))),
                "sink", Map.of("name", "logging_sink"))));
    }

    @Test
    void anInlineTenantIsCheckedAgainstFixtureCases() {
        var cases = List.<Map<String, Object>>of(
                Map.of("name", "500 is an error", "event", Map.of("extra", Map.of("responseStatus", 500)), "expect", List.of("errors")),
                Map.of("name", "200 is not", "event", Map.of("extra", Map.of("responseStatus", 200)), "expect", List.of("errors")));

        Map<String, Object> result = endpoint.dryRun("acme", null, cases, inlineTenant("acme"), false);

        assertEquals("failed", result.get("status"));
        assertEquals(1, result.get("failedCases"));
        assertTrue(((List<?>) result.get("failures")).get(0).toString().contains("'200 is not'"));
        assertEquals("inline", result.get("source"));
    }

    @Test
    void anInlineTenantForAnotherTenantIsRejected() {
        Map<String, Object> result = endpoint.dryRun("acme", List.of(Map.of("eventType", "x")), null, inlineTenant("globex"), false);
        assertEquals("error", result.get("status"));
    }

    @Test
    void eitherEventsOrCasesAndAtMostOneHundred() {
        assertEquals("error", endpoint.dryRun("acme", null, null, inlineTenant("acme"), false).get("status"));
        var many = java.util.Collections.nCopies(101, Map.<String, Object>of("eventType", "x"));
        assertEquals("error", endpoint.dryRun("acme", many, null, inlineTenant("acme"), false).get("status"));
    }

    @Test
    void anInvalidTenantIdOrUnprovisionedTenantIsAnError() {
        assertEquals("error", endpoint.pipelines("../x").get("status"));
        assertEquals("error", endpoint.pipelines("ghost").get("status"));
        assertEquals("error", endpoint.dryRun("ghost", List.of(Map.of("eventType", "x")), null, null, false).get("status"));
    }

    @Test
    void theDeployedTenantIsListedWithEffectiveSettingsAndNoSinkProperties() {
        registry.upsert(new TenantConfigParser(mapper).parse("""
                tenantId: acme
                enabled: true
                pipelines:
                  - name: archive
                    enabled: true
                    retry: {maxAge: 6h}
                    sink: {name: aws_s3_sink, properties: {secret-access-key: "do-not-show"}}
                """), "test");

        Map<String, Object> result = endpoint.pipelines("acme");

        assertEquals("available", result.get("status"));
        assertTrue(!result.toString().contains("do-not-show"));
        assertTrue(result.toString().contains("maxAge=PT6H"), result.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListingShowsTheBatchSizeASinkCanReallyGet() {
        registry.upsert(new TenantConfigParser(mapper).parse("""
                tenantId: acme
                enabled: true
                pipelines:
                  - name: big
                    batch: {enabled: true, maxSize: 250}
                    sink: {name: aws_s3_sink}
                  - name: small
                    batch: {enabled: true, maxSize: 10}
                    sink: {name: aws_s3_sink}
                  - name: default-size
                    batch: {enabled: true}
                    sink: {name: aws_s3_sink}
                  - name: size-without-enabled
                    batch: {maxSize: 10}
                    sink: {name: aws_s3_sink}
                  - name: single
                    sink: {name: aws_s3_sink}
                """), "test");

        var pipelines = (List<Map<String, Object>>) endpoint.pipelines("acme").get("pipelines");
        Map<String, Object> batch = new java.util.HashMap<>();
        pipelines.forEach(p -> batch.put((String) p.get("name"), p.get("batch")));

        // a sink batch is cut from one received consumer batch (auditflow.delivery.batch-size = 50)
        assertEquals(Map.of("enabled", true, "maxSize", 250, "effectiveMaxSize", 50), batch.get("big"));
        assertEquals(Map.of("enabled", true, "maxSize", 10, "effectiveMaxSize", 10), batch.get("small"));
        assertEquals(Map.of("enabled", true, "maxSize", 100, "effectiveMaxSize", 50), batch.get("default-size"));
        // maxSize alone does not turn batching on
        assertEquals(Map.of("enabled", false), batch.get("size-without-enabled"));
        assertEquals(Map.of("enabled", false), batch.get("single"));
    }
}
