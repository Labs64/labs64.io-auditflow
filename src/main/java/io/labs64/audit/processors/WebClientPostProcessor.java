package io.labs64.audit.processors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

public class WebClientPostProcessor implements DestinationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(WebClientPostProcessor.class);

    private String serviceUrl;
    private String servicePath;
    private WebClient webClient;

    @Override
    public void initialize(Map<String, String> properties) {
        this.serviceUrl = properties.get("service-url");
        this.servicePath = properties.get("service-path");

        if (serviceUrl == null || serviceUrl.isEmpty()) {
            logger.warn("Service URL not provided in properties. Using default: {}", this.serviceUrl);
        }
        if (servicePath == null || servicePath.isEmpty()) {
            logger.warn("Service Path not provided in properties. Using default: {}", this.servicePath);
        }

        this.webClient = WebClient.create(this.serviceUrl);
        logger.info("WebClientPostProcessor initialized with Service URL: {} and Path: {}", this.serviceUrl, this.servicePath);
    }

    @Override
    public void process(String message) {
        if (webClient == null) {
            logger.error("WebClient not initialized. Cannot process message. " +
                    "Please ensure the 'initialize' method is called before 'process'.");
            return;
        }

        logger.debug("Attempting to send message: {}", message);

        webClient.post()
                .uri(this.servicePath)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(response -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                        logger.info("Successfully sent message. Status: {}", response.getStatusCode());
                    } else {
                        logger.warn("Service responded with non-2xx status. Status: {}, Body: {}",
                                response.getStatusCode(), response.getBody());
                    }
                })
                .doOnError(error -> logger.error("Failed to send message due to network or server error: {}", error.getMessage(), error))
                .subscribe();
    }

}
