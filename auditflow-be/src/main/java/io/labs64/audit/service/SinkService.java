package io.labs64.audit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.labs64.audit.config.HttpClientProperties;
import io.labs64.audit.config.HttpRetryProperties;
import io.labs64.audit.exception.RetryableDeliveryException;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    private final ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final Map<String, WebClient> webClientCache = new ConcurrentHashMap<>();
    private final Retry retrySpec;
    private final int maxResponseBytes;
    private final ObjectMapper objectMapper;

    public SinkService(SinkDiscovery sinkDiscovery,
                       WebClient.Builder webClientBuilder,
                       ReactiveCircuitBreakerFactory<?, ?> circuitBreakerFactory,
                       HttpRetryProperties retryProperties,
                       HttpClientProperties httpProperties,
                       MeterRegistry meterRegistry,
                       ObjectMapper objectMapper) {
        this.sinkDiscovery = sinkDiscovery;
        this.webClientBuilder = webClientBuilder;
        this.circuitBreakerFactory = circuitBreakerFactory;
        this.retrySpec = retryProperties.isEnabled()
                ? HttpRetrySupport.spec(retryProperties,
                        meterRegistry.counter("auditflow.http.retries", "service", "sink"))
                : null;
        this.maxResponseBytes = Math.toIntExact(httpProperties.getMaxResponseSize().toBytes());
        this.objectMapper = objectMapper;
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
        // Argument/configuration validation is fail-fast: it throws synchronously at assembly time.
        // When called from a reactive chain (AuditService), Reactor converts the throw into an onError signal.
        if (sinkName == null || !sinkName.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid sink name: '" + sinkName
                    + "'. Only alphanumeric characters and underscores are allowed.");
        }

        String sinkUrl = sinkDiscovery.getSinkUrl();
        if (sinkUrl == null || sinkUrl.isEmpty()) {
            throw new IllegalStateException("Sink service URL is empty or null");
        }

        logger.debug("Sending to sink '{}' at '{}'", sinkName, sinkUrl);

        return sendEventToSink(message, sinkUrl, sinkName, properties)
                .doOnNext(result -> logger.info("Event '{}' sent to sink '{}'", 
                        message.path("eventId").asText("unknown"), sinkName))
                .onErrorMap(e -> {
                    logger.error("Failed to send event to sink '{}' at '{}': {}", sinkName, sinkUrl, e.getMessage(), e);
                    return DeliveryErrors.classify("Failed to send event to sink", e);
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
        // Build the request body as an ObjectNode so Jackson2JsonEncoder serialises the event
        // as its JSON tree content, not as a bean (which would produce JsonNode getter properties).
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.set("event_data", message);
        ObjectNode propertiesNode = objectMapper.createObjectNode();
        if (properties != null) {
            properties.forEach(propertiesNode::put);
        }
        requestBody.set("properties", propertiesNode);

        logger.trace("Sending event to sink '{}' at URL '{}'", sinkName, sinkUrl);

        WebClient client = client(sinkUrl);

        Mono<String> response = client.post()
                .uri("/sink/" + sinkName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class);
        return guarded(response, sinkUrl, sinkName);
    }

    /** Outcome of one event in a batch sink call. */
    public record EntryResult(boolean success, boolean retryable, String error) {
        public static final EntryResult OK = new EntryResult(true, false, null);
    }

    /**
     * Send several events to a sink in ONE call ({@code POST /sink/<name>/batch}). The sink answers
     * per event; a whole-call failure (transport, 5xx, open circuit) is classified like a single send
     * and surfaces as an error signal for the caller to apply to every entry.
     */
    public Mono<List<EntryResult>> sendBatchToSink(List<JsonNode> events, String sinkName, Map<String, String> properties) {
        if (sinkName == null || !sinkName.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Invalid sink name: '" + sinkName
                    + "'. Only alphanumeric characters and underscores are allowed.");
        }
        String sinkUrl = sinkDiscovery.getSinkUrl();
        if (sinkUrl == null || sinkUrl.isEmpty()) {
            throw new IllegalStateException("Sink service URL is empty or null");
        }
        ObjectNode requestBody = objectMapper.createObjectNode();
        var array = requestBody.putArray("events");
        events.forEach(array::add);
        ObjectNode propertiesNode = requestBody.putObject("properties");
        if (properties != null) {
            properties.forEach(propertiesNode::put);
        }
        Mono<String> response = client(sinkUrl).post()
                .uri("/sink/" + sinkName + "/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class);
        return guarded(response, sinkUrl, sinkName)
                .map(body -> parseBatchResults(body, events.size()))
                .doOnNext(results -> logger.info("Batch of {} event(s) sent to sink '{}'", events.size(), sinkName))
                .onErrorMap(e -> {
                    logger.error("Failed to send batch to sink '{}' at '{}': {}", sinkName, sinkUrl, e.getMessage());
                    return DeliveryErrors.classify("Failed to send batch to sink", e);
                });
    }

    private List<EntryResult> parseBatchResults(String body, int expected) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RetryableDeliveryException("Sink batch response is not JSON: " + e.getMessage(), e);
        }
        JsonNode results = root.path("results");
        if (!results.isArray() || results.size() != expected) {
            // Without a result per event nothing can be marked delivered: retry the whole batch.
            throw new RetryableDeliveryException("Sink batch response has " + results.size()
                    + " result(s) for " + expected + " event(s)");
        }
        List<EntryResult> out = new ArrayList<>(expected);
        for (JsonNode r : results) {
            if ("success".equals(r.path("status").asText())) {
                out.add(EntryResult.OK);
            } else {
                out.add(new EntryResult(false, r.path("retryable").asBoolean(true), r.path("error").asText("sink error")));
            }
        }
        return out;
    }

    private WebClient client(String sinkUrl) {
        return webClientCache.computeIfAbsent(sinkUrl, u -> {
            ConnectionProvider provider = ConnectionProvider.builder("sink-pool")
                    .maxConnections(500)
                    .pendingAcquireMaxCount(-1)
                    .maxIdleTime(Duration.ofSeconds(60))
                    .build();

            return webClientBuilder.clone()
                    .clientConnector(new ReactorClientHttpConnector(
                            HttpClient.create(provider)
                                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                                    .option(ChannelOption.SO_KEEPALIVE, true)
                                    .option(ChannelOption.TCP_NODELAY, true)
                                    .responseTimeout(Duration.ofSeconds(10))
                    ))
                    // The whole response is read into memory; WebClient stops at 256 KB by default.
                    .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxResponseBytes))
                    .baseUrl(u)
                    .build();
        });
    }

    private Mono<String> guarded(Mono<String> response, String sinkUrl, String sinkName) {
        // Retry transient failures (5xx, transport errors) before the error is mapped/wrapped,
        // so the retry filter can inspect the original WebClient exception type.
        Mono<String> withRetry = retrySpec != null ? response.retryWhen(retrySpec) : response;

        // Circuit breaker sits OUTSIDE the retry so it counts post-retry outcomes; when open it
        // fast-fails with CallNotPermittedException, which DeliveryErrors maps to a retryable failure.
        return circuitBreakerFactory.create("sink:" + sinkUrl + "/" + sinkName)
                .run(withRetry, Mono::error);
    }
}
