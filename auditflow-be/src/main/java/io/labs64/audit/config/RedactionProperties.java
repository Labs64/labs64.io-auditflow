package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * PII de-identification rules applied to every event at ingest, before it is published to the
 * broker, so raw PII never dwells in the message queue (P2-3). Redaction is global by design —
 * it is not per-pipeline. Field paths use the same dot/array notation as the condition engine
 * (e.g. {@code actor}, {@code extra.userEmail}, {@code items[0].card}).
 */
@ConfigurationProperties(prefix = "auditflow.redaction")
public class RedactionProperties {

    /** Master switch — redaction is opt-in (off by default). */
    private boolean enabled = false;

    /** Replacement value used by the MASK action. */
    private String mask = "***";

    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMask() {
        return mask;
    }

    public void setMask(String mask) {
        this.mask = mask;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules;
    }

    /** What to do with a matched field. */
    public enum Action {
        /** Replace the value with the configured mask string. */
        MASK,
        /** Replace the value with its SHA-256 hex digest (preserves correlatability, hides the value). */
        HASH,
        /** Remove the field entirely. */
        DROP
    }

    public static class Rule {
        private String field;
        private Action action = Action.MASK;

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public Action getAction() {
            return action;
        }

        public void setAction(Action action) {
            this.action = action;
        }
    }
}
