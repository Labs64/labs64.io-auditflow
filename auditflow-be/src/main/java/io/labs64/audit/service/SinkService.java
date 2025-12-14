package io.labs64.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for sending transformed events to Python-based sink service.
 * Replaces the Java processor architecture with a flexible, pluggable sink system.
 */
@Service
public class SinkService {

    private static final Logger logger = LoggerFactory.getLogger(SinkService.class);

    private final SinkDiscovery sinkDiscovery;
    private ObjectMapper objectMapper;

    public SinkService(SinkDiscovery sinkDiscovery, ObjectMapper objectMapper) {
        this.sinkDiscovery = sinkDiscovery;
        this.objectMapper = objectMapper;
    }

    /**
     * Send a transformed event to a sink for processing.
     *
     * @param message    The transformed event message (JSON string)
     * @param sinkName   The name of the sink to use
     * @param properties Configuration properties for the sink
     * @return Processing result from the sink
     */
    public String sendToSink(String message, String sinkName, Map<String, String> properties) {
        logger.debug("Send event to sink '{}' with properties: {}", sinkName, properties);

        Mono<String> sinkResultMono = Mono.fromCallable(sinkDiscovery::getSinkUrl)
                .flatMap(sinkUrl -> {
                    if (sinkUrl != null && !sinkUrl.isEmpty()) {
                        logger.debug("Determined sink service '{}' at URL '{}'. Sending to sink...", sinkName, sinkUrl);
                        return sendEventToSink(message, sinkUrl, sinkName, properties)
                                .doOnSuccess(response -> logger.info(
                                        "Event sent to sink '{}' successfully. Response: {}",
                                        sinkName, response))
                                .doOnError(error -> logger.error(
                                        "Failed to send event to sink '{}' at URL '{}'. Error: {}",
                                        sinkName, sinkUrl, error.getMessage()));
                    } else {
                        logger.error("Sink service URL is empty!");
                        return Mono.error(new IllegalStateException("Sink service URL is empty"));
                    }
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("Sink service URL is null or Mono is empty")));

        try {
            return sinkResultMono.block();
        } catch (IllegalStateException e) {
            logger.error("Configuration error: {}", e.getMessage());
            throw new RuntimeException("Sink service configuration error: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("An error occurred while sending event to sink: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send event to sink: " + e.getMessage(), e);
        }
    }

    /**
     * Send an event to a specific sink via HTTP POST.
     *
     * @param message    The event message (JSON string)
     * @param sinkUrl    The base URL of the sink service
     * @param sinkName   The name of the sink
     * @param properties Configuration properties
     * @return Response from the sink
     */
    private Mono<String> sendEventToSink(String message, String sinkUrl, String sinkName, Map<String, String> properties) {
        return Mono.fromCallable(() -> {
                    Map<String, Object> requestBody = new HashMap<>();

                    JsonNode eventData = objectMapper.readTree(message);

                    requestBody.put("event_data", eventData);
                    requestBody.put("properties",
                            properties != null ? properties : new HashMap<>());

                    logger.debug(
                            "Sending event to sink '{}' payload JSON: {}",
                            sinkName,
                            objectMapper.writeValueAsString(requestBody)
                    );

                    return requestBody;
                })
                .flatMap(requestBody ->
                        WebClient.create(sinkUrl)
                                .post()
                                .uri("/sink/" + sinkName)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(requestBody)
                                .retrieve()
                                .bodyToMono(String.class)
                );
    }
}
