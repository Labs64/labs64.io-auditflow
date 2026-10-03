package io.labs64.audit.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.labs64.audit.config.AuditFlowConfiguration.PipelineProperties;
import io.labs64.audit.service.PipelineDryRun;
import io.labs64.audit.tenant.TenantConfig;
import io.labs64.audit.tenant.TenantConfigParser;
import io.labs64.audit.tenant.TenantIds;

/**
 * Tenant pipeline inspection and dry run ({@code /actuator/pipelines/<tenantId>}), on the same
 * internal admin surface as the DLQ endpoint (never routed through the gateway).
 *
 * <ul>
 *   <li>{@code GET}: the tenant's deployed pipelines with their effective retry and batch settings
 *       and configuration warnings. {@code batch.effectiveMaxSize} is the largest batch a sink can
 *       get: {@code batch.maxSize} capped by {@code auditflow.delivery.batch-size}. Sink properties
 *       are never returned.</li>
 *   <li>{@code POST /actuator/pipelines/<tenantId>/dry-run} ({@link PipelineDryRunController}; the
 *       actuator cannot bind JSON lists): {@code {"events": [...]}} or
 *       {@code {"cases": [{"name", "event", "expect": [...]}]}}, optionally {@code "tenant": {...}} (a
 *       tenant document to test instead of the deployed one) and {@code "transform": true}: which
 *       pipelines each event would be routed to and why. With {@code expect}, each case passes or
 *       fails and {@code failedCases} counts the failures. Nothing is published or delivered.</li>
 * </ul>
 */
@Endpoint(id = "pipelines")
@Component
public class PipelinesEndpoint {

    static final int MAX_CASES = 100;
    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");

    private final PipelineDryRun dryRun;
    private final TenantConfigParser parser;
    private final ObjectMapper objectMapper;

    private final int deliveryBatchSize;

    public PipelinesEndpoint(PipelineDryRun dryRun, TenantConfigParser parser, ObjectMapper objectMapper,
                             @Value("${auditflow.delivery.batch-size:50}") int deliveryBatchSize) {
        this.dryRun = dryRun;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.deliveryBatchSize = Math.max(1, deliveryBatchSize);
    }

    @ReadOperation
    public Map<String, Object> pipelines(@Selector String tenantId) {
        String tenant;
        try {
            tenant = validTenant(tenantId);
        } catch (IllegalArgumentException e) {
            return error(tenantId, e.getMessage());
        }
        var pipelines = dryRun.loadedPipelines(tenant);
        if (pipelines.isEmpty()) {
            return error(tenant, "Tenant '" + tenant + "' is not provisioned (or disabled)");
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (PipelineProperties p : pipelines.get()) {
            var policy = dryRun.retryPolicy(p);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", p.getName());
            entry.put("enabled", p.isEnabled());
            entry.put("condition", p.getCondition());
            entry.put("transformers", p.getEffectiveTransformers().stream()
                    .filter(t -> t != null && t.getName() != null).map(t -> t.getName()).toList());
            entry.put("sink", p.getSink() == null ? null : p.getSink().getName());
            entry.put("retry", Map.of("maxAttempts", policy.maxAttempts(), "maxAge", policy.maxAge().toString()));
            // A sink batch is cut from ONE received consumer batch, so it never holds more than
            // auditflow.delivery.batch-size events, whatever maxSize says.
            entry.put("batch", p.isBatchEnabled()
                    ? Map.of("enabled", true, "maxSize", p.getBatch().getMaxSize(),
                            "effectiveMaxSize", Math.min(p.getBatch().getMaxSize(), deliveryBatchSize))
                    : Map.of("enabled", false));
            list.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant);
        result.put("status", "available");
        result.put("warnings", dryRun.run(tenant, null, List.of(), false).warnings());
        result.put("pipelines", list);
        return result;
    }

    /** The dry run behind {@link PipelineDryRunController}; {@code status} is success, failed or error. */
    public Map<String, Object> dryRun(@Selector String tenantId,
                                      @Nullable List<Map<String, Object>> events,
                                      @Nullable List<Map<String, Object>> cases,
                                      @Nullable Map<String, Object> tenant,
                                      @Nullable Boolean transform) {
        String wanted;
        try {
            wanted = validTenant(tenantId);
            List<PipelineDryRun.Case> parsed = cases(events, cases);
            TenantConfig inline = tenant == null ? null : inlineTenant(wanted, tenant);
            PipelineDryRun.Report report = dryRun.run(wanted, inline, parsed, Boolean.TRUE.equals(transform));
            Map<String, Object> result = objectMapper.convertValue(report, LinkedHashMap.class);
            result.put("status", report.failedCases() != null && report.failedCases() > 0 ? "failed" : "success");
            if (report.failedCases() != null) {
                result.put("failures", PipelineDryRun.failures(report));
            }
            return result;
        } catch (IllegalArgumentException e) {
            return error(tenantId, e.getMessage());
        }
    }

    private List<PipelineDryRun.Case> cases(List<Map<String, Object>> events, List<Map<String, Object>> cases) {
        if ((events == null || events.isEmpty()) == (cases == null || cases.isEmpty())) {
            throw new IllegalArgumentException("Send either 'events' or 'cases' (not both, not neither)");
        }
        List<PipelineDryRun.Case> out;
        if (events != null && !events.isEmpty()) {
            out = new ArrayList<>();
            for (Map<String, Object> e : events) {
                out.add(new PipelineDryRun.Case(null, objectMapper.valueToTree(e), null));
            }
        } else {
            out = PipelineDryRun.casesFrom(objectMapper.valueToTree(cases));
        }
        if (out.size() > MAX_CASES) {
            throw new IllegalArgumentException("At most " + MAX_CASES + " events or cases per dry run");
        }
        return out;
    }

    private TenantConfig inlineTenant(String tenantId, Map<String, Object> document) {
        Map<String, Object> doc = new HashMap<>(document);
        Object declared = doc.putIfAbsent("tenantId", tenantId);
        if (declared != null && !tenantId.equals(declared)) {
            throw new IllegalArgumentException("Inline tenant document is for '" + declared + "', not '" + tenantId + "'");
        }
        try {
            // JSON is valid YAML: the parser validates exactly what a tenant file must satisfy.
            return parser.parse(objectMapper.writeValueAsString(doc));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Inline tenant document is not serialisable: " + e.getMessage());
        }
    }

    private static String validTenant(String raw) {
        String tenant = TenantIds.resolve(raw);
        if (!TenantIds.PLATFORM.equals(tenant) && !TENANT_ID.matcher(tenant).matches()) {
            throw new IllegalArgumentException("Invalid tenant id '" + raw + "'");
        }
        return tenant;
    }

    private static Map<String, Object> error(String tenantId, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("tenantId", tenantId);
        result.put("status", "error");
        result.put("error", message);
        return result;
    }
}
