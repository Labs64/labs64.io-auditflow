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
         * JSON path to the field (e.g., "eventType", "extra.action_name", "tenantId")
         */
        private String field;

        /**
         * Comparison operator: eq, neq, gt, gte, lt, lte, contains, startsWith, endsWith, in, notIn, exists, regex
         */
        private String operator;

        /**
         * Value(s) to compare against. For 'in' and 'notIn' operators, use comma-separated values.
         */
        private String value;

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
