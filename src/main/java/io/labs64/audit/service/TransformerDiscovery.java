package io.labs64.audit.service;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TransformerDiscovery {

    private static final Logger logger = LoggerFactory.getLogger(TransformerDiscovery.class);

    private final KubernetesClient kubernetesClient;

    @Value("${kubernetes.transformers.namespace:default}")
    private String transformersNamespace;

    @Value("${kubernetes.transformers.id-label}")
    private String transformerIdLabel;

    public TransformerDiscovery(KubernetesClient kubernetesClient) {
        this.kubernetesClient = kubernetesClient;
    }

    @PostConstruct
    public void init() {
        logger.info("TransformerDiscovery initialized with K8s config: Namespace={}, Transformer Label={}",
                transformersNamespace, transformerIdLabel);
    }

    /**
     * Get map of transformer IDs to their pod URLs
     * @return map of pod transformer-id label and connect URL
     */
    public Map<String, String> getAvailableTransformersWithUrls() {
        try {
            return kubernetesClient.pods()
                    .inNamespace(transformersNamespace)
                    .withLabelSelector(transformerIdLabel)
                    .list()
                    .getItems()
                    .stream()
                    .filter(pod -> pod.getMetadata() != null && pod.getMetadata().getLabels() != null &&
                            pod.getMetadata().getLabels().containsKey(transformerIdLabel) &&
                            pod.getStatus() != null && pod.getStatus().getPodIP() != null &&
                            pod.getSpec() != null && pod.getSpec().getContainers() != null && !pod.getSpec().getContainers().isEmpty())
                    .collect(Collectors.toMap(
                            pod -> pod.getMetadata().getLabels().get(transformerIdLabel),
                            pod -> {
                                logger.info("Found POD: {}", pod);
                                // Find the first available container port
                                Optional<Integer> port = pod.getSpec().getContainers().stream()
                                        .filter(container -> container.getPorts() != null && !container.getPorts().isEmpty())
                                        .flatMap(container -> container.getPorts().stream())
                                        .map(ContainerPort::getContainerPort)
                                        .filter(p -> p != null && p > 0)
                                        .findFirst();
                                if (port.isPresent()) {
                                    return "http://" + pod.getStatus().getPodIP() + ":" + port.get();
                                } else {
                                    logger.warn("Pod '{}' (IP: {}) has transformer-id '{}' but no exposed container port found. Skipping.",
                                            pod.getMetadata().getName(), pod.getStatus().getPodIP(), pod.getMetadata().getLabels().get(transformerIdLabel));
                                    return null;
                                }
                            }
                    ))
                    .entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        } catch (Exception e) {
            logger.error("Failed to retrieve available transformers from Kubernetes", e);
            return Collections.emptyMap();
        }
    }

}
