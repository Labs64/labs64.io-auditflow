package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * PII de-identification rules applied to every event at ingest, before it is published to the
 * broker, so raw PII never dwells in the message queue. Redaction is global by design —
 * it is not per-pipeline. Field paths use the same dot/array notation as the condition engine
 * (e.g. {@code actor}, {@code extra.userEmail}, {@code items[0].card}).
 */
@ConfigurationProperties(prefix = "auditflow.redaction")
public class RedactionProperties {

    /** Master switch — redaction is opt-in (off by default). */
    private boolean enabled = false;

    /** Replacement value used by the MASK action. */
    private String mask = "***";

    /**
     * Secret key of the HASH action (HMAC-SHA256), at least 32 characters. Required as soon as an
     * enabled rule uses HASH: without it the application does not start. It is a secret: supply it
     * through the environment ({@code AUDITFLOW_REDACTION_HASH_KEY}, from a Kubernetes Secret), never
     * in a values file or a ConfigMap.
     */
    private String hashKey;

    private List<Rule> rules = new ArrayList<>();

    public String getHashKey() {
        return hashKey;
    }

    public void setHashKey(String hashKey) {
        this.hashKey = hashKey;
    }

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
        /**
         * Replace the value with its HMAC-SHA256 under {@code auditflow.redaction.hash-key}, as hex.
         * Equal values still give equal results, so events can be correlated, but without the key a
         * value cannot be recovered by hashing candidates (which a plain SHA-256 allows for an
         * e-mail address, an IP address or a user id).
         */
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
