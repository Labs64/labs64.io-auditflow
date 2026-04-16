package io.labs64.audit.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Kubernetes-based transformer discovery.
 * Discovers the transformer service URL via Kubernetes service discovery.
 */
@Service
@ConditionalOnProperty(name = "transformer.discovery.mode", havingValue = "kubernetes")
public class KubernetesDiscoveryService implements TransformerDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesDiscoveryService.class);

    private final KubernetesClient kubernetesClient;

    @Value("${transformer.service.namespace:default}")
    private String namespace;

    @Value("${transformer.service.name:auditflow-transformer}")
    private String serviceName;

    public KubernetesDiscoveryService(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    @Override
    public String getTransformerUrl() {
        try {
            var svc = kubernetesClient.services()
                    .inNamespace(namespace)
                    .withName(serviceName)
                    .get();
            if (svc == null) {
                logger.warn("Transformer service '{}' not found in namespace '{}'", serviceName, namespace);
                throw new IllegalStateException("Transformer service not found: " + serviceName);
            }
            String ip = svc.getSpec().getClusterIP();
            List<io.fabric8.kubernetes.api.model.ServicePort> ports = svc.getSpec().getPorts();
            if (ports == null || ports.isEmpty()) {
                throw new IllegalStateException("Transformer service '" + serviceName + "' has no ports defined");
            }
            int port = ports.get(0).getPort();
            String url = "http://" + ip + ":" + port;
            logger.debug("Discovered transformer service via Kubernetes: {}", url);
            return url;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to discover transformer service via Kubernetes: {}", e.getMessage(), e);
            throw new IllegalStateException("Kubernetes transformer discovery failed: " + e.getMessage(), e);
        }
    }

}
