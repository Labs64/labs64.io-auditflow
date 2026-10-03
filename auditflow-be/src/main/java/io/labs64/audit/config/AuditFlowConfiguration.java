package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ConfigurationProperties(prefix = "auditflow")
public class AuditFlowConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AuditFlowConfiguration.class);

    private List<PipelineProperties> pipelines = new ArrayList<>();

    public List<PipelineProperties> getPipelines() {
        return pipelines;
    }

    public void setPipelines(List<PipelineProperties> pipelines) {
        this.pipelines = pipelines;
    }

    @PostConstruct
    public void logConfiguration() {
        long enabledCount = pipelines.stream().filter(PipelineProperties::isEnabled).count();
        logger.info("AuditFlow configuration loaded — {} pipelines ({} enabled): {}",
                pipelines.size(), enabledCount,
                pipelines.stream()
                        .map(p -> p.getName() + (p.isEnabled() ? "" : " [disabled]"))
                        .toList());
    }

    public static class PipelineProperties {
        private String name;
        private boolean enabled;
        private ConditionProperties condition;
        private TransformerProperties transformer;
        private List<TransformerProperties> transformers = new ArrayList<>();
        private SinkProperties sink;
        /** Per-pipeline delivery retry policy; unset fields take the {@code auditflow.delivery.retry} defaults. */
        private RetryProperties retry;
        /** Sink-side batching; off unless {@code batch.enabled}. */
        private BatchProperties batch;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public ConditionProperties getCondition() {
            return condition;
        }

        public void setCondition(ConditionProperties condition) {
            this.condition = condition;
        }

        public TransformerProperties getTransformer() {
            return transformer;
        }

        public void setTransformer(TransformerProperties transformer) {
            this.transformer = transformer;
        }

        public List<TransformerProperties> getTransformers() {
            return transformers;
        }

        public void setTransformers(List<TransformerProperties> transformers) {
            this.transformers = transformers;
        }

        /**
         * The ordered transformer stages to apply. Backward compatible: the multi-stage
         * {@code transformers} list takes precedence; otherwise the singular {@code transformer}
         * is used as a single stage; if neither is set the event passes through unchanged.
         */
        public List<TransformerProperties> getEffectiveTransformers() {
            if (transformers != null && !transformers.isEmpty()) {
                return transformers;
            }
            if (transformer != null) {
                return List.of(transformer);
            }
            return List.of();
        }

        public SinkProperties getSink() {
            return sink;
        }

        public void setSink(SinkProperties sink) {
            this.sink = sink;
        }

        public RetryProperties getRetry() {
            return retry;
        }

        public void setRetry(RetryProperties retry) {
            this.retry = retry;
        }

        public BatchProperties getBatch() {
            return batch;
        }

        public void setBatch(BatchProperties batch) {
            this.batch = batch;
        }

        public boolean isBatchEnabled() {
            return batch != null && batch.isEnabled();
        }
    }

    /**
     * Delivery retry policy of one pipeline. A delivery is retried with growing delays (seconds up to
     * hours) until it succeeds, {@code maxAttempts} failed attempts are used up, or {@code maxAge} has
     * passed since the router first enqueued it; then it is dead-lettered. Backpressure (rate limit,
     * concurrency cap) defers a delivery without spending an attempt.
     */
    public static class RetryProperties {
        private Integer maxAttempts;
        /** ISO-8601 ({@code PT24H}) or a short form: {@code 90s}, {@code 30m}, {@code 24h}, {@code 2d}. */
        private String maxAge;

        public Integer getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(Integer maxAttempts) {
            if (maxAttempts != null && maxAttempts < 1) {
                throw new IllegalArgumentException("retry.maxAttempts must be at least 1, got " + maxAttempts);
            }
            this.maxAttempts = maxAttempts;
        }

        public String getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(String maxAge) {
            if (maxAge != null) {
                parseDuration(maxAge); // validate at config load, not on the first failure
            }
            this.maxAge = maxAge;
        }

        public java.time.Duration maxAgeDuration() {
            return maxAge == null ? null : parseDuration(maxAge);
        }

        static java.time.Duration parseDuration(String value) {
            String v = value.trim();
            java.time.Duration d;
            try {
                if (v.toUpperCase().startsWith("P")) {
                    d = java.time.Duration.parse(v.toUpperCase());
                } else {
                    var m = java.util.regex.Pattern.compile("^(\\d+)([smhd])$").matcher(v.toLowerCase());
                    if (!m.matches()) {
                        throw new IllegalArgumentException();
                    }
                    long n = Long.parseLong(m.group(1));
                    d = switch (m.group(2)) {
                        case "s" -> java.time.Duration.ofSeconds(n);
                        case "m" -> java.time.Duration.ofMinutes(n);
                        case "h" -> java.time.Duration.ofHours(n);
                        default -> java.time.Duration.ofDays(n);
                    };
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("retry.maxAge '" + value
                        + "' is not a duration (use PT24H, 90s, 30m, 24h or 2d)");
            }
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("retry.maxAge must be positive, got '" + value + "'");
            }
            return d;
        }
    }

    /**
     * Sink-side batching of one pipeline: the delivery worker hands the sink up to {@code maxSize}
     * events of this pipeline in one call (sink {@code /sink/<id>/batch}). A batch closes when it is
     * full or when the worker's receive window ends ({@code auditflow.delivery.batch-receive-timeout}),
     * so latency stays bounded under light load.
     */
    public static class BatchProperties {
        private boolean enabled;
        private int maxSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            if (maxSize < 1 || maxSize > 1000) {
                throw new IllegalArgumentException("batch.maxSize must be between 1 and 1000, got " + maxSize);
            }
            this.maxSize = maxSize;
        }
    }

    /**
     * Condition configuration for pipeline triggering.
     * Supports multiple rules with logical operators.
     */
    public static class ConditionProperties {
        /**
         * Logical operator to combine rules: "and" (default), "or"
         */
        private String match = "all";

        /**
         * List of condition rules to evaluate
         */
        private List<ConditionRule> rules = new ArrayList<>();

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public List<ConditionRule> getRules() {
            return rules;
        }

        public void setRules(List<ConditionRule> rules) {
            this.rules = rules;
        }
    }

    /**
     * A single condition rule that evaluates a field against a value.
     */
    public static class ConditionRule {
        /**
         * JSON path to the field (e.g., "eventType", "extra.actionName", "tenantId")
         */
        private String field;

        /**
         * Comparison operator: eq, neq, gt, gte, lt, lte, contains, startsWith, endsWith, in, notIn,
         * exists, notExists, regex, eqIgnoreCase, cidr, notCidr, wildcard, notWildcard
         */
        private String operator;

        /**
         * Value(s) to compare against. For 'in', 'notIn', 'cidr', 'notCidr', 'wildcard' and
         * 'notWildcard', use comma-separated values.
         */
        private String value;

        /** Nested group: how {@link #rules} combine ("all" default, or "any"). Set together with rules. */
        private String match;

        /**
         * Nested group: when non-empty this rule is a group of rules instead of a comparison, and
         * field/operator/value are ignored (e.g. {@code A and (B or C)}).
         */
        private List<ConditionRule> rules;

        public String getMatch() {
            return match;
        }

        public void setMatch(String match) {
            this.match = match;
        }

        public List<ConditionRule> getRules() {
            return rules;
        }

        public void setRules(List<ConditionRule> rules) {
            this.rules = rules;
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isGroup() {
            return rules != null && !rules.isEmpty();
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class TransformerProperties {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    public static class SinkProperties {
        private String name;
        private Map<String, String> properties;
        /** Optional fallback sink, attempted when the primary sink fails with a retryable error. */
        private SinkProperties fallback;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }


        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }

        public SinkProperties getFallback() {
            return fallback;
        }

        public void setFallback(SinkProperties fallback) {
            this.fallback = fallback;
        }
    }

}
