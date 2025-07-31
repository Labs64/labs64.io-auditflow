package io.labs64.audit.processors;

import java.util.Map;

public interface DestinationProcessor {

    /**
     * Initializes the processor with its specific properties.
     *
     * @param properties The configuration properties from the YAML.
     */
    void initialize(Map<String, String> properties);

    /**
     * Processes the (potentially transformed) event object.
     *
     * @param message The audit event message to be processed.
     */
    void process(String message);

}
