package io.labs64.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TransformationService {

    private static final Logger logger = LoggerFactory.getLogger(TransformationService.class);

    private final TransformerDiscovery transformerDiscovery;
    private final ObjectMapper objectMapper;
    private final Map<String, WebClient> webClientCache = new ConcurrentHashMap<>();

    public TransformationService(TransformerDiscovery transformerDiscovery, ObjectMapper objectMapper) {
        this.transformerDiscovery = transformerDiscovery;
        this.objectMapper = objectMapper;
    }

    public String transform(String message, String transformerName) {
        logger.debug("Trigger transformer '{}' process for message", transformerName);

        String transformerUrl = transformerDiscovery.getTransformerUrl();
        if (transformerUrl == null || transformerUrl.isEmpty()) {
            throw new IllegalStateException("Transformer URL is empty or null");
        }

        logger.debug("Determined transformer '{}' at URL '{}'. Initiating transformation...", transformerName, transformerUrl);

        try {
            String result = transformMessage(message, transformerUrl, transformerName);
            logger.info("Transformation successful for transformer '{}'. Response: {}", transformerName, result);
            return result;
        } catch (Exception e) {
            logger.error("Transformation failed for transformer '{}' at URL '{}'. Error: {}", transformerName, transformerUrl, e.getMessage(), e);
            throw new RuntimeException("Transformation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Transforms a JSON string by sending it to a specific transformer pod URL.
     *
     * @param message         The JSON string to transform.
     * @param transformerUrl  The full URL of the specific transformer pod (e.g., "http://&lt;pod-ip&gt;:8080").
     *                        This should not include the path.
     * @param transformerName The name of the transformer to use.
     *                        This is appended to the URL path for the transformation request.
     * @return The transformed string.
     */
    private String transformMessage(String message, String transformerUrl, String transformerName) {
        Object requestBody;
        try {
            // Parse JSON string to Object to ensure proper serialization
            requestBody = objectMapper.readValue(message, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON message for transformer: " + e.getMessage(), e);
        }

        WebClient client = webClientCache.computeIfAbsent(transformerUrl, WebClient::create);

        return client.post()
                .uri("/transform/" + transformerName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

}
