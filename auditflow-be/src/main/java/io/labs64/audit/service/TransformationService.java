package io.labs64.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class TransformationService {

    private static final Logger logger = LoggerFactory.getLogger(TransformationService.class);

    private final TransformerDiscovery transformerDiscovery;
    private final ObjectMapper objectMapper;

    public TransformationService(TransformerDiscovery transformerDiscovery, ObjectMapper objectMapper) {
        this.transformerDiscovery = transformerDiscovery;
        this.objectMapper = objectMapper;
    }

    public String transform(String message, String transformerName) {
        logger.debug("Trigger transformer '{}' process for message: '{}'", transformerName, message);

        Mono<String> transformationResultMono = Mono.fromCallable(transformerDiscovery::getTransformerUrl)
                .flatMap(transformerUrl -> {
                    if (transformerUrl != null && !transformerUrl.isEmpty()) {
                        logger.debug("Determined transformer '{}' at URL '{}'. Initiating transformation...", transformerName, transformerUrl);
                        return transformMessage(message, transformerUrl, transformerName)
                                .doOnSuccess(response -> logger.info("Transformation successful for transformer '{}' at URL '{}'. Response: {}", transformerName, transformerUrl, response))
                                .doOnError(error -> logger.error("Transformation failed for transformer'{}' at URL '{}'. Error: {}", transformerName, transformerUrl, error.getMessage()));
                    } else {
                        logger.error("Determined transformer URL is empty!");
                        return Mono.error(new IllegalStateException("Transformer URL is empty"));
                    }
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("Transformer URL is null or Mono is empty")));


        try {
            return transformationResultMono.block();
        } catch (IllegalStateException e) {
            logger.error("Configuration error: {}", e.getMessage());
            throw new RuntimeException("Transformer configuration error: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("An error occurred during transformation: {}", e.getMessage(), e);
            throw new RuntimeException("Transformation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Transforms a JSON string by sending it to a specific transformer pod URL.
     *
     * @param message         The JSON string to transform.
     * @param transformerUrl  The full URL of the specific transformer pod (e.g., "http://<pod-ip>:8080").
     *                        This should not include the path.
     * @param transformerName The name of the transformer to use.
     *                        This is appended to the URL path for the transformation request.
     * @return A Mono emitting the transformed string, or an error.
     */
    public Mono<String> transformMessage(String message, String transformerUrl, String transformerName) {
        WebClient client = WebClient.create(transformerUrl);

        return Mono.fromCallable(() -> {
                    // Parse JSON string to Object to ensure proper serialization
                    return objectMapper.readValue(message, Object.class);
                })
                .flatMap(requestBody ->
                        client.post()
                                .uri("/transform/" + transformerName)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(requestBody)
                                .retrieve()
                                .bodyToMono(String.class)
                );
    }

}
