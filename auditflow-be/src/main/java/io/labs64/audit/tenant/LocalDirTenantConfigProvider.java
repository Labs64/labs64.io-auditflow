package io.labs64.audit.tenant;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * {@code local-dir} adapter — the BUILT-IN DEFAULT source: a mounted {@code tenants/} folder of
 * {@code <tenantId>.yaml} files, one per tenant. Poll-based (Capability.POLL) — {@link #reconcile}
 * diffs the directory against the last snapshot and emits UPSERT/DELETE. Exercises the whole model
 * end-to-end without a cluster; a missing directory means zero tenants, never a boot failure.
 */
@Component
@ConditionalOnProperty(name = "tenants.source.mode", havingValue = "local-dir", matchIfMissing = true)
public class LocalDirTenantConfigProvider implements TenantConfigProvider {

    private static final Logger logger = LoggerFactory.getLogger(LocalDirTenantConfigProvider.class);

    private final Path dir;
    private final TenantConfigParser parser;
    private final MeterRegistry meterRegistry;
    /** Raw file content kept alongside the parsed config: change detection diffs the RAW yaml,
     *  because the PipelineProperties config beans have no value equality. */
    private record Loaded(String raw, TenantConfig cfg) {
    }
    private final Map<String, Loaded> lastByTenant = new HashMap<>();
    private ScheduledExecutorService poller;

    public LocalDirTenantConfigProvider(
            @Value("${tenants.source.local-dir.path:/config/tenants}") String path,
            TenantConfigParser parser,
            MeterRegistry meterRegistry) {
        this.dir = resolvePath(path);
        this.parser = parser;
        this.meterRegistry = meterRegistry;
    }

    /** A {@code classpath:} dir is only file-resolvable when resources are exploded (IDE / mvn run);
     *  packaged-jar deployments use a mounted dir, so an unresolvable classpath dir means zero
     *  tenants — logged loudly, never a boot failure. */
    private static Path resolvePath(String path) {
        if (!path.startsWith("classpath:")) {
            return Path.of(path);
        }
        try {
            return new org.springframework.core.io.DefaultResourceLoader()
                    .getResource(path).getFile().toPath();
        } catch (IOException e) {
            logger.error("Tenants dir '{}' is not file-resolvable ({}); treating as empty — "
                    + "use a mounted directory for packaged deployments", path, e.getMessage());
            return Path.of("/nonexistent-classpath-tenants");
        }
    }

    @Override
    public String id() {
        return "local-dir";
    }

    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.POLL);
    }

    @Override
    public synchronized Collection<TenantConfig> loadAll() {
        lastByTenant.clear();
        lastByTenant.putAll(scan());
        logger.info("local-dir tenant source loaded {} tenant(s) from {}", lastByTenant.size(), dir);
        return lastByTenant.values().stream().map(Loaded::cfg).toList();
    }

    @Override
    public Closeable subscribe(Consumer<TenantChange> onChange) {
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-localdir-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> {
            try {
                reconcile(onChange);
            } catch (RuntimeException e) {
                logger.warn("local-dir reconcile failed: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
        return () -> poller.shutdownNow();
    }

    /** Re-scan and emit the diff against the last snapshot. Malformed files are skipped + counted. */
    synchronized void reconcile(Consumer<TenantChange> onChange) {
        Map<String, Loaded> current = scan();
        for (Map.Entry<String, Loaded> e : current.entrySet()) {
            Loaded prev = lastByTenant.get(e.getKey());
            if (prev == null || !prev.raw().equals(e.getValue().raw())) {
                onChange.accept(TenantChange.upsert(e.getValue().cfg()));
            }
        }
        Set<String> removed = new HashSet<>(lastByTenant.keySet());
        removed.removeAll(current.keySet());
        for (String tenantId : removed) {
            onChange.accept(TenantChange.delete(tenantId));
        }
        lastByTenant.clear();
        lastByTenant.putAll(current);
    }

    private Map<String, Loaded> scan() {
        Map<String, Loaded> result = new HashMap<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".yaml") || n.endsWith(".yml");
            }).forEach(p -> {
                try {
                    String raw = Files.readString(p);
                    TenantConfig cfg = parser.parse(raw);
                    if (!cfg.tenantId().equals(fileTenantGuess(p))) {
                        logger.warn("Tenant file {} declares tenantId '{}' — convention is <tenantId>.yaml",
                                p.getFileName(), cfg.tenantId());
                    }
                    result.put(cfg.tenantId(), new Loaded(raw, cfg));
                } catch (IOException | RuntimeException ex) {
                    // Last-good retained: skip this file, do not remove an existing tenant.
                    logger.error("Skipping malformed tenant file {}: {}", p, ex.getMessage());
                    meterRegistry.counter("auditflow.tenant.config.reload.errors").increment();
                    Loaded prev = lastByTenant.get(fileTenantGuess(p));
                    if (prev != null) {
                        result.put(prev.cfg().tenantId(), prev);
                    }
                }
            });
        } catch (IOException e) {
            logger.error("Failed to list tenant dir {}: {}", dir, e.getMessage());
        }
        return result;
    }

    private String fileTenantGuess(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
