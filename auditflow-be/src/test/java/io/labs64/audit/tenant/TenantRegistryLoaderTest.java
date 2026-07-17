package io.labs64.audit.tenant;

import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TenantRegistryLoaderTest {

    /** A hand-driven provider whose change stream the test controls. */
    static class FakeProvider implements TenantConfigProvider {
        Consumer<TenantChange> sink;
        boolean closed;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.WATCH);
        }

        @Override
        public Collection<TenantConfig> loadAll() {
            return List.of(new TenantConfig("acme", true, TenantConfig.Quota.DEFAULT, List.of()));
        }

        @Override
        public Closeable subscribe(Consumer<TenantChange> onChange) {
            this.sink = onChange;
            return () -> closed = true;
        }
    }

    @Test
    void loadAllPopulatesRegistryAndProvenance() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        new TenantRegistryLoader(new FakeProvider(), reg).start();
        assertEquals(TenantState.PROVISIONED, reg.stateFor("acme"));
        assertEquals("fake", reg.providerFor("acme"));
    }

    @Test
    void subscribedChangesUpdateRegistry() {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        FakeProvider provider = new FakeProvider();
        TenantRegistryLoader loader = new TenantRegistryLoader(provider, reg);
        loader.start();

        provider.sink.accept(TenantChange.upsert(
                new TenantConfig("globex", true, TenantConfig.Quota.DEFAULT, List.of())));
        assertEquals(TenantState.PROVISIONED, reg.stateFor("globex"));

        provider.sink.accept(TenantChange.delete("acme"));
        assertEquals(TenantState.UNPROVISIONED, reg.stateFor("acme"));
    }

    @Test
    void stopClosesTheSubscription() throws Exception {
        TenantPipelineRegistry reg = new TenantPipelineRegistry();
        FakeProvider provider = new FakeProvider();
        TenantRegistryLoader loader = new TenantRegistryLoader(provider, reg);
        loader.start();
        loader.stop();
        assertTrue(provider.closed);
    }
}
