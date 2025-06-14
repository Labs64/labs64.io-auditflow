package io.labs64.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

        String transformerPodUrl = transformerDiscovery.getTransformerUrl();
        if (transformerPodUrl != null) {
            logger.info("Determined transformer at URL '{}'. Initiating transformation...", transformerPodUrl);
            transformMessage(message, transformerPodUrl)
                    .subscribe(
                            response -> logger.info("Transformation successful for transformer at URL '{}'. Response: {}", transformerPodUrl, response),
                            error -> logger.error("Transformation failed for transformer URL '{}'. Error: {}", transformerPodUrl, error.getMessage())
                    );
        } else {
            logger.error("Determined transformer URL is empty!");
        }
    }

    /**
     * Transforms a JSON string by sending it to a specific transformer pod URL.
     *
     * @param message The JSON string to transform.
     * @param transformerUrl The full URL of the specific transformer pod (e.g., "http://<pod-ip>:8080").
     * @return A Mono emitting the transformed string, or an error.
     */
    public Mono<String> transformMessage(String message, String transformerUrl) {
        WebClient client = WebClient.create(transformerUrl);

        return client.post()
                .uri("/transform/zero")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .retrieve()
                .bodyToMono(String.class);
    }

}
