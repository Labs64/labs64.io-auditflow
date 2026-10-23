package io.labs64.audit.service;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.MessageBroker;
import io.labs64.audit.delivery.BrokerPublishException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes events that cannot be safely evaluated (e.g. unparseable JSON) to a dedicated
 * quarantine destination instead of silently matching every pipeline (fail-closed).
 */
@Service
public class QuarantineService {

    private static final Logger logger = LoggerFactory.getLogger(QuarantineService.class);
    public static final String REASON_HEADER = "x-quarantine-reason";

    private final MessageBroker broker;
    private final Counter quarantinedCounter;

    public QuarantineService(MessageBroker broker, MeterRegistry meterRegistry) {
        this.broker = broker;
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
        BrokerMessage message = BrokerMessage.of(rawMessage).withHeader(REASON_HEADER, reason);
        broker.publish(Destination.quarantine(), message);
        quarantinedCounter.increment();
    }
}
