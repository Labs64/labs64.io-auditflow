package io.labs64.audit.exception;

public class TenantNotProvisionedException extends RuntimeException {
    public TenantNotProvisionedException(String message) {
        super(message);
    }
}
