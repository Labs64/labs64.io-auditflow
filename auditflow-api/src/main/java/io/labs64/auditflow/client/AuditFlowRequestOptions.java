package io.labs64.auditflow.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Options applied to one AuditFlow request. */
public final class AuditFlowRequestOptions {

    private static final AuditFlowRequestOptions EMPTY = new AuditFlowRequestOptions(Map.of());

    private final Map<String, String> headers;

    private AuditFlowRequestOptions(final Map<String, String> headers) {
        this.headers = Map.copyOf(headers);
    }

    public static AuditFlowRequestOptions empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, String> headers() {
        return headers;
    }

    public static final class Builder {

        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder headers(final Map<String, String> headers) {
            this.headers.clear();
            this.headers.putAll(Objects.requireNonNull(headers, "headers"));
            return this;
        }

        public Builder header(final String name, final String value) {
            this.headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public AuditFlowRequestOptions build() {
            return headers.isEmpty() ? EMPTY : new AuditFlowRequestOptions(headers);
        }
    }
}
