package io.labs64.audit.broker;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.labs64.audit.config.ConsumerHealthIndicator;
import io.labs64.audit.service.AuditService;

/**
 * Handles one message of the ingest queue for whichever adapter consumed it: routes it
 * ({@link AuditService}), retries a failure a few times in place, and finally moves the message to the
 * ingest DLQ ({@link Destination#ingestDlq()}) with the error in {@link #EXCEPTION_MESSAGE}. These are
 * the semantics the Spring Cloud Stream binder had ({@code maxAttempts=3}, {@code republishToDlq}), so
 * the legacy DLQ keeps its format.
 *
 * <p>Returns normally when the message may be removed from the ingest queue (routed, or stored in the
 * DLQ). Throws when it must stay: the DLQ publish failed, or the pod is shutting down.</p>
 */
@Component
public class IngestConsumer {

    private static final Logger logger = LoggerFactory.getLogger(IngestConsumer.class);

    /** Header of an ingest DLQ entry: why routing failed (same name the binder used). */
    public static final String EXCEPTION_MESSAGE = "x-exception-message";

    private final AuditService auditService;
    private final MessageBroker broker;
    private final ConsumerHealthIndicator consumerHealth;
    private final int maxAttempts;
    private final Duration initialBackoff;

    public IngestConsumer(AuditService auditService, MessageBroker broker, ConsumerHealthIndicator consumerHealth,
                          @Value("${auditflow.ingest.retry.max-attempts:3}") int maxAttempts,
                          @Value("${auditflow.ingest.retry.initial-backoff:PT1S}") Duration initialBackoff) {
        this.auditService = auditService;
        this.broker = broker;
        this.consumerHealth = consumerHealth;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initialBackoff = initialBackoff;
    }

    /**
     * @param redelivered the broker delivered this message before without it being removed (an
     *                    earlier attempt died): see {@link AuditService#processAuditEvent(String, boolean)}
     */
    public void consume(BrokerMessage message, boolean redelivered) {
        String body = message.bodyAsString();
        RuntimeException last = null;
        long backoff = initialBackoff.toMillis();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                auditService.processAuditEvent(body, redelivered);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (consumerHealth.isShutdownRequested()) {
                    throw e; // leave it on the queue for another pod
                }
                if (attempt < maxAttempts && !sleep(backoff)) {
                    throw e;
                }
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
        String reason = last.getCause() != null && last.getCause().getMessage() != null
                ? last.getCause().getMessage() : last.getMessage();
        BrokerMessage dead = new BrokerMessage(message.body(), message.headers(), message.messageId());
        dead.headers().put(EXCEPTION_MESSAGE, reason == null ? last.getClass().getName() : reason);
        broker.publish(Destination.ingestDlq(), dead); // throws: the message stays on the ingest queue
        logger.warn("Ingest message moved to the ingest DLQ after {} attempt(s): {}", maxAttempts, reason);
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
