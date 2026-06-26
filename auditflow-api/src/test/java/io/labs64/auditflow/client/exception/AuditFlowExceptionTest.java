package io.labs64.auditflow.client.exception;

import io.labs64.auditflow.model.ErrorResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditFlowExceptionTest {

    @Test
    void carriesStatusCodeAndErrorResponse() {
        ErrorResponse body = new ErrorResponse();
        ValidationException ex = new ValidationException("bad", 400, body);

        assertEquals(400, ex.statusCode());
        assertSame(body, ex.errorResponse());
        assertEquals("bad", ex.getMessage());
        assertTrue(ex instanceof AuditFlowException);
    }

    @Test
    void transportExceptionWrapsCauseWithNoStatus() {
        var cause = new java.io.IOException("boom");
        AuditFlowTransportException ex = new AuditFlowTransportException("transport failed", cause);

        assertEquals(-1, ex.statusCode());
        assertSame(cause, ex.getCause());
        assertNull(ex.errorResponse());
    }
}
