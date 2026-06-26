package io.labs64.auditflow.client.auth;

import java.util.Objects;
import java.util.function.Supplier;

/** Supplies the bearer token used to authenticate publish requests. */
@FunctionalInterface
public interface TokenProvider {

    /** @return the current bearer token, or {@code null} to send no Authorization header. */
    String token();

    /** A provider that always returns the same token. */
    static TokenProvider fixed(String token) {
        return () -> token;
    }

    /** A provider that evaluates the supplier on each request (supports rotating tokens). */
    static TokenProvider dynamic(Supplier<String> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return supplier::get;
    }
}
