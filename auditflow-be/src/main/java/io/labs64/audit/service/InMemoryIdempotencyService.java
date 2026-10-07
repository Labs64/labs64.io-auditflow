package io.labs64.audit.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory {@link IdempotencyService} for the lite local profile, so Redis is not required for
 * a working publish→sink path. Entries carry an expiry timestamp and are evicted lazily (on
 * access) plus opportunistically when the map grows past {@link #PURGE_THRESHOLD}.
 *
 * <p>Active when {@code auditflow.idempotency.store=memory}. <strong>Single-process only</strong>:
 * the guarantee holds within one backend instance and does not survive a restart — adequate for
 * local dev, not for a clustered deployment (use the Redis store there).</p>
 */
@Service
@ConditionalOnProperty(name = "auditflow.idempotency.store", havingValue = "memory")
public class InMemoryIdempotencyService implements IdempotencyService {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryIdempotencyService.class);
    private static final String KEY_PREFIX = "evt:";
    private static final int PURGE_THRESHOLD = 100_000;

    /** A claim or done marker with its expiry (epoch millis). */
    private record Entry(long expiry, boolean done) {
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final Duration claimTtl;
    private final Duration doneTtl;

    public InMemoryIdempotencyService(
            @Value("${auditflow.idempotency.claim-ttl:PT5M}") Duration claimTtl,
            @Value("${auditflow.idempotency.done-ttl:PT24H}") Duration doneTtl) {
        this.claimTtl = claimTtl;
        this.doneTtl = doneTtl;
        logger.info("Idempotency store: memory (single-process, claim-ttl={}, done-ttl={})", claimTtl, doneTtl);
    }

    @Override
    public boolean claim(String eventId) {
        long now = System.currentTimeMillis();
        maybePurge(now);
        long newExpiry = now + claimTtl.toMillis();
        AtomicBoolean acquired = new AtomicBoolean(false);
        store.compute(key(eventId), (k, existing) -> {
            if (existing == null || existing.expiry() <= now) {
                acquired.set(true);
                return new Entry(newExpiry, false);
            }
            return existing; // still live → claim refused
        });
        if (!acquired.get()) {
            logger.debug("Duplicate or in-flight eventId '{}', claim refused", eventId);
        }
        return acquired.get();
    }

    @Override
    public boolean takeOver(String eventId) {
        long now = System.currentTimeMillis();
        AtomicBoolean taken = new AtomicBoolean(false);
        store.compute(key(eventId), (k, existing) -> {
            if (existing != null && existing.done() && existing.expiry() > now) {
                return existing; // routed already → a true duplicate
            }
            taken.set(true);
            return new Entry(now + claimTtl.toMillis(), false);
        });
        return taken.get();
    }

    @Override
    public void markProcessed(String eventId) {
        store.put(key(eventId), new Entry(System.currentTimeMillis() + doneTtl.toMillis(), true));
    }

    @Override
    public void release(String eventId) {
        store.remove(key(eventId));
    }

    @Override
    public void markPipelineDone(String eventId, String pipelineName) {
        store.put(pipelineKey(eventId, pipelineName),
                new Entry(System.currentTimeMillis() + doneTtl.toMillis(), true));
    }

    @Override
    public boolean isPipelineDone(String eventId, String pipelineName) {
        Entry entry = store.get(pipelineKey(eventId, pipelineName));
        return entry != null && entry.expiry() > System.currentTimeMillis();
    }

    /** Drop expired entries when the map has grown large, to bound memory for long-running dev sessions. */
    private void maybePurge(long now) {
        if (store.size() > PURGE_THRESHOLD) {
            store.values().removeIf(entry -> entry.expiry() <= now);
        }
    }

    private String key(String eventId) {
        return KEY_PREFIX + eventId;
    }

    private String pipelineKey(String eventId, String pipelineName) {
        return KEY_PREFIX + eventId + ":pipe:" + pipelineName;
    }
}
