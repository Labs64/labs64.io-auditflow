package io.labs64.audit.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * Routes events that cannot be safely evaluated (e.g. unparseable JSON) to a dedicated
 * quarantine destination instead of silently matching every pipeline (fail-closed).
 */
@Service
public class QuarantineService {

    private static final Logger logger = LoggerFactory.getLogger(QuarantineService.class);
    public static final String QUARANTINE_OUT = "quarantine-out-0";

    private final StreamBridge streamBridge;
    private final Counter quarantinedCounter;

    public QuarantineService(StreamBridge streamBridge, MeterRegistry meterRegistry) {
        this.streamBridge = streamBridge;
        this.quarantinedCounter = meterRegistry.counter("auditflow.events.quarantined");
    }

    /**
     * Send a problematic raw message to the quarantine queue for later inspection.
     *
     * @param rawMessage the original event payload, forwarded verbatim
     * @param reason     short human-readable reason (added as a header)
     */
    public void quarantine(String rawMessage, String reason) {
        logger.warn("Quarantining audit event; reason='{}'", reason);
        streamBridge.send(QUARANTINE_OUT, MessageBuilder
                .withPayload(rawMessage)
                .setHeader("x-quarantine-reason", reason)
                .build());
        quarantinedCounter.increment();
    }
}
