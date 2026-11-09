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
    void aReplayOfAnEntryWithUnreadableProgressStartsOver() {
        InMemoryBroker broker = new InMemoryBroker();
        BrokerMessage entry = resumedAtStepTwo();
        entry.headers().put(DeliveryHeaders.DLQ_REASON, DeadLetterReason.MALFORMED.name());

        new DeliveryQueue(broker).replay(entry);

        BrokerMessage copy = broker.published().get(0).message();
        assertEquals(null, copy.header(DeliveryHeaders.STEPS_DONE));
        assertEquals(null, copy.header(DeliveryHeaders.CONTEXT));
        assertEquals(io.labs64.audit.service.StepProgress.START, DeliveryHeaders.progress(copy, new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Test
    void progressThisCodeCouldNotHaveWrittenIsRefused() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        BrokerMessage noStepDone = resumedAtStepTwo();
        noStepDone.headers().put(DeliveryHeaders.STEPS_DONE, 0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DeliveryHeaders.progress(noStepDone, mapper));

        BrokerMessage tooLarge = resumedAtStepTwo();
        tooLarge.headers().put(DeliveryHeaders.CONTEXT, "{\"k\":\"" + "x".repeat(5000) + "\"}");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DeliveryHeaders.progress(tooLarge, mapper));

        assertEquals(1, DeliveryHeaders.progress(resumedAtStepTwo(), mapper).stepsDone());
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
