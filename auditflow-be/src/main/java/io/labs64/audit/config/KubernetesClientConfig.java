package io.labs64.audit.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the fabric8 {@link KubernetesClient} used by kubernetes-mode discovery, the
 * gitops-configmap tenant provider, and the k8s-secret resolver. Created ONLY when at least one
 * k8s-backed mode is selected, so docker-compose / bare-JVM installs never need a cluster to boot.
 */
@Configuration
public class KubernetesClientConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression("""
            '${tenants.source.mode:local-dir}' == 'gitops-configmap'
            or '${secretRef.resolver:env}' == 'k8s-secret'
            or '${transformer.discovery.mode:local}' == 'kubernetes'
            or '${sink.discovery.mode:local}' == 'kubernetes'""")
    public KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
