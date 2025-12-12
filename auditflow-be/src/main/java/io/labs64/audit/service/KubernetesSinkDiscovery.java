package io.labs64.audit.service;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Kubernetes-based sink discovery.
 * Discovers the sink service URL via Kubernetes service discovery.
 */
@org.springframework.stereotype.Service
@ConditionalOnProperty(name = "sink.discovery.mode", havingValue = "kubernetes")
public class KubernetesSinkDiscovery implements SinkDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesSinkDiscovery.class);

    @Autowired
    private KubernetesClient kubernetesClient;

    @Value("${sink.service.name:auditflow-sink}")
    private String serviceName;

    @Value("${sink.service.namespace:default}")
    private String namespace;

    @Value("${sink.service.port:8082}")
    private int port;

    @Override
    public String getSinkUrl() {
        try {
            Service service = kubernetesClient.services()
                    .inNamespace(namespace)
                    .withName(serviceName)
                    .get();

            if (service != null) {
                String url = String.format("http://%s.%s.svc.cluster.local:%d",
                        serviceName, namespace, port);
                logger.debug("Discovered sink service via Kubernetes: {}", url);
                return url;
            } else {
                logger.warn("Sink service '{}' not found in namespace '{}'", serviceName, namespace);
                return null;
            }
        } catch (Exception e) {
            logger.error("Failed to discover sink service via Kubernetes: {}", e.getMessage(), e);
            return null;
        }
    }
}

