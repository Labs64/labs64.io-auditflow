package io.labs64.audit.exception;

public class TenantRateLimitedException extends RuntimeException {
    private final long retryAfterSeconds;

    public TenantRateLimitedException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
