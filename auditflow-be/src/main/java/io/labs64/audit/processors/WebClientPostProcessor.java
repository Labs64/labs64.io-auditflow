package io.labs64.audit.processors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

import java.util.Map;

public class WebClientPostProcessor implements DestinationProcessor {

    private static final Logger logger = LoggerFactory.getLogger(WebClientPostProcessor.class);

    private String serviceUrl;
    private String servicePath;
    private String username;
    private String password;

    private WebClient webClient;

    @Override
    public void initialize(Map<String, String> properties) {
        this.serviceUrl = properties.get("service-url");
        this.servicePath = properties.get("service-path");
        this.username = properties.get("username");
        this.password = properties.get("password");

        if (serviceUrl == null || serviceUrl.isEmpty()) {
            logger.warn("Service URL not provided in properties. Using default: {}", this.serviceUrl);
        }
        if (servicePath == null || servicePath.isEmpty()) {
            logger.warn("Service Path not provided in properties. Using default: {}", this.servicePath);
        }

        // Create an HttpClient that ignores SSL certificate validation
        HttpClient httpClient = HttpClient.create()
                .secure(sslContextSpec -> {
                    try {
                        sslContextSpec.sslContext(
                                SslContextBuilder.forClient()
                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                        .build()
                        );
                    } catch (Exception e) {
                        logger.error("Failed to configure insecure SSL context", e);
                    }
                });


        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(this.serviceUrl);
        if (username.isEmpty() || password.isEmpty()) {
            logger.warn("WebClient configured without Basic Authentication (username or password not provided).");
        } else {
            webClientBuilder.defaultHeaders(headers -> headers.setBasicAuth(username, password));
            logger.info("WebClient configured with Basic Authentication.");
        }
        this.webClient = webClientBuilder.build();

        logger.debug("WebClientPostProcessor initialized with properties: {}", properties);
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
                    logger.debug("Response received: {}", response);
                    if (response.getStatusCode().is2xxSuccessful()) {
                        logger.debug("Successfully sent message. Status: {}", response.getStatusCode());
                    } else {
                        logger.warn("Service responded with non-2xx status. Status: {}, Body: {}",
                                response.getStatusCode(), response.getBody());
                    }
                })
                .doOnError(error -> logger.error("Failed to send message due to network or server error: {}", error.getMessage(), error))
                .subscribe();
    }

}
