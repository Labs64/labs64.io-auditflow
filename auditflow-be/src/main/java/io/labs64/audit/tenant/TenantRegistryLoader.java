package io.labs64.audit.tenant;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;

/**
 * Wires the single active {@link TenantConfigProvider} into the {@link TenantPipelineRegistry}:
 * an initial {@code loadAll} snapshot, then a live {@code subscribe} stream. This is the only place
 * the provider meets the registry — the registry is source-agnostic.
 */
@Component
public class TenantRegistryLoader {

    private static final Logger logger = LoggerFactory.getLogger(TenantRegistryLoader.class);

    private final TenantConfigProvider provider;
    private final TenantPipelineRegistry registry;
    private Closeable subscription;

    public TenantRegistryLoader(TenantConfigProvider provider, TenantPipelineRegistry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    @PostConstruct
    public void start() {
        logger.info("Loading tenant config from provider '{}' (capabilities={})",
                provider.id(), provider.capabilities());
        registry.replaceAll(provider.loadAll(), provider.id());
        subscription = provider.subscribe(this::apply);
    }

    private void apply(TenantChange change) {
        switch (change.type()) {
            case UPSERT -> registry.upsert(change.config(), provider.id());
            case DELETE -> registry.remove(change.tenantId());
        }
    }

    @PreDestroy
    public void stop() throws IOException {
        if (subscription != null) {
            subscription.close();
        }
    }
}
