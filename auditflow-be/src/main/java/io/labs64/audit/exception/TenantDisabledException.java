package io.labs64.audit.exception;

public class TenantDisabledException extends RuntimeException {
    public TenantDisabledException(String message) {
        super(message);
    }
}
