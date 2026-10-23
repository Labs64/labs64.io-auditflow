package io.labs64.audit.broker.sqs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.MessageBroker;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

/**
 * Amazon SQS adapter ({@code auditflow.broker.type=sqs}). Standard queues, one per destination of
 * {@link SqsQueueNames}; a successful {@code SendMessage} is the confirm (SQS stores the message
 * redundantly before it answers).
 *
 * <p>Differences to RabbitMQ the application relies on being handled here:</p>
 * <ul>
 *   <li><b>Delays</b> are per-message delivery delays on the delivery queue. SQS caps one at 15 minutes;
 *       a longer one is sent with the maximum and a {@link SqsCodec#NOT_BEFORE} header, and the delivery
 *       consumer parks it again until then ({@link SqsConsumers});</li>
 *   <li><b>Size</b>: 1 MiB per message ({@link #maxMessageBytes()}); ingest answers 413 above it;</li>
 *   <li><b>Retention</b>: at most 14 days, also for DLQs: replay or purge DLQ entries before then.</li>
 * </ul>
 */
public class SqsMessageBroker implements MessageBroker {

    private static final Logger logger = LoggerFactory.getLogger(SqsMessageBroker.class);

    public static final String TYPE = "sqs";

    /** SQS's per-message delivery delay limit. */
    static final Duration MAX_DELAY = Duration.ofMinutes(15);
    /** SQS's per-request entry limit for batch calls (send, delete, change visibility, receive). */
    static final int BATCH = 10;

    private final SqsClient sqs;
    private final SqsQueues queues;
    private final SqsBrokerProperties properties;
    private final Duration sendTimeout;
    private final LongSupplier clock;

    SqsMessageBroker(SqsClient sqs, SqsQueues queues, SqsBrokerProperties properties, Duration sendTimeout) {
        this(sqs, queues, properties, sendTimeout, System::currentTimeMillis);
    }

    SqsMessageBroker(SqsClient sqs, SqsQueues queues, SqsBrokerProperties properties, Duration sendTimeout,
                     LongSupplier clock) {
        this.sqs = sqs;
        this.queues = queues;
        this.properties = properties;
        this.sendTimeout = sendTimeout;
        this.clock = clock;
    }

    /** Epoch millis; the clock not-before times are measured with. */
    long now() {
        return clock.getAsLong();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int maxMessageBytes() {
        return Math.min(properties.getMaxMessageSize(), SqsBrokerProperties.SQS_MAX_MESSAGE_BYTES);
    }

    @Override
    public List<String> publishEach(List<Outgoing> messages) {
        String[] results = new String[messages.size()];
        Arrays.fill(results, "not sent");
        long now = now();
        Map<String, List<Integer>> byQueue = new LinkedHashMap<>();
        SqsCodec.Encoded[] encoded = new SqsCodec.Encoded[messages.size()];
        int[] delays = new int[messages.size()];
        for (int i = 0; i < messages.size(); i++) {
            Outgoing out = messages.get(i);
            String url;
            try {
                url = queues.url(out.destination());
            } catch (RuntimeException e) {
                results[i] = "queue unavailable: " + e.getMessage();
                continue;
            }
            long notBefore = 0;
            if (out.destination().kind() == Destination.Kind.DELAY) {
                Duration delay = out.destination().delay();
                delays[i] = (int) Math.min(MAX_DELAY.toSeconds(), Math.max(0, ceilSeconds(delay)));
                if (delay.compareTo(MAX_DELAY) > 0) {
                    notBefore = now + delay.toMillis();
                }
            }
            encoded[i] = SqsCodec.encode(out.message(), notBefore);
            if (encoded[i].size() > maxMessageBytes()) {
                results[i] = "message too large: " + encoded[i].size() + " bytes, SQS accepts " + maxMessageBytes();
                continue;
            }
            byQueue.computeIfAbsent(url, k -> new ArrayList<>()).add(i);
        }
        byQueue.forEach((url, indexes) -> {
            for (List<Integer> chunk : chunks(indexes, encoded)) {
                sendBatch(url, chunk, encoded, delays, results);
            }
        });
        return Arrays.asList(results);
    }

    private void sendBatch(String url, List<Integer> chunk, SqsCodec.Encoded[] encoded, int[] delays, String[] results) {
        List<SendMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
        for (int i : chunk) {
            SendMessageBatchRequestEntry.Builder entry = SendMessageBatchRequestEntry.builder()
                    .id(String.valueOf(i))
                    .messageBody(encoded[i].body())
                    .messageAttributes(encoded[i].attributes());
            if (delays[i] > 0) {
                entry.delaySeconds(delays[i]);
            }
            entries.add(entry.build());
        }
        try {
            SendMessageBatchResponse response = sqs.sendMessageBatch(r -> r.queueUrl(url).entries(entries)
                    .overrideConfiguration(o -> o.apiCallTimeout(sendTimeout)));
            response.successful().forEach(ok -> results[Integer.parseInt(ok.id())] = null);
            for (BatchResultErrorEntry failed : response.failed()) {
                results[Integer.parseInt(failed.id())] = "rejected by SQS: " + failed.code() + " " + failed.message();
            }
        } catch (SdkException e) {
            for (int i : chunk) {
                results[i] = "publish failed: " + e.getMessage();
            }
        }
    }

    /** At most {@link #BATCH} entries and {@link SqsBrokerProperties#SQS_MAX_MESSAGE_BYTES} per request. */
    private static List<List<Integer>> chunks(List<Integer> indexes, SqsCodec.Encoded[] encoded) {
        List<List<Integer>> chunks = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        int bytes = 0;
        for (int i : indexes) {
            int size = encoded[i].size();
            if (!current.isEmpty() && (current.size() == BATCH || bytes + size > SqsBrokerProperties.SQS_MAX_MESSAGE_BYTES)) {
                chunks.add(current);
                current = new ArrayList<>();
                bytes = 0;
            }
            current.add(i);
            bytes += size;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    @Override
    public void ensureTenantDlq(String tenantId) {
        queues.url(Destination.tenantDlq(tenantId));
    }

    /**
     * Receives with a short long-poll (which, unlike a short poll, asks every SQS server, so an empty
     * answer means nothing is visible) until the queue answers empty. Kept messages stay invisible for
     * {@code scan-visibility-timeout} meanwhile, so each is seen once, and are made visible again at the
     * end. A message seen twice means the scan outlasted that timeout: it stops there.
     */
    @Override
    public long scan(Destination queue, ScanVisitor visitor) {
        if (queue.kind() != Destination.Kind.TENANT_DLQ && queue.kind() != Destination.Kind.INGEST_DLQ) {
            throw new IllegalArgumentException("Not a scannable queue: " + queue.kind());
        }
        String url = queues.url(queue);
        int visibility = (int) Math.max(1, properties.getScanVisibilityTimeout().toSeconds());
        Set<String> seen = new HashSet<>();
        List<String> keep = new ArrayList<>();
        long counted = 0;
        try {
            boolean wrapped = false;
            while (!wrapped) {
                List<Message> received = receive(url, BATCH, 1, visibility);
                if (received.isEmpty()) {
                    break;
                }
                for (Message m : received) {
                    if (!seen.add(m.messageId())) {
                        keep.add(m.receiptHandle());
                        wrapped = true;
                        continue;
                    }
                    ScanAction action;
                    try {
                        action = visitor.visit(SqsCodec.decode(m).message());
                    } catch (RuntimeException e) {
                        keep.add(m.receiptHandle());
                        throw e;
                    } catch (Exception e) {
                        keep.add(m.receiptHandle());
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                    if (action == ScanAction.ACK_COUNTED) {
                        sqs.deleteMessage(r -> r.queueUrl(url).receiptHandle(m.receiptHandle()));
                        counted++;
                    } else {
                        keep.add(m.receiptHandle());
                        if (action == ScanAction.KEEP_COUNTED) {
                            counted++;
                        }
                    }
                }
            }
        } finally {
            changeVisibility(url, keep, 0);
        }
        return counted;
    }

    // ---- used by the consumers ------------------------------------------------------------------

    String url(Destination destination) {
        return queues.url(destination);
    }

    String queueName(Destination destination) {
        return queues.name(destination);
    }

    List<Message> receive(String url, int max, int waitSeconds, int visibilitySeconds) {
        return sqs.receiveMessage(r -> r.queueUrl(url)
                .maxNumberOfMessages(Math.max(1, Math.min(BATCH, max)))
                .waitTimeSeconds(waitSeconds)
                .visibilityTimeout(visibilitySeconds)
                .messageAttributeNames("All")
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
    }

    /** Remove settled messages. A failure only means they come back once more (deliveries are idempotent). */
    void delete(String url, List<String> receiptHandles) {
        for (int from = 0; from < receiptHandles.size(); from += BATCH) {
            List<String> chunk = receiptHandles.subList(from, Math.min(receiptHandles.size(), from + BATCH));
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                entries.add(DeleteMessageBatchRequestEntry.builder().id(String.valueOf(i)).receiptHandle(chunk.get(i)).build());
            }
            try {
                var response = sqs.deleteMessageBatch(r -> r.queueUrl(url).entries(entries));
                if (!response.failed().isEmpty()) {
                    logger.warn("SQS did not delete {} settled message(s) of {}; they will be redelivered and skipped",
                            response.failed().size(), url);
                }
            } catch (SdkException e) {
                logger.warn("Could not delete {} settled message(s) of {}; they will be redelivered and skipped: {}",
                        chunk.size(), url, e.getMessage());
            }
        }
    }

    /** Make messages visible again after {@code seconds} (0 = now). Best effort: they reappear anyway. */
    void changeVisibility(String url, List<String> receiptHandles, int seconds) {
        for (int from = 0; from < receiptHandles.size(); from += BATCH) {
            List<String> chunk = receiptHandles.subList(from, Math.min(receiptHandles.size(), from + BATCH));
            List<ChangeMessageVisibilityBatchRequestEntry> entries = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                entries.add(ChangeMessageVisibilityBatchRequestEntry.builder().id(String.valueOf(i))
                        .receiptHandle(chunk.get(i)).visibilityTimeout(seconds).build());
            }
            try {
                sqs.changeMessageVisibilityBatch(r -> r.queueUrl(url).entries(entries));
            } catch (SdkException e) {
                logger.warn("Could not release {} message(s) of {}; they reappear after their visibility timeout: {}",
                        chunk.size(), url, e.getMessage());
            }
        }
    }

    private static long ceilSeconds(Duration d) {
        return d.toNanosPart() > 0 ? d.toSeconds() + 1 : d.toSeconds();
    }
}
