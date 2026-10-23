package io.labs64.audit.broker.rabbit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;

import com.rabbitmq.client.GetResponse;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.MessageBroker;

/**
 * RabbitMQ adapter ({@code auditflow.broker.type=rabbitmq}): confirmed publishes through
 * {@link ConfirmingPublisher} to the exchanges of {@link RabbitTopology}, DLQ scans with
 * {@code basic.get} and manual acks on one channel.
 */
public class RabbitMessageBroker implements MessageBroker {

    public static final String TYPE = "rabbitmq";

    private final ConfirmingPublisher publisher;
    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final RabbitTopology topology;
    private final Set<String> declared = ConcurrentHashMap.newKeySet();
    private final DefaultMessagePropertiesConverter propertiesConverter = new DefaultMessagePropertiesConverter();

    public RabbitMessageBroker(ConfirmingPublisher publisher, RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
                               RabbitTopology topology) {
        this.publisher = publisher;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.topology = topology;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<String> publishEach(List<Outgoing> messages) {
        List<ConfirmingPublisher.Outgoing> out = new ArrayList<>(messages.size());
        for (Outgoing o : messages) {
            RabbitTopology.Address address = RabbitTopology.address(o.destination());
            out.add(new ConfirmingPublisher.Outgoing(address.exchange(), address.routingKey(), toAmqp(o.message())));
        }
        return publisher.publishEach(out);
    }

    @Override
    public void ensureTenantDlq(String tenantId) {
        if (declared.contains(tenantId)) {
            return;
        }
        for (Declarable d : topology.tenantDlq(tenantId)) {
            if (d instanceof Queue q) {
                amqpAdmin.declareQueue(q);
            } else if (d instanceof Binding b) {
                amqpAdmin.declareBinding(b);
            }
        }
        declared.add(tenantId);
    }

    /**
     * Kept messages are nacked back only at the end: unacked messages are not handed out again on the
     * same channel, so the loop terminates.
     */
    @Override
    public long scan(Destination queue, ScanVisitor visitor) {
        String queueName = RabbitTopology.queueName(queue);
        Long count = rabbitTemplate.execute(channel -> {
            long counted = 0;
            List<Long> keep = new ArrayList<>();
            try {
                GetResponse resp;
                while ((resp = channel.basicGet(queueName, false)) != null) {
                    long tag = resp.getEnvelope().getDeliveryTag();
                    ScanAction action;
                    try {
                        action = visitor.visit(fromGet(resp));
                    } catch (Exception e) {
                        keep.add(tag); // never leave it unacked on a cached channel: back to the queue
                        throw e;
                    }
                    if (action == ScanAction.ACK_COUNTED) {
                        channel.basicAck(tag, false);
                        counted++;
                    } else {
                        keep.add(tag);
                        if (action == ScanAction.KEEP_COUNTED) {
                            counted++;
                        }
                    }
                }
            } finally {
                for (long tag : keep) {
                    channel.basicNack(tag, false, true);
                }
            }
            return counted;
        });
        return count == null ? 0 : count;
    }

    static Message toAmqp(BrokerMessage message) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(message.messageId());
        message.headers().forEach(props::setHeader);
        return new Message(message.body(), props);
    }

    static BrokerMessage fromAmqp(Message message) {
        MessageProperties props = message.getMessageProperties();
        return new BrokerMessage(message.getBody(), props.getHeaders(), props.getMessageId());
    }

    private BrokerMessage fromGet(GetResponse resp) {
        MessageProperties props = propertiesConverter.toMessageProperties(resp.getProps(), resp.getEnvelope(),
                StandardCharsets.UTF_8.name());
        return new BrokerMessage(resp.getBody(), props.getHeaders(), props.getMessageId());
    }
}
