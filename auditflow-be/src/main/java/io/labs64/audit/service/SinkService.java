package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.labs64.audit.config.HttpRetryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
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
    private final WebClient.Builder webClientBuilder;
    private final Map<String, WebClient> webClientCache = new ConcurrentHashMap<>();
    private final Retry retrySpec;

    public SinkService(SinkDiscovery sinkDiscovery,
                       WebClient.Builder webClientBuilder,
                       HttpRetryProperties retryProperties,
                       MeterRegistry meterRegistry) {
        this.sinkDiscovery = sinkDiscovery;
        this.webClientBuilder = webClientBuilder;
        this.retrySpec = retryProperties.isEnabled()
                ? HttpRetrySupport.spec(retryProperties,
                        meterRegistry.counter("auditflow.http.retries", "service", "sink"))
                : null;
    }

    /** Package-private accessor for testing — allows injecting mock WebClient instances. */
    Map<String, WebClient> getWebClientCache() {
        return webClientCache;
    }

    /**
     * Send a transformed event to a sink for processing.
     *
     * @param message    The transformed event (parsed JSON)
     * @param sinkName   The name of the sink to use
     * @param properties Configuration properties for the sink
     * @return Processing result from the sink
     */
    public Mono<String> sendToSink(JsonNode message, String sinkName, Map<String, String> properties) {
        logger.debug("Send event to sink '{}'", sinkName);

        // Argument/configuration validation is fail-fast: it throws synchronously at assembly time.
        // When called from a reactive chain (AuditService), Reactor converts the throw into an onError signal.
        if (sinkName == null || !sinkName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid sink name: '" + sinkName
                    + "'. Only alphanumeric characters and underscores are allowed.");
        }

        String sinkUrl = sinkDiscovery.getSinkUrl();
        if (sinkUrl == null || sinkUrl.isEmpty()) {
            throw new IllegalStateException("Sink service URL is empty or null");
        }

        logger.debug("Determined sink service '{}' at URL '{}'. Sending to sink...", sinkName, sinkUrl);

        return sendEventToSink(message, sinkUrl, sinkName, properties)
                .doOnNext(result -> logger.info("Event sent to sink '{}' successfully. Response: {}", sinkName, result))
                .onErrorMap(e -> {
                    logger.error("Failed to send event to sink '{}' at URL '{}'. Error: {}", sinkName, sinkUrl, e.getMessage(), e);
                    return new RuntimeException("Failed to send event to sink: " + e.getMessage(), e);
                });
    }

    /**
     * Send an event to a specific sink via HTTP POST.
     *
     * @param message    The event (parsed JSON)
     * @param sinkUrl    The base URL of the sink service
     * @param sinkName   The name of the sink
     * @param properties Configuration properties
     * @return Response from the sink
     */
    private Mono<String> sendEventToSink(JsonNode message, String sinkUrl, String sinkName, Map<String, String> properties) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("event_data", message);
        requestBody.put("properties", properties != null ? properties : new HashMap<>());

        logger.trace("Sending event to sink '{}' at URL '{}'", sinkName, sinkUrl);

        WebClient client = webClientCache.computeIfAbsent(sinkUrl, u ->
                webClientBuilder.clone()
                        .clientConnector(new ReactorClientHttpConnector(
                                HttpClient.create()
                                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                                        .responseTimeout(Duration.ofSeconds(10))
                        ))
                        .baseUrl(u)
                        .build()
        );

        Mono<String> response = client.post()
                .uri("/sink/" + sinkName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class);

        // Retry transient failures (5xx, transport errors) before the error is mapped/wrapped,
        // so the retry filter can inspect the original WebClient exception type.
        return retrySpec != null ? response.retryWhen(retrySpec) : response;
    }
}
