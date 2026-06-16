package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TransformationService {

    private static final Logger logger = LoggerFactory.getLogger(TransformationService.class);

    private final TransformerDiscovery transformerDiscovery;
    private final Map<String, WebClient> webClientCache = new ConcurrentHashMap<>();

    public TransformationService(TransformerDiscovery transformerDiscovery) {
        this.transformerDiscovery = transformerDiscovery;
    }

    /** Package-private accessor for testing — allows injecting mock WebClient instances. */
    Map<String, WebClient> getWebClientCache() {
        return webClientCache;
    }

    public String transform(JsonNode message, String transformerName) {
        logger.debug("Trigger transformer '{}' process for message", transformerName);

        if (transformerName == null || !transformerName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid transformer name: '" + transformerName
                    + "'. Only alphanumeric characters and underscores are allowed.");
        }

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
     * Transforms an already-parsed JSON event by sending it to a specific transformer pod URL.
     *
     * @param message         The parsed JSON event to transform.
     * @param transformerUrl  The full URL of the specific transformer pod (e.g., "http://&lt;pod-ip&gt;:8080").
     *                        This should not include the path.
     * @param transformerName The name of the transformer to use.
     *                        This is appended to the URL path for the transformation request.
     * @return The transformed string.
     */
    private String transformMessage(JsonNode message, String transformerUrl, String transformerName) {
        WebClient client = webClientCache.computeIfAbsent(transformerUrl, u ->
                WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(
                                HttpClient.create()
                                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                                        .responseTimeout(Duration.ofSeconds(10))
                        ))
                        .baseUrl(u)
                        .build()
        );

        return client.post()
                .uri("/transform/" + transformerName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

}
