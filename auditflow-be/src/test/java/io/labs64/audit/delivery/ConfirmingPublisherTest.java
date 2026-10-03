package io.labs64.audit.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class ConfirmingPublisherTest {

    private static final Message MSG = new Message("{}".getBytes(), new MessageProperties());

    /** A template whose broker answers each publish with {@code broker}. */
    private ConfirmingPublisher publisherWhere(Consumer<CorrelationData> broker) {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(inv -> {
            broker.accept(inv.getArgument(3));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        return new ConfirmingPublisher(template, Duration.ofMillis(200));
    }

    private static ConfirmingPublisher.Outgoing out() {
        return new ConfirmingPublisher.Outgoing("x", "k", MSG);
    }

    @Test
    void anAckedPublishIsStored() {
        ConfirmingPublisher p = publisherWhere(c -> c.getFuture().complete(new CorrelationData.Confirm(true, null)));
        assertNull(p.publishEach(List.of(out())).get(0));
    }

    @Test
    void aNackIsAFailure() {
        ConfirmingPublisher p = publisherWhere(c -> c.getFuture().complete(new CorrelationData.Confirm(false, "disk alarm")));
        assertTrue(p.publishEach(List.of(out())).get(0).contains("disk alarm"));
    }

    @Test
    void anUnroutableReturnIsAFailureEvenWhenAcked() {
        ConfirmingPublisher p = publisherWhere(c -> {
            c.setReturned(new ReturnedMessage(MSG, 312, "NO_ROUTE", "x", "k"));
            c.getFuture().complete(new CorrelationData.Confirm(true, null));
        });
        assertTrue(p.publishEach(List.of(out())).get(0).contains("unroutable"));
    }

    @Test
    void aMissingConfirmTimesOut() {
        ConfirmingPublisher p = publisherWhere(c -> { });
        assertTrue(p.publishEach(List.of(out())).get(0).contains("timeout"));
    }

    @Test
    void aSendExceptionFailsOnlyThatMessage() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doAnswer(inv -> {
            if ("bad".equals(inv.getArgument(1))) {
                throw new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("refused"));
            }
            ((CorrelationData) inv.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        ConfirmingPublisher p = new ConfirmingPublisher(template, Duration.ofMillis(200));

        List<String> results = p.publishEach(List.of(out(), new ConfirmingPublisher.Outgoing("x", "bad", MSG)));

        assertNull(results.get(0));
        assertTrue(results.get(1).startsWith("publish failed"));
    }

    @Test
    void publishAllThrowsOnTheFirstFailure() {
        ConfirmingPublisher p = publisherWhere(c -> c.getFuture().complete(new CorrelationData.Confirm(false, "nope")));
        BrokerPublishException e = assertThrows(BrokerPublishException.class, () -> p.publishAll(List.of(out())));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void confirmsAreAwaitedTogetherWithinOneTimeout() {
        ConfirmingPublisher p = publisherWhere(c -> { });
        long start = System.nanoTime();
        List<String> results = p.publishEach(List.of(out(), out(), out(), out()));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(4, results.size());
        assertTrue(millis < 600, "four missing confirms took " + millis + " ms; the deadline is shared");
    }
}
