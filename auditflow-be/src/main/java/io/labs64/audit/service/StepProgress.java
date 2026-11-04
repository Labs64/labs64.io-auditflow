package io.labs64.audit.service;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Where a delivery of a pipeline with {@code steps} stands: how many steps already succeeded for the
 * event, and the context their sinks returned (by step name). A retry, defer or DLQ replay starts at
 * step {@code stepsDone} with this context instead of repeating finished steps.
 *
 * @param stepsDone steps already done, 0 for a fresh delivery
 * @param context   what the done steps' sinks returned, or null when nothing was returned yet
 */
public record StepProgress(int stepsDone, ObjectNode context) {

    public static final StepProgress START = new StepProgress(0, null);

    public StepProgress {
        if (stepsDone < 0) {
            throw new IllegalArgumentException("stepsDone must not be negative");
        }
    }

    /** A fresh delivery: nothing done, nothing returned; such a progress is never written into a message. */
    public boolean isStart() {
        return stepsDone == 0 && (context == null || context.isEmpty());
    }
}
