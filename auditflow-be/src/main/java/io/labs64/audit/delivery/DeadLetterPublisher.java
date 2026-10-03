package io.labs64.audit.delivery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.stereotype.Component;

import io.labs64.audit.tenant.TenantIds;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Writes one DLQ entry per failed pipeline delivery into the tenant's own DLQ queue
 * ({@code labs64-audit-dlq.<tenant>}), declared on first use. Tenant operations therefore only ever
 * read their own queue. The publish is confirmed: a delivery is acked only after its DLQ entry is
 * stored, so nothing is lost between the delivery queue and the DLQ.
 */
@Component
public class DeadLetterPublisher {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final ConfirmingPublisher publisher;
    private final AmqpAdmin amqpAdmin;
    private final BrokerTopology topology;
    private final MeterRegistry meterRegistry;
    private final Set<String> declared = ConcurrentHashMap.newKeySet();

    public DeadLetterPublisher(ConfirmingPublisher publisher, AmqpAdmin amqpAdmin, BrokerTopology topology,
                               MeterRegistry meterRegistry) {
        this.publisher = publisher;
        this.amqpAdmin = amqpAdmin;
        this.topology = topology;
        this.meterRegistry = meterRegistry;
    }

    /** Make sure the tenant's DLQ queue exists (idempotent; cached after the first success). */
    public void ensureTenantDlq(String tenantId) {
        String tenant = TenantIds.resolve(tenantId);
        if (declared.contains(tenant)) {
            return;
        }
        for (Declarable d : topology.tenantDlq(tenant)) {
            if (d instanceof Queue q) {
                amqpAdmin.declareQueue(q);
            } else if (d instanceof Binding b) {
                amqpAdmin.declareBinding(b);
            }
        }
        declared.add(tenant);
    }

    public void deadLetter(Message delivery, String tenantId, DeadLetterReason reason, String error) {
        String tenant = TenantIds.resolve(tenantId);
        ensureTenantDlq(tenant);
        Message entry = DeliveryHeaders.copy(delivery, Map.of(
                DeliveryHeaders.TENANT, tenant,
                DeliveryHeaders.DLQ_REASON, reason.name(),
                DeliveryHeaders.DLQ_AT, System.currentTimeMillis(),
                DeliveryHeaders.LAST_ERROR, error == null ? "" : DeliveryHeaders.truncateError(error)));
        publisher.publish(BrokerTopology.DLX_EXCHANGE, tenant, entry);
        meterRegistry.counter("auditflow.delivery.deadlettered", "tenant", tenant,
                "pipeline", String.valueOf(DeliveryHeaders.string(delivery, DeliveryHeaders.PIPELINE)),
                "reason", reason.name()).increment();
        logger.warn("Dead-lettered delivery eventId={} pipeline={} tenant={} reason={} error={}",
                DeliveryHeaders.string(delivery, DeliveryHeaders.EVENT_ID),
                DeliveryHeaders.string(delivery, DeliveryHeaders.PIPELINE), tenant, reason, error);
    }
}
