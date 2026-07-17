package io.labs64.audit.tenant;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * {@code gitops-configmap} adapter (the k8s deployment default — set explicitly by the Helm chart).
 * A fabric8 informer over ConfigMaps selected by PRESENCE of the {@code auditflow.io/tenant} label.
 * On add/change/delete it emits a single-tenant {@link TenantChange}. A malformed {@code tenant.yaml}
 * is skipped (last-good retained) and counted via {@code auditflow.tenant.config.reload.errors} —
 * never fatal. The authoritative tenant id comes from the {@code tenant.yaml} body (the label value
 * is the lossy k8sName), so deletes are resolved via a ConfigMap-name → tenant-id map.
 */
@Component
@ConditionalOnProperty(name = "tenants.source.mode", havingValue = "gitops-configmap")
public class ConfigMapTenantConfigProvider implements TenantConfigProvider {

    private static final Logger logger = LoggerFactory.getLogger(ConfigMapTenantConfigProvider.class);

    private final KubernetesClient client;
    private final TenantConfigMapMapper mapper;
    private final MeterRegistry meterRegistry;
    /** ConfigMap name -> canonical tenantId, learned on load/upsert; resolves deletes. */
    private final Map<String, String> tenantByConfigMapName = new ConcurrentHashMap<>();
    private SharedIndexInformer<ConfigMap> informer;

    public ConfigMapTenantConfigProvider(KubernetesClient client,
                                         TenantConfigMapMapper mapper,
                                         MeterRegistry meterRegistry) {
        this.client = client;
        this.mapper = mapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String id() {
        return "gitops-configmap";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.WATCH);
    }

    @Override
    public Collection<TenantConfig> loadAll() {
        List<TenantConfig> result = new ArrayList<>();
        client.configMaps().withLabel(TenantConfigMapMapper.TENANT_LABEL).list().getItems().forEach(cm -> {
            try {
                TenantConfig cfg = mapper.fromConfigMap(cm);
                tenantByConfigMapName.put(cm.getMetadata().getName(), cfg.tenantId());
                result.add(cfg);
            } catch (RuntimeException e) {
                logger.error("Skipping malformed tenant ConfigMap {}: {}",
                        cm.getMetadata().getName(), e.getMessage());
                meterRegistry.counter("auditflow.tenant.config.reload.errors").increment();
            }
        });
        return result;
    }

    @Override
    public Closeable subscribe(Consumer<TenantChange> onChange) {
        informer = client.configMaps().withLabel(TenantConfigMapMapper.TENANT_LABEL)
                .inform(handler(onChange));
        return () -> informer.stop();
    }

    /** Package-visible so the reconcile/last-good behaviour is unit-testable without an informer. */
    ResourceEventHandler<ConfigMap> handler(Consumer<TenantChange> onChange) {
        return new ResourceEventHandler<>() {
            @Override
            public void onAdd(ConfigMap cm) {
                emitUpsert(cm, onChange);
            }

            @Override
            public void onUpdate(ConfigMap oldCm, ConfigMap newCm) {
                emitUpsert(newCm, onChange);
            }

            @Override
            public void onDelete(ConfigMap cm, boolean deletedFinalStateUnknown) {
                // The label value is the lossy k8sName — resolve the CANONICAL id via the name map,
                // falling back to the final-state body if this provider never saw the ConfigMap.
                String name = cm.getMetadata() == null ? null : cm.getMetadata().getName();
                String tenant = name == null ? null : tenantByConfigMapName.remove(name);
                if (tenant == null) {
                    try {
                        tenant = mapper.fromConfigMap(cm).tenantId();
                    } catch (RuntimeException e) {
                        logger.warn("Cannot resolve tenant for deleted ConfigMap {}; ignoring", name);
                        return;
                    }
                }
                onChange.accept(TenantChange.delete(tenant));
            }
        };
    }

    private void emitUpsert(ConfigMap cm, Consumer<TenantChange> onChange) {
        try {
            TenantConfig cfg = mapper.fromConfigMap(cm);
            tenantByConfigMapName.put(cm.getMetadata().getName(), cfg.tenantId());
            onChange.accept(TenantChange.upsert(cfg));
        } catch (RuntimeException e) {
            logger.error("Skipping malformed tenant ConfigMap {}: {}",
                    cm.getMetadata().getName(), e.getMessage());
            meterRegistry.counter("auditflow.tenant.config.reload.errors").increment();
        }
    }
}
