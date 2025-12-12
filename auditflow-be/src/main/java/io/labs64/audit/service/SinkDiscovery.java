package io.labs64.audit.service;

/**
 * Interface for discovering the sink service URL.
 * Implementations can provide different discovery mechanisms (local, Kubernetes, etc.)
 */
public interface SinkDiscovery {

    /**
     * Get the sink service URL.
     *
     * @return The base URL of the sink service
     */
    String getSinkUrl();
}

