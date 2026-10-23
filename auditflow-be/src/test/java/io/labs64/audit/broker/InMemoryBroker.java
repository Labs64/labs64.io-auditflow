package io.labs64.audit.broker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A {@link MessageBroker} in memory for tests: one FIFO per destination (DELAY destinations share one
 * per delay), with the port's scan semantics (only ACK_COUNTED removes; a visitor exception leaves the
 * message and propagates).
 */
public class InMemoryBroker implements MessageBroker {

    private final Map<Destination, Deque<BrokerMessage>> queues = new LinkedHashMap<>();
    private final List<Outgoing> published = new ArrayList<>();
    private final Set<String> tenantDlqs = new HashSet<>();
    private String failWith;
    private int maxMessageBytes = Integer.MAX_VALUE;

    @Override
    public String type() {
        return "in-memory";
    }

    @Override
    public synchronized List<String> publishEach(List<Outgoing> messages) {
        List<String> results = new ArrayList<>(messages.size());
        for (Outgoing out : messages) {
            if (failWith != null) {
                results.add(failWith);
                continue;
            }
            published.add(out);
            queue(out.destination()).addLast(out.message());
            results.add(null);
        }
        return results;
    }

    @Override
    public synchronized void ensureTenantDlq(String tenantId) {
        tenantDlqs.add(tenantId);
    }

    @Override
    public synchronized long scan(Destination queue, ScanVisitor visitor) {
        Deque<BrokerMessage> q = queue(queue);
        Deque<BrokerMessage> kept = new ArrayDeque<>();
        long counted = 0;
        try {
            BrokerMessage m;
            while ((m = q.pollFirst()) != null) {
                ScanAction action;
                try {
                    action = visitor.visit(m);
                } catch (RuntimeException e) {
                    kept.addLast(m);
                    throw e;
                } catch (Exception e) {
                    kept.addLast(m);
                    throw new IllegalStateException(e);
                }
                if (action != ScanAction.ACK_COUNTED) {
                    kept.addLast(m);
                }
                if (action != ScanAction.KEEP) {
                    counted++;
                }
            }
        } finally {
            kept.addAll(q);
            q.clear();
            q.addAll(kept);
        }
        return counted;
    }

    @Override
    public int maxMessageBytes() {
        return maxMessageBytes;
    }

    public InMemoryBroker maxMessageBytes(int bytes) {
        this.maxMessageBytes = bytes;
        return this;
    }

    /** Every publish fails with {@code reason} from now on ({@code null} = succeed again). */
    public InMemoryBroker failWith(String reason) {
        this.failWith = reason;
        return this;
    }

    /** Put a message on a queue without counting it as published. */
    public synchronized InMemoryBroker put(Destination destination, BrokerMessage message) {
        queue(destination).addLast(message);
        return this;
    }

    public synchronized List<BrokerMessage> messages(Destination destination) {
        return List.copyOf(queue(destination));
    }

    public synchronized List<Outgoing> published() {
        return List.copyOf(published);
    }

    public synchronized Set<String> tenantDlqs() {
        return Set.copyOf(tenantDlqs);
    }

    private Deque<BrokerMessage> queue(Destination destination) {
        return queues.computeIfAbsent(destination, d -> new ArrayDeque<>());
    }
}
