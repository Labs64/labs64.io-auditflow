package io.labs64.auditflow.client.auth;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenProviderTest {

    @Test
    void fixedReturnsSameTokenEveryCall() {
        TokenProvider provider = TokenProvider.fixed("abc");
        assertEquals("abc", provider.token());
        assertEquals("abc", provider.token());
    }

    @Test
    void dynamicEvaluatesSupplierPerCall() {
        AtomicInteger counter = new AtomicInteger();
        TokenProvider provider = TokenProvider.dynamic(() -> "t" + counter.incrementAndGet());
        assertEquals("t1", provider.token());
        assertEquals("t2", provider.token());
    }
}
