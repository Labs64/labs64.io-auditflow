package io.labs64.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Local sink discovery - returns a configured local URL.
 * Used when sink service is running as a sidecar or on a known local address.
 */
@Service
@ConditionalOnProperty(name = "sink.discovery.mode", havingValue = "local", matchIfMissing = true)
public class LocalSinkDiscovery implements SinkDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(LocalSinkDiscovery.class);

    @Value("${sink.local.url:http://localhost:8082}")
    private String sinkUrl;

    @Override
    public String getSinkUrl() {
        logger.debug("Using local sink discovery. Sink URL: {}", sinkUrl);
        return sinkUrl;
    }
}

