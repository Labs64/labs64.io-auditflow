package io.labs64.auditflow.client;

import java.net.http.HttpHeaders;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Outcome of a successful publish: the resolved event id and server receipt time. */
public record PublishResult(UUID eventId, OffsetDateTime receivedAt, int httpStatus) {

    private static final String HEADER_EVENT_ID = "X-Audit-Event-Id";
    private static final String HEADER_RECEIVED_AT = "X-Audit-Received-At";

    public static PublishResult from(int httpStatus, HttpHeaders headers) {
        UUID eventId = headers.firstValue(HEADER_EVENT_ID).map(UUID::fromString).orElse(null);
        OffsetDateTime receivedAt = headers.firstValue(HEADER_RECEIVED_AT)
                .map(OffsetDateTime::parse)
                .orElse(null);
        return new PublishResult(eventId, receivedAt, httpStatus);
    }
}
