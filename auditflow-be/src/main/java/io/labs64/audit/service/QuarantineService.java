package io.labs64.audit.service;

import java.nio.charset.StandardCharsets;

import io.labs64.audit.delivery.BrokerPublishException;
import io.labs64.audit.delivery.BrokerTopology;
import io.labs64.audit.delivery.ConfirmingPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Service;

/**
 * Routes events that cannot be safely evaluated (e.g. unparseable JSON) to a dedicated
 * quarantine destination instead of silently matching every pipeline (fail-closed).
 */
@Service
public class QuarantineService {

    private static final Logger logger = LoggerFactory.getLogger(QuarantineService.class);
    public static final String REASON_HEADER = "x-quarantine-reason";

    private final ConfirmingPublisher publisher;
    private final Counter quarantinedCounter;

    public QuarantineService(ConfirmingPublisher publisher, MeterRegistry meterRegistry) {
        this.publisher = publisher;
        this.quarantinedCounter = meterRegistry.counter("auditflow.events.quarantined");
    }

    /**
     * Send a problematic raw message to the quarantine queue for later inspection. Returns once the
     * broker confirmed it: the callers ack the original message right after, so an unconfirmed
     * quarantine copy would lose the event.
     *
     * @param rawMessage the original event payload, forwarded verbatim
     * @param reason     short human-readable reason (added as a header)
     * @throws BrokerPublishException if the broker did not store the message; the caller must not
     *                                ack the original (it is then redelivered)
     */
    public void quarantine(String rawMessage, String reason) {
        logger.warn("Quarantining audit event; reason='{}'", reason);
        Message message = MessageBuilder
                .withBody(rawMessage.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setHeader(REASON_HEADER, reason)
                .build();
        publisher.publish(BrokerTopology.QUARANTINE_EXCHANGE, BrokerTopology.QUARANTINE_ROUTING_KEY, message);
        quarantinedCounter.increment();
    }
}
