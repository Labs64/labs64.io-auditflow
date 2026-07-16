package io.labs64.audit.tenant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalDirTenantConfigProviderTest {

    private LocalDirTenantConfigProvider provider(Path dir, SimpleMeterRegistry reg) {
        return new LocalDirTenantConfigProvider(dir.toString(),
                new TenantConfigParser(new ObjectMapper()), reg);
    }

    private void write(Path dir, String file, String yaml) throws Exception {
        Files.writeString(dir.resolve(file), yaml);
    }

    @Test
    void loadAllReadsEveryTenantYaml(@TempDir Path dir) throws Exception {
        write(dir, "acme.yaml", "tenantId: acme\nenabled: true\n");
        write(dir, "globex.yaml", "tenantId: globex\nenabled: false\n");
        var p = provider(dir, new SimpleMeterRegistry());
        var loaded = p.loadAll();
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().anyMatch(c -> c.tenantId().equals("acme") && c.enabled()));
        assertTrue(loaded.stream().anyMatch(c -> c.tenantId().equals("globex") && !c.enabled()));
    }

    @Test
    void reconcileEmitsUpsertOnAddAndDeleteOnRemoval(@TempDir Path dir) throws Exception {
        var p = provider(dir, new SimpleMeterRegistry());
        List<TenantChange> changes = new ArrayList<>();
        p.loadAll(); // establish baseline (empty)

        write(dir, "acme.yaml", "tenantId: acme\nenabled: true\n");
        p.reconcile(changes::add);
        assertEquals(1, changes.size());
        assertEquals(TenantChange.Type.UPSERT, changes.get(0).type());
        assertEquals("acme", changes.get(0).tenantId());

        changes.clear();
        Files.delete(dir.resolve("acme.yaml"));
        p.reconcile(changes::add);
        assertEquals(1, changes.size());
        assertEquals(TenantChange.Type.DELETE, changes.get(0).type());
        assertEquals("acme", changes.get(0).tenantId());
    }

    @Test
    void unchangedFilesEmitNothingOnRepeatedReconcile(@TempDir Path dir) throws Exception {
        var p = provider(dir, new SimpleMeterRegistry());
        write(dir, "acme.yaml", "tenantId: acme\nenabled: true\npipelines:\n  - name: a\n    sink:\n      name: s\n");
        p.loadAll();
        List<TenantChange> changes = new ArrayList<>();
        p.reconcile(changes::add);
        p.reconcile(changes::add);
        assertTrue(changes.isEmpty(), "unchanged files must not re-emit upserts on every poll");

        write(dir, "acme.yaml", "tenantId: acme\nenabled: false\n");
        p.reconcile(changes::add);
        assertEquals(1, changes.size(), "a real edit still emits exactly one upsert");
    }

    @Test
    void malformedFileIsSkippedAndCounted(@TempDir Path dir) throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var p = provider(dir, reg);
        p.loadAll();
        write(dir, "bad.yaml", "not: [valid");
        List<TenantChange> changes = new ArrayList<>();
        p.reconcile(changes::add);
        assertTrue(changes.isEmpty(), "malformed file must not emit a change");
        assertEquals(1.0, reg.counter("auditflow.tenant.config.reload.errors").count());
    }

    @Test
    void resolvesClasspathPrefix() {
        var p = new LocalDirTenantConfigProvider("classpath:tenants-fixture",
                new TenantConfigParser(new ObjectMapper()), new SimpleMeterRegistry());
        var loaded = p.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("acme", loaded.iterator().next().tenantId());
    }

    @Test
    void unresolvableClasspathDirMeansZeroTenantsNotBootFailure() {
        var p = new LocalDirTenantConfigProvider("classpath:no-such-dir",
                new TenantConfigParser(new ObjectMapper()), new SimpleMeterRegistry());
        assertTrue(p.loadAll().isEmpty());
    }

    @Test
    void malformedEditRetainsLastGoodConfig(@TempDir Path dir) throws Exception {
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        var p = provider(dir, reg);
        write(dir, "acme.yaml", "tenantId: acme\nenabled: true\n");
        p.loadAll();

        write(dir, "acme.yaml", "not: [valid"); // bad edit
        List<TenantChange> changes = new ArrayList<>();
        p.reconcile(changes::add);
        assertTrue(changes.isEmpty(), "bad edit must neither upsert nor delete — last-good retained");
        assertEquals(1.0, reg.counter("auditflow.tenant.config.reload.errors").count());
    }
}
