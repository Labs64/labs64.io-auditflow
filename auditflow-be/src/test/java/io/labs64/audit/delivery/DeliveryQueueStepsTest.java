package io.labs64.audit.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.InMemoryBroker;

/** A delivery of a pipeline with steps keeps where it stands on every re-publish, so no finished step runs twice. */
class DeliveryQueueStepsTest {

    private static final String CONTEXT = "{\"original\":{\"key\":\"k1\"}}";

    private static BrokerMessage resumedAtStepTwo() {
        BrokerMessage m = DeliveryHeaders.newDelivery("{\"eventId\":\"e1\",\"tenantId\":\"acme\"}", "acme", "archive", "e1", 1L);
        m.headers().put(DeliveryHeaders.STEPS_DONE, 1);
        m.headers().put(DeliveryHeaders.CONTEXT, CONTEXT);
        return m;
    }

    private static void assertProgressKept(BrokerMessage copy) {
        assertEquals(1, DeliveryHeaders.intHeader(copy, DeliveryHeaders.STEPS_DONE));
        assertEquals(CONTEXT, DeliveryHeaders.string(copy, DeliveryHeaders.CONTEXT));
    }

    @Test
    void aDlqReplayResumesAtTheFailedStep() {
        InMemoryBroker broker = new InMemoryBroker();
        new DeliveryQueue(broker).replay(resumedAtStepTwo());
        assertProgressKept(broker.published().get(0).message());
    }

    @Test
    void aDeferAndARetryKeepTheProgress() {
        InMemoryBroker broker = new InMemoryBroker();
        DeliveryQueue queue = new DeliveryQueue(broker);
        queue.defer(resumedAtStepTwo());
        queue.retryLater(resumedAtStepTwo(), 2, "down", Duration.ofSeconds(5));
        assertProgressKept(broker.published().get(0).message());
        assertProgressKept(broker.published().get(1).message());
    }
}
