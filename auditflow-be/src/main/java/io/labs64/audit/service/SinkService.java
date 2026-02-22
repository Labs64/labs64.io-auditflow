package io.labs64.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for sending transformed events to Python-based sink service.
 * Replaces the Java processor architecture with a flexible, pluggable sink system.
 */
@Service
public class SinkService {

    private static final Logger logger = LoggerFactory.getLogger(SinkService.class);

    private final SinkDiscovery sinkDiscovery;
    private final ObjectMapper objectMapper;
    private final Map<String, WebClient> webClientCache = new ConcurrentHashMap<>();

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
        logger.debug("Send event to sink '{}'", sinkName);

        String sinkUrl = sinkDiscovery.getSinkUrl();
        if (sinkUrl == null || sinkUrl.isEmpty()) {
            throw new IllegalStateException("Sink service URL is empty or null");
        }

        logger.debug("Determined sink service '{}' at URL '{}'. Sending to sink...", sinkName, sinkUrl);

        try {
            String result = sendEventToSink(message, sinkUrl, sinkName, properties);
            logger.info("Event sent to sink '{}' successfully. Response: {}", sinkName, result);
            return result;
        } catch (Exception e) {
            logger.error("Failed to send event to sink '{}' at URL '{}'. Error: {}", sinkName, sinkUrl, e.getMessage(), e);
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
    private String sendEventToSink(String message, String sinkUrl, String sinkName, Map<String, String> properties) {
        Map<String, Object> requestBody = new HashMap<>();

        try {
            // Parse the JSON string into a Map to preserve the actual structure
            Object eventData = objectMapper.readValue(message, Object.class);
            requestBody.put("event_data", eventData);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON message for sink: " + e.getMessage(), e);
        }

        requestBody.put("properties", properties != null ? properties : new HashMap<>());

        logger.trace("Sending event to sink '{}' at URL '{}'", sinkName, sinkUrl);

        WebClient client = webClientCache.computeIfAbsent(sinkUrl, WebClient::create);

        return client.post()
                .uri("/sink/" + sinkName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }
}
