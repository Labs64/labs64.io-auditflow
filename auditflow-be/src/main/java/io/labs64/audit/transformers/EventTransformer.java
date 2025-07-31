package io.labs64.audit.transformers;

public interface EventTransformer {

    /**
     * Transforms an audit event message.
     *
     * @param message The original audit event.
     * @return The transformed event object.
     */
    String transform(String message);

    /**
     * Returns the name of the transformer.
     *
     * @return The name of the transformer.
     */
    String getName();

}
