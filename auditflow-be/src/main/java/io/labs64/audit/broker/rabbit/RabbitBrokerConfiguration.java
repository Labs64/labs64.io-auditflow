package io.labs64.audit.broker.rabbit;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareBatchMessageListener;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.rabbitmq.client.Channel;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.IngestConsumer;
import io.labs64.audit.delivery.DeliveryWorker;

/**
 * The RabbitMQ adapter ({@code auditflow.broker.type=rabbitmq}, the default): Spring Boot's RabbitMQ
 * connection ({@code spring.rabbitmq.*}), the topology of {@link RabbitTopology}, and one listener
 * container per stage, both with manual acks.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auditflow.broker.type", havingValue = RabbitMessageBroker.TYPE, matchIfMissing = true)
public class RabbitBrokerConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(RabbitBrokerConfiguration.class);

    @Bean
    public RabbitTopology rabbitTopology(@Value("${auditflow.broker.queue-type:classic}") String queueType) {
        return new RabbitTopology(queueType);
    }

    @Bean
    public Declarables auditflowTopology(RabbitTopology topology) {
        return topology.declarables();
    }

    @Bean
    public RabbitMessageBroker rabbitMessageBroker(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
                                                   RabbitTopology topology,
                                                   @Value("${auditflow.broker.confirm-timeout:PT5S}") Duration confirmTimeout) {
        return new RabbitMessageBroker(new ConfirmingPublisher(rabbitTemplate, confirmTimeout), rabbitTemplate,
                amqpAdmin, topology);
    }

    /**
     * Router stage: one message at a time per consumer. Acked once {@link IngestConsumer} returned (routed,
     * or moved to the ingest DLQ); requeued when it threw.
     */
    @Bean
    public SimpleMessageListenerContainer ingestListenerContainer(
            ConnectionFactory connectionFactory, IngestConsumer ingestConsumer,
            @Value("${auditflow.ingest.concurrency:8}") int concurrency) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(RabbitTopology.INGEST_QUEUE);
        container.setConcurrentConsumers(Math.max(1, concurrency));
        container.setPrefetchCount(Math.max(1, concurrency));
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setShutdownTimeout(25_000);
        container.setMissingQueuesFatal(false);
        container.setObservationEnabled(true);
        container.setMessageListener((ChannelAwareMessageListener) (message, channel) ->
                onIngest(ingestConsumer, message, channel));
        return container;
    }

    static void onIngest(IngestConsumer ingestConsumer, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            ingestConsumer.consume(RabbitMessageBroker.fromAmqp(message),
                    Boolean.TRUE.equals(message.getMessageProperties().isRedelivered()));
        } catch (RuntimeException e) {
            channel.basicNack(tag, false, true);
            return;
        }
        channel.basicAck(tag, false);
    }

    @Bean
    public SimpleMessageListenerContainer deliveryListenerContainer(
            ConnectionFactory connectionFactory, DeliveryWorker worker,
            @Value("${auditflow.delivery.concurrency:4}") int concurrency,
            @Value("${auditflow.delivery.batch-size:50}") int batchSize,
            @Value("${auditflow.delivery.batch-receive-timeout:PT1S}") Duration receiveTimeout,
            @Value("${auditflow.delivery.batch-window:PT0S}") Duration batchWindow,
            @Value("${auditflow.delivery.enabled:true}") boolean enabled) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(RabbitTopology.DELIVERY_QUEUE);
        container.setConcurrentConsumers(Math.max(1, concurrency));
        container.setConsumerBatchEnabled(true);
        container.setBatchSize(Math.max(1, batchSize));
        container.setPrefetchCount(Math.max(1, batchSize));
        container.setReceiveTimeout(receiveTimeout.toMillis());
        // The receive timeout alone closes a batch after that much silence, so a steady trickle keeps one
        // open until it is full. The window is the hard bound from its first message (0 = off).
        container.setBatchReceiveTimeout(Math.max(0, batchWindow.toMillis()));
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setShutdownTimeout(25_000);
        container.setMissingQueuesFatal(false);
        container.setAutoStartup(enabled);
        container.setMessageListener((ChannelAwareBatchMessageListener) (messages, channel) ->
                onDeliveryBatch(worker, messages, channel));
        return container;
    }

    /** Settle every message of the batch, then ack them all; on a storage failure requeue them all. */
    static void onDeliveryBatch(DeliveryWorker worker, List<Message> messages, Channel channel) {
        if (messages.isEmpty()) {
            return;
        }
        long lastTag = messages.get(messages.size() - 1).getMessageProperties().getDeliveryTag();
        try {
            try {
                List<BrokerMessage> batch = messages.stream().map(RabbitMessageBroker::fromAmqp).toList();
                worker.process(batch);
            } catch (RuntimeException e) {
                logger.error("Could not settle a delivery batch of {} message(s); requeueing it: {}",
                        messages.size(), e.getMessage(), e);
                channel.basicNack(lastTag, true, true);
                return;
            }
            channel.basicAck(lastTag, true);
        } catch (IOException e) {
            // The channel is gone: the broker requeues every unacked message of this batch itself.
            throw new AmqpIOException(e);
        }
    }
}
