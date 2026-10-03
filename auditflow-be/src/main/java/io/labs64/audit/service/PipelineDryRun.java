package io.labs64.audit.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.config.AuditFlowConfiguration.TransformerProperties;
import io.labs64.audit.delivery.RetryPolicy;
import io.labs64.audit.tenant.PipelineSet;
import io.labs64.audit.tenant.TenantConfig;
import io.labs64.audit.tenant.TenantPipelineRegistry;

/**
 * Pipeline dry run: which of a tenant's pipelines a sample event would be routed to, why, with which
 * retry and batch settings, and (optionally) what each pipeline's transformers would produce. Never
 * publishes, delivers or calls a sink. Also checks fixture cases ({@code event} + expected pipelines),
 * so tenant files can be tested before they are deployed.
 */
@Service
public class PipelineDryRun {

    private static final Duration TRANSFORM_TIMEOUT = Duration.ofSeconds(10);
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("^[a-zA-Z0-9_]+$");

    /** One pipeline's verdict for one event. */
    public record PipelineReport(String name, boolean enabled, boolean matched, String reason, String sink,
                                 List<String> transformers, int maxAttempts, String maxAge, boolean batch,
                                 Integer batchMaxSize, JsonNode transformed, String transformError) {
    }

    /** All pipelines for one event; {@code matched} lists the names it would be delivered to. */
    public record EventReport(int index, String name, List<String> matched, List<PipelineReport> pipelines,
                              List<String> expected, Boolean passed) {
    }

    /** Problems in the tenant document that would make a pipeline silently not behave as intended. */
    public record Report(String tenantId, String source, List<String> warnings, List<EventReport> events,
                         Integer failedCases) {
    }

    private final ConditionEvaluator conditionEvaluator;
    private final TransformationService transformationService;
    private final TenantPipelineRegistry registry;
    private final ObjectMapper objectMapper;
    private final RetryPolicy defaultPolicy;

    public PipelineDryRun(ConditionEvaluator conditionEvaluator, TransformationService transformationService,
                          TenantPipelineRegistry registry, ObjectMapper objectMapper,
                          @Value("${auditflow.delivery.retry.max-attempts:20}") int defaultMaxAttempts,
                          @Value("${auditflow.delivery.retry.max-age:PT24H}") Duration defaultMaxAge) {
        this.conditionEvaluator = conditionEvaluator;
        this.transformationService = transformationService;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.defaultPolicy = new RetryPolicy(defaultMaxAttempts, defaultMaxAge);
    }

    /** Effective retry policy of a pipeline (its own settings over the defaults). */
    public RetryPolicy retryPolicy(PipelineProperties pipeline) {
        return RetryPolicy.of(pipeline, defaultPolicy);
    }

    /** The deployed pipelines of a tenant, if it is provisioned. */
    public Optional<List<PipelineProperties>> loadedPipelines(String tenantId) {
        return registry.pipelinesFor(tenantId).map(PipelineSet::pipelines);
    }

    /**
     * Run sample events (or fixture cases with expectations) against a tenant's pipelines.
     *
     * @param tenantId  the tenant
     * @param inline    a tenant document to test instead of the deployed one (null: deployed)
     * @param cases     the events; a case may carry {@code expect} (pipeline names) and {@code name}
     * @param transform also run each matching pipeline's transformers
     */
    public Report run(String tenantId, TenantConfig inline, List<Case> cases, boolean transform) {
        List<PipelineProperties> pipelines;
        String source;
        if (inline != null) {
            pipelines = inline.pipelines();
            source = "inline";
        } else {
            pipelines = loadedPipelines(tenantId).orElseThrow(() ->
                    new IllegalArgumentException("Tenant '" + tenantId + "' is not provisioned (or disabled)"));
            source = "deployed";
        }
        List<EventReport> reports = new ArrayList<>();
        int failed = 0;
        boolean anyExpectation = false;
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            List<PipelineReport> perPipeline = new ArrayList<>();
            List<String> matched = new ArrayList<>();
            for (PipelineProperties p : pipelines) {
                PipelineReport r = report(p, c.event(), transform);
                perPipeline.add(r);
                if (r.matched()) {
                    matched.add(r.name());
                }
            }
            Boolean passed = null;
            if (c.expect() != null) {
                anyExpectation = true;
                passed = new TreeSet<>(c.expect()).equals(new TreeSet<>(matched));
                if (!passed) {
                    failed++;
                }
            }
            reports.add(new EventReport(i, c.name(), matched, perPipeline, c.expect(), passed));
        }
        return new Report(tenantId, source, warnings(pipelines), reports, anyExpectation ? failed : null);
    }

    /** One sample event plus, for fixture checks, the exact set of pipelines it must match. */
    public record Case(String name, JsonNode event, List<String> expect) {
    }

    private PipelineReport report(PipelineProperties p, JsonNode event, boolean transform) {
        List<String> transformers = p.getEffectiveTransformers().stream()
                .filter(t -> t != null && StringUtils.hasText(t.getName()))
                .map(TransformerProperties::getName).toList();
        RetryPolicy policy = RetryPolicy.of(p, defaultPolicy);
        boolean batch = p.isBatchEnabled();
        boolean matched;
        String reason;
        if (!p.isEnabled()) {
            matched = false;
            reason = "pipeline disabled";
        } else {
            ConditionEvaluator.Explanation e = conditionEvaluator.explain(event, p.getCondition());
            matched = e.matched();
            reason = e.reason();
        }
        JsonNode transformed = null;
        String transformError = null;
        if (matched && transform) {
            try {
                JsonNode current = event;
                for (String name : transformers) {
                    String out = transformationService.transform(current, name).block(TRANSFORM_TIMEOUT);
                    current = objectMapper.readTree(out);
                }
                transformed = current;
            } catch (Exception ex) {
                transformError = ex.getMessage();
            }
        }
        return new PipelineReport(p.getName(), p.isEnabled(), matched, reason,
                p.getSink() == null ? null : p.getSink().getName(), transformers, policy.maxAttempts(),
                policy.maxAge().toString(), batch, batch ? p.getBatch().getMaxSize() : null, transformed, transformError);
    }

    /** Configuration problems that do not fail parsing but make a pipeline misbehave silently. */
    List<String> warnings(List<PipelineProperties> pipelines) {
        List<String> warnings = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (PipelineProperties p : pipelines) {
            String name = p.getName();
            if (!StringUtils.hasText(name)) {
                warnings.add("a pipeline has no name");
                continue;
            }
            if (!names.add(name)) {
                warnings.add("pipeline '" + name + "' is defined twice; deliveries and DLQ entries cannot tell them apart");
            }
            for (String op : conditionEvaluator.unknownOperators(p.getCondition())) {
                warnings.add("pipeline '" + name + "' uses unknown operator '" + op + "', which never matches");
            }
            if (p.getSink() == null || !StringUtils.hasText(p.getSink().getName())) {
                warnings.add("pipeline '" + name + "' has no sink");
            } else if (!ID.matcher(p.getSink().getName()).matches()) {
                warnings.add("pipeline '" + name + "' has an invalid sink name '" + p.getSink().getName() + "'");
            }
            for (TransformerProperties t : p.getEffectiveTransformers()) {
                if (t != null && StringUtils.hasText(t.getName()) && !ID.matcher(t.getName()).matches()) {
                    warnings.add("pipeline '" + name + "' has an invalid transformer name '" + t.getName() + "'");
                }
            }
        }
        return warnings;
    }

    /** Parse fixture cases ({@code cases: [{name, event, expect}]}) from a parsed YAML/JSON tree. */
    public static List<Case> casesFrom(JsonNode root) {
        JsonNode cases = root.has("cases") ? root.get("cases") : root;
        if (!cases.isArray()) {
            throw new IllegalArgumentException("fixtures need a 'cases' list");
        }
        List<Case> out = new ArrayList<>();
        for (JsonNode c : cases) {
            if (!c.has("event") || !c.get("event").isObject()) {
                throw new IllegalArgumentException("every case needs an 'event' object");
            }
            List<String> expect = null;
            if (c.has("expect")) {
                expect = new ArrayList<>();
                for (JsonNode e : c.get("expect")) {
                    expect.add(e.asText());
                }
            }
            out.add(new Case(c.path("name").asText(null), c.get("event"), expect));
        }
        return out;
    }

    /** Human-readable failures of a fixture run (empty when every case passed). */
    public static List<String> failures(Report report) {
        List<String> out = new ArrayList<>();
        for (EventReport e : report.events()) {
            if (Boolean.FALSE.equals(e.passed())) {
                Map<String, String> why = new LinkedHashMap<>();
                for (PipelineReport p : e.pipelines()) {
                    boolean expected = e.expected().contains(p.name());
                    if (expected != p.matched()) {
                        why.put(p.name(), (expected ? "expected to match but " : "matched unexpectedly: ") + p.reason());
                    }
                }
                for (String name : e.expected()) {
                    if (e.pipelines().stream().noneMatch(p -> p.name().equals(name))) {
                        why.put(name, "no such pipeline");
                    }
                }
                out.add("case " + (e.name() != null ? "'" + e.name() + "'" : "#" + e.index()) + ": matched "
                        + e.matched() + ", expected " + e.expected() + " " + why);
            }
        }
        return out;
    }
}
