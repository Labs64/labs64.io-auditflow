package io.labs64.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@Service
public class TransformationService {

    private static final Logger logger = LoggerFactory.getLogger(TransformationService.class);

    private final TransformerDiscovery transformerDiscovery;

    public TransformationService(TransformerDiscovery transformerDiscovery) {
        this.transformerDiscovery = transformerDiscovery;
    }

    public void triggerTransformerProcess(String message) {
        logger.info("Attempting to trigger transformer process for message: '{}'", message);

        Map<String, String> availableTransformersMap = transformerDiscovery.getAvailableTransformersWithUrls();

        if (availableTransformersMap.isEmpty()) {
            logger.warn("No transformers are currently available to process the message!");
            return;
        }

        // Find the transformer ID from the available ones that matches the audit message
        Optional<String> chosenTransformerId = availableTransformersMap.keySet().stream()
                .filter(transformerId -> message.contains(transformerId))
                .findFirst();

        if (chosenTransformerId.isPresent()) {
            String transformerId = chosenTransformerId.get();
            String transformerPodUrl = availableTransformersMap.get(transformerId);
            if (transformerPodUrl != null) {
                logger.info("Determined transformer '{}' at URL '{}'. Initiating transformation...",
                        transformerId, transformerPodUrl);
                startTransformer(transformerId, transformerPodUrl, message);
            } else {
                logger.error("Determined transformer '{}' but could not find its URL!", transformerId);
            }
        } else {
            logger.warn("No suitable transformer found among available ones for given message!");
        }
    }

    private void startTransformer(String transformerId, String transformerPodUrl, String data) {
        // Call the TransformationService with the specific pod URL
        transformData(data, transformerPodUrl)
                .subscribe(
                        response -> logger.info("Transformation successful for transformer '{}' at URL '{}'. Response: {}", transformerId, transformerPodUrl, response),
                        error -> logger.error("Transformation failed for transformer '{}' at URL '{}'. Error: {}", transformerId, transformerPodUrl, error.getMessage())
                );
    }

    /**
     * Transforms a JSON string by sending it to a specific transformer pod URL.
     *
     * @param data The JSON string to transform.
     * @param transformerUrl The full URL of the specific transformer pod (e.g., "http://<pod-ip>:8080").
     * @return A Mono emitting the transformed string, or an error.
     */
    public Mono<String> transformData(String data, String transformerUrl) {
        WebClient client = WebClient.create(transformerUrl);

        return client.post()
                .uri("/transform")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(data)
                .retrieve()
                .bodyToMono(String.class);
    }

}
