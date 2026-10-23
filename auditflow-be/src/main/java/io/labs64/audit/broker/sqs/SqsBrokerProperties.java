package io.labs64.audit.broker.sqs;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code auditflow.broker.sqs.*}: the Amazon SQS adapter ({@code auditflow.broker.type=sqs}).
 * Credentials and, unless set here, the region come from the AWS SDK default chain (IRSA / EKS Pod
 * Identity, environment, profile).
 */
@ConfigurationProperties(prefix = "auditflow.broker.sqs")
public class SqsBrokerProperties {

    /** SQS's own limit for one message (body and attributes) and for one batch request. */
    public static final int SQS_MAX_MESSAGE_BYTES = 1024 * 1024;

    /** AWS region; empty = the SDK default chain (AWS_REGION, set on EKS by IRSA / Pod Identity). */
    private String region;

    /** Endpoint override, e.g. {@code http://localhost:9324} for ElasticMQ or LocalStack; empty = AWS. */
    private String endpoint;

    /**
     * Prefix of every queue name: {@code <prefix>ingest}, {@code <prefix>ingest-dlq},
     * {@code <prefix>delivery}, {@code <prefix>quarantine}, {@code <prefix>dlq-<tenant>}.
     */
    private String queuePrefix = "labs64-audit-";

    /**
     * Create a queue that does not exist yet (needs {@code sqs:CreateQueue} on {@code <prefix>*}). Tenant
     * DLQs are always created on first use, so with {@code false} they must be created beforehand.
     */
    private boolean createQueues = true;

    /** Retention of the queues this application creates (SQS allows 60s..14 days). */
    private Duration messageRetention = Duration.ofDays(14);

    /** Long-poll wait of one receive (1..20 s). Longer = fewer empty receives, slower shutdown. */
    private Duration waitTime = Duration.ofSeconds(10);

    /** How long a received ingest message stays invisible before SQS hands it out again. */
    private Duration ingestVisibilityTimeout = Duration.ofMinutes(1);

    /**
     * How long a received delivery message stays invisible: must cover one whole batch (all sink calls
     * with their retries), or the batch is delivered again while it is still being worked on.
     */
    private Duration deliveryVisibilityTimeout = Duration.ofMinutes(10);

    /** How long a DLQ inspect/replay/purge holds the messages it has looked at. */
    private Duration scanVisibilityTimeout = Duration.ofMinutes(5);

    /** HTTP connections to SQS per pod (publishes from request threads + the consumer threads). */
    private int maxConnections = 100;

    /** Largest message accepted; at most {@link #SQS_MAX_MESSAGE_BYTES}, lower if the queues are. */
    private int maxMessageSize = SQS_MAX_MESSAGE_BYTES;

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getQueuePrefix() {
        return queuePrefix;
    }

    public void setQueuePrefix(String queuePrefix) {
        this.queuePrefix = queuePrefix;
    }

    public boolean isCreateQueues() {
        return createQueues;
    }

    public void setCreateQueues(boolean createQueues) {
        this.createQueues = createQueues;
    }

    public Duration getMessageRetention() {
        return messageRetention;
    }

    public void setMessageRetention(Duration messageRetention) {
        this.messageRetention = messageRetention;
    }

    public Duration getWaitTime() {
        return waitTime;
    }

    public void setWaitTime(Duration waitTime) {
        this.waitTime = waitTime;
    }

    public Duration getIngestVisibilityTimeout() {
        return ingestVisibilityTimeout;
    }

    public void setIngestVisibilityTimeout(Duration ingestVisibilityTimeout) {
        this.ingestVisibilityTimeout = ingestVisibilityTimeout;
    }

    public Duration getDeliveryVisibilityTimeout() {
        return deliveryVisibilityTimeout;
    }

    public void setDeliveryVisibilityTimeout(Duration deliveryVisibilityTimeout) {
        this.deliveryVisibilityTimeout = deliveryVisibilityTimeout;
    }

    public Duration getScanVisibilityTimeout() {
        return scanVisibilityTimeout;
    }

    public void setScanVisibilityTimeout(Duration scanVisibilityTimeout) {
        this.scanVisibilityTimeout = scanVisibilityTimeout;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxMessageSize() {
        return maxMessageSize;
    }

    public void setMaxMessageSize(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    /** Long-poll wait in whole seconds, clamped to what SQS accepts. */
    int waitTimeSeconds() {
        return (int) Math.max(1, Math.min(20, waitTime.toSeconds()));
    }
}
