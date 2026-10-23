package io.labs64.audit.broker.sqs;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.labs64.audit.broker.Destination;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/**
 * Resolves destinations to queue URLs, cached. A queue that does not exist is created when
 * {@code create-queues} is on; one that exists is used as it is (its attributes, e.g. set by
 * Terraform, are never changed).
 */
class SqsQueues {

    private static final Logger logger = LoggerFactory.getLogger(SqsQueues.class);

    private final SqsClient sqs;
    private final SqsQueueNames names;
    private final SqsBrokerProperties properties;
    private final Map<String, String> urls = new ConcurrentHashMap<>();

    SqsQueues(SqsClient sqs, SqsBrokerProperties properties) {
        this.sqs = sqs;
        this.names = new SqsQueueNames(properties.getQueuePrefix());
        this.properties = properties;
    }

    String name(Destination destination) {
        return names.name(destination);
    }

    /** Resolved once per queue: concurrent callers wait for the first resolution (and creation). */
    String url(Destination destination) {
        return urls.computeIfAbsent(names.name(destination), this::resolve);
    }

    private String resolve(String name) {
        try {
            return sqs.getQueueUrl(r -> r.queueName(name)).queueUrl();
        } catch (QueueDoesNotExistException e) {
            if (!properties.isCreateQueues()) {
                throw new IllegalStateException("SQS queue '" + name + "' does not exist and "
                        + "auditflow.broker.sqs.create-queues is false", e);
            }
            logger.info("Creating SQS queue '{}'", name);
            // Idempotent: a concurrent create of the same queue with the same attributes returns its URL.
            return sqs.createQueue(r -> r.queueName(name).attributes(Map.of(
                    QueueAttributeName.MESSAGE_RETENTION_PERIOD,
                    String.valueOf(properties.getMessageRetention().toSeconds()),
                    QueueAttributeName.SQS_MANAGED_SSE_ENABLED, "true"))).queueUrl();
        }
    }
}
