package io.labs64.audit.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
@ConditionalOnProperty(name = "transformer.discovery.mode", havingValue = "local", matchIfMissing = true)
public class LocalDiscoveryService implements TransformerDiscovery {

    @Value("${transformer.local.port:8081}")
    private int transformerPort;

    @Override
    public String getTransformerUrl() {
        return "http://localhost:" + transformerPort;
    }

}
