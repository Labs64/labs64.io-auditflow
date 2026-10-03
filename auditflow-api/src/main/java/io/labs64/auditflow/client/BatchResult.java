package io.labs64.auditflow.client;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of {@link AuditFlowClient#publishBatch}: one entry per submitted event, in submission order.
 * An accepted event is stored by the broker. A rejected one was not; its {@code errorCode} says why
 * ({@code VALIDATION_ERROR} needs a fix, while {@code TENANT_RATE_LIMITED} / {@code PUBLISH_FAILED}
 * entries were already retried as far as the retry policy allows).
 */
public final class BatchResult {

    /** The result of one event. */
    public record Entry(UUID eventId, boolean accepted, String errorCode, String errorMessage) {
    }

    private final List<Entry> entries;

    public BatchResult(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public int acceptedCount() {
        return (int) entries.stream().filter(Entry::accepted).count();
    }

    public List<Entry> rejected() {
        return entries.stream().filter(e -> !e.accepted()).toList();
    }

    public boolean allAccepted() {
        return entries.stream().allMatch(Entry::accepted);
    }
}
