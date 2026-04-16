package io.labs64.audit.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Kubernetes-based sink discovery.
 * Discovers the sink service URL via Kubernetes service discovery.
 *
 * <p>Mirrors the pattern used in {@link KubernetesDiscoveryService}: constructor injection
 * and fail-fast {@link IllegalStateException} on lookup failure instead of returning {@code null}.</p>
 */
@org.springframework.stereotype.Service
@ConditionalOnProperty(name = "sink.discovery.mode", havingValue = "kubernetes")
public class KubernetesSinkDiscovery implements SinkDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesSinkDiscovery.class);

    private final KubernetesClient kubernetesClient;

    @Value("${sink.service.name:auditflow-sink}")
    private String serviceName;

    @Value("${sink.service.namespace:default}")
    private String namespace;

    @Value("${sink.service.port:8082}")
    private int port;

    public KubernetesSinkDiscovery(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    @Override
    public String getSinkUrl() {
        try {
            var service = kubernetesClient.services()
                    .inNamespace(namespace)
                    .withName(serviceName)
                    .get();

            if (service == null) {
                logger.warn("Sink service '{}' not found in namespace '{}'", serviceName, namespace);
                throw new IllegalStateException("Sink service not found: " + serviceName);
            }

            String url = String.format("http://%s.%s.svc.cluster.local:%d", serviceName, namespace, port);
            logger.debug("Discovered sink service via Kubernetes: {}", url);
            return url;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to discover sink service via Kubernetes: {}", e.getMessage(), e);
            throw new IllegalStateException("Kubernetes sink discovery failed: " + e.getMessage(), e);
        }
    }
}
