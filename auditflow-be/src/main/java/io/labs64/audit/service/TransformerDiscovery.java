package io.labs64.audit.service;

/**
 * Interface for discovering the transformer service URL.
 * Implementations can provide different discovery mechanisms (local, Kubernetes, etc.)
 */
public interface TransformerDiscovery {

    /**
     * Get the transformer service URL.
     *
     * @return The base URL of the transformer service
     */
    String getTransformerUrl();

}
