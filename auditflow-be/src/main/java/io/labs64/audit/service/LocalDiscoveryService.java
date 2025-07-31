package io.labs64.audit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "transformer.discovery.mode", havingValue = "local", matchIfMissing = true)
public class LocalDiscoveryService implements TransformerDiscovery {

    @Value("${transformer.local.url:http://localhost:8081}")
    private String transformerUrl;

    @Override
    public String getTransformerUrl() {
        return transformerUrl;
    }

}
