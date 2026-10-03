package io.labs64.audit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.tenant.TenantConfig;
import io.labs64.audit.tenant.TenantConfigParser;
import io.labs64.audit.tenant.TenantPipelineRegistry;
import reactor.core.publisher.Mono;

class PipelineDryRunTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TransformationService transformations = mock(TransformationService.class);
    private final TenantPipelineRegistry registry = new TenantPipelineRegistry();
    private final PipelineDryRun dryRun = new PipelineDryRun(new ConditionEvaluator(), transformations, registry, mapper,
            20, Duration.ofHours(24));

    private static final String TENANT = """
            tenantId: acme
            pipelines:
              - name: archive
                enabled: true
                retry: {maxAttempts: 3}
                batch: {enabled: true, maxSize: 50}
                transformer: {name: zero}
                sink: {name: aws_s3_sink}
              - name: failed-calls
                enabled: true
                condition:
                  match: all
                  rules: [{field: extra.responseStatus, operator: gte, value: "400"}]
                sink: {name: logging_sink}
              - name: off
                enabled: false
                sink: {name: logging_sink}
            """;

    private TenantConfig tenant() {
        return new TenantConfigParser(mapper).parse(TENANT);
    }

    private PipelineDryRun.Case event(String json, String... expect) throws Exception {
        return new PipelineDryRun.Case(null, mapper.readTree(json), expect.length == 0 ? null : List.of(expect));
    }

    @Test
    void reportsMatchesReasonsAndEffectiveSettingsWithoutCallingAnything() throws Exception {
        var report = dryRun.run("acme", tenant(), List.of(event("{\"extra\":{\"responseStatus\":200}}")), false);

        var e = report.events().get(0);
        assertEquals(List.of("archive"), e.matched());
        var archive = e.pipelines().get(0);
        assertEquals(3, archive.maxAttempts());
        assertEquals("PT24H", archive.maxAge());
        assertTrue(archive.batch());
        assertEquals(50, archive.batchMaxSize());
        assertTrue(e.pipelines().get(1).reason().contains("not matched: extra.responseStatus gte '400'"));
        assertEquals("pipeline disabled", e.pipelines().get(2).reason());
        assertNull(report.failedCases(), "no expectations given");
        org.mockito.Mockito.verifyNoInteractions(transformations);
    }

    @Test
    void fixtureCasesPassOrFailWithAReadableReason() throws Exception {
        var report = dryRun.run("acme", tenant(), List.of(
                event("{\"extra\":{\"responseStatus\":500}}", "archive", "failed-calls"),
                event("{\"extra\":{\"responseStatus\":200}}", "archive", "failed-calls")), false);

        assertEquals(1, report.failedCases());
        assertEquals(Boolean.TRUE, report.events().get(0).passed());
        List<String> failures = PipelineDryRun.failures(report);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("failed-calls=expected to match but"), failures.get(0));
    }

    @Test
    void transformRunsOnlyForMatchingPipelines() throws Exception {
        when(transformations.transform(any(), eq("zero"))).thenReturn(Mono.just("{\"done\":true}"));

        var report = dryRun.run("acme", tenant(), List.of(event("{\"extra\":{\"responseStatus\":200}}")), true);

        var archive = report.events().get(0).pipelines().get(0);
        assertEquals(true, archive.transformed().path("done").asBoolean());
        assertNull(report.events().get(0).pipelines().get(1).transformed());
    }

    @Test
    void warningsCatchSilentMisconfiguration() {
        var cfg = new TenantConfigParser(mapper).parse("""
                tenantId: acme
                pipelines:
                  - name: a
                    enabled: true
                    condition: {rules: [{field: eventType, operator: startWith, value: x}]}
                    sink: {name: logging_sink}
                  - name: a
                    enabled: true
                    sink: {name: "bad-sink"}
                """);
        var warnings = dryRun.run("acme", cfg, List.of(), false).warnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("unknown operator 'startWith'")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("defined twice")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("invalid sink name")), warnings.toString());
    }

    @Test
    void anUnprovisionedTenantWithoutInlineDocumentIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> dryRun.run("ghost", null, List.of(), false));
    }
}
