package io.labs64.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Limits of the outbound HTTP calls to the transformer and sink services, bound from the
 * {@code auditflow.http} prefix and shared by both (the retry settings below it have their own
 * class, {@link HttpRetryProperties}).
 */
@ConfigurationProperties(prefix = "auditflow.http")
public class HttpClientProperties {

    /**
     * Largest transformer or sink response held in memory. A transformer answers with the whole
     * event, so this must not be smaller than the largest event ingest accepts (the gateway's
     * request body limit): an event above it is accepted and can then never be delivered.
     * WebClient's own default is 256 KB.
     */
    private DataSize maxResponseSize = DataSize.ofMegabytes(4);

    public DataSize getMaxResponseSize() {
        return maxResponseSize;
    }

    public void setMaxResponseSize(DataSize maxResponseSize) {
        this.maxResponseSize = maxResponseSize;
    }
}
