package io.labs64.audit.delivery;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.MessageBroker;
import io.labs64.audit.tenant.TenantIds;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Writes one DLQ entry per failed pipeline delivery into the tenant's own DLQ, created on first use
 * by the broker adapter (RabbitMQ: {@code labs64-audit-dlq.<tenant>}; SQS: {@code <prefix>dlq-<tenant>}).
 * Tenant operations therefore only ever read their own queue. The publish is confirmed: a delivery
 * is acked only after its DLQ entry is stored, so nothing is lost between the delivery queue and the DLQ.
 */
@Component
public class DeadLetterPublisher {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final MessageBroker broker;
    private final MeterRegistry meterRegistry;

    public DeadLetterPublisher(MessageBroker broker, MeterRegistry meterRegistry) {
        this.broker = broker;
        this.meterRegistry = meterRegistry;
    }

    /** Make sure the tenant's DLQ exists (idempotent; cached after the first success). */
    public void ensureTenantDlq(String tenantId) {
        broker.ensureTenantDlq(TenantIds.resolve(tenantId));
    }

    public void deadLetter(BrokerMessage delivery, String tenantId, DeadLetterReason reason, String error) {
        String tenant = TenantIds.resolve(tenantId);
        broker.ensureTenantDlq(tenant);
        BrokerMessage entry = DeliveryHeaders.copy(delivery, Map.of(
                DeliveryHeaders.TENANT, tenant,
                DeliveryHeaders.DLQ_REASON, reason.name(),
                DeliveryHeaders.DLQ_AT, System.currentTimeMillis(),
                DeliveryHeaders.LAST_ERROR, error == null ? "" : DeliveryHeaders.truncateError(error)));
        broker.publish(Destination.tenantDlq(tenant), entry);
        meterRegistry.counter("auditflow.delivery.deadlettered", "tenant", tenant,
                "pipeline", String.valueOf(DeliveryHeaders.string(delivery, DeliveryHeaders.PIPELINE)),
                "reason", reason.name()).increment();
        logger.warn("Dead-lettered delivery eventId={} pipeline={} tenant={} reason={} error={}",
                DeliveryHeaders.string(delivery, DeliveryHeaders.EVENT_ID),
                DeliveryHeaders.string(delivery, DeliveryHeaders.PIPELINE), tenant, reason, error);
    }
}
