package io.labs64.audit.tenant;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantConfigParserTest {

    private final TenantConfigParser parser = new TenantConfigParser(new ObjectMapper());

    private static final String VALID = """
            tenantId: acme
            enabled: true
            quota:
              rateLimitPerSec: 200
              burst: 400
            pipelines:
              - name: acme-security-opensearch
                enabled: true
                condition:
                  match: all
                  rules:
                    - field: eventType
                      operator: startsWith
                      value: "security."
                transformer:
                  name: audit_opensearch
                sink:
                  name: opensearch_sink
                  properties:
                    endpoint: https://acme.os.internal:9200
                    index: acme-audit
            """;

    @Test
    void parsesCanonicalTenantYaml() {
        TenantConfig cfg = parser.parse(VALID);
        assertEquals("acme", cfg.tenantId());
        assertTrue(cfg.enabled());
        assertEquals(200, cfg.quota().rateLimitPerSec());
        assertEquals(400, cfg.quota().burst());
        assertEquals(1, cfg.pipelines().size());
        assertEquals("acme-security-opensearch", cfg.pipelines().get(0).getName());
        assertEquals("opensearch_sink", cfg.pipelines().get(0).getSink().getName());
        assertEquals("acme-audit", cfg.pipelines().get(0).getSink().getProperties().get("index"));
    }

    @Test
    void missingTenantIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("enabled: true\n"));
    }

    @Test
    void malformedYamlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("tenantId: acme\n  : : bad"));
    }

    @Test
    void quotaDefaultsWhenOmitted() {
        TenantConfig cfg = parser.parse("tenantId: acme\nenabled: true\n");
        assertEquals(TenantConfig.Quota.DEFAULT, cfg.quota());
    }

    @Test
    void invalidTenantIdPatternIsRejectedButPlatformIsAllowed() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("tenantId: \"bad tenant!\"\nenabled: true\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("tenantId: \"_sneaky\"\nenabled: true\n"));
        assertEquals("_platform", parser.parse("tenantId: _platform\nenabled: true\n").tenantId());
    }

    @Test
    void pipelineRetryAndBatchBlocksAreParsed() {
        TenantConfig cfg = parser.parse("""
                tenantId: acme
                pipelines:
                  - name: archive
                    enabled: true
                    retry:
                      maxAttempts: 3
                      maxAge: 6h
                    batch:
                      enabled: true
                      maxSize: 250
                    sink:
                      name: aws_s3_sink
                """);
        var p = cfg.pipelines().get(0);
        assertEquals(3, p.getRetry().getMaxAttempts());
        assertEquals(java.time.Duration.ofHours(6), p.getRetry().maxAgeDuration());
        assertTrue(p.isBatchEnabled());
        assertEquals(250, p.getBatch().getMaxSize());
    }

    @Test
    void anInvalidRetryOrBatchSettingRejectsTheTenantFile() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                tenantId: acme
                pipelines:
                  - name: archive
                    retry:
                      maxAge: soon
                """));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
                tenantId: acme
                pipelines:
                  - name: archive
                    batch:
                      enabled: true
                      maxSize: 0
                """));
    }

    // --- pipeline steps ---

    private static String withPipeline(String pipeline) {
        return "tenantId: acme\nenabled: true\npipelines:\n" + pipeline;
    }

    private static final String STEPS = """
              - name: archive
                enabled: true
                steps:
                  - name: original
                    transformer: { name: zero }
                    sink: { name: aws_s3_sink, properties: { bucket: b } }
                  - name: index
                    transformer: { name: audit_clickhouse }
                    sink: { name: clickhouse_sink }
            """;

    @Test
    void aPipelineWithStepsParsesInOrder() {
        TenantConfig cfg = parser.parse(withPipeline(STEPS));
        var p = cfg.pipelines().get(0);
        assertTrue(p.hasSteps());
        assertEquals(List.of("original", "index"), p.getEffectiveSteps().stream().map(s -> s.getName()).toList());
        assertEquals("aws_s3_sink", p.getSteps().get(0).getSink().getName());
        assertEquals("audit_clickhouse", p.getSteps().get(1).getEffectiveTransformers().get(0).getName());
    }

    @Test
    void aPipelineWithoutStepsIsOneUnnamedStepOfItsTransformerAndSink() {
        TenantConfig cfg = parser.parse(withPipeline("""
                  - name: log
                    transformer: { name: zero }
                    sink: { name: logging_sink }
                """));
        var steps = cfg.pipelines().get(0).getEffectiveSteps();
        assertEquals(1, steps.size());
        assertNull(steps.get(0).getName());
        assertEquals("logging_sink", steps.get(0).getSink().getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // steps and a top-level sink
            "  - name: p\n    sink: { name: s }\n    steps:\n      - { name: a, sink: { name: s } }\n",
            // steps and a top-level transformer
            "  - name: p\n    transformer: { name: t }\n    steps:\n      - { name: a, sink: { name: s } }\n",
            // a step without a name
            "  - name: p\n    steps:\n      - { sink: { name: s } }\n",
            // a step name outside the identifier rule
            "  - name: p\n    steps:\n      - { name: \"a-b\", sink: { name: s } }\n",
            // two steps with one name
            "  - name: p\n    steps:\n      - { name: a, sink: { name: s } }\n      - { name: a, sink: { name: s } }\n",
            // a step without a sink
            "  - name: p\n    steps:\n      - { name: a, transformer: { name: t } }\n",
    })
    void aWrongStepsShapeRefusesTheWholeTenant(String pipeline) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parser.parse(withPipeline(pipeline)));
        assertTrue(e.getMessage().contains("pipeline 'p'"), e.getMessage());
    }
}
