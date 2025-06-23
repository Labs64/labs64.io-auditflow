package io.labs64.audit.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
@ConditionalOnProperty(name = "transformer.discovery.mode", havingValue = "kubernetes")
public class KubernetesDiscoveryService implements TransformerDiscovery {

    private final KubernetesClient kubernetesClient = new KubernetesClientBuilder().build();
    @Value("${transformer.service.namespace:default}")
    private String namespace;
    @Value("${transformer.service.name:auditflow-transformer}")
    private String serviceName;

    @Override
    public String getTransformerUrl() {
        var svc = kubernetesClient.services()
                .inNamespace(namespace)
                .withName(serviceName)
                .get();
        if (svc == null) {
            throw new RuntimeException("Transformer service not found!");
        }
        String ip = svc.getSpec().getClusterIP();
        int port = svc.getSpec().getPorts().get(0).getPort();
        return "http://" + ip + ":" + port;
    }

}
