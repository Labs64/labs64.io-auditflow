package io.labs64.audit.broker.sqs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequest;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchResponse;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResultEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;

/**
 * The SQS operations the adapter uses, in memory, with SQS semantics that matter to it: a received
 * message is invisible until deleted or its visibility timeout ends, a delayed message is invisible
 * until its delay ends, and every receive counts toward {@code ApproximateReceiveCount}. Time is the
 * fake's own clock ({@link #advance}), so tests never sleep.
 */
class FakeSqs implements SqsClient {

    static final class Stored {
        final String id = UUID.randomUUID().toString();
        final SendMessageBatchRequestEntry entry;
        long visibleAt;
        int receiveCount;
        String receiptHandle;

        Stored(SendMessageBatchRequestEntry entry, long visibleAt) {
            this.entry = entry;
            this.visibleAt = visibleAt;
        }
    }

    final Map<String, List<Stored>> queues = new LinkedHashMap<>();
    final Map<String, Map<QueueAttributeName, String>> created = new LinkedHashMap<>();
    final List<SendMessageBatchRequest> sends = new ArrayList<>();
    final AtomicInteger receives = new AtomicInteger();
    long now = 1_000_000;
    /** Entry ids (in the next batch send) SQS should reject. */
    final List<String> rejectIds = new ArrayList<>();

    FakeSqs withQueue(String name) {
        queues.put(name, new ArrayList<>());
        return this;
    }

    void advance(long millis) {
        now += millis;
    }

    List<Stored> stored(String name) {
        return queues.get(name);
    }

    private static String url(String name) {
        return "https://sqs.test/000000000000/" + name;
    }

    private List<Stored> queue(String url) {
        List<Stored> q = queues.get(url.substring(url.lastIndexOf('/') + 1));
        if (q == null) {
            throw QueueDoesNotExistException.builder().message("no queue " + url).build();
        }
        return q;
    }

    @Override
    public synchronized GetQueueUrlResponse getQueueUrl(GetQueueUrlRequest request) {
        if (!queues.containsKey(request.queueName())) {
            throw QueueDoesNotExistException.builder().message("no queue " + request.queueName()).build();
        }
        return GetQueueUrlResponse.builder().queueUrl(url(request.queueName())).build();
    }

    @Override
    public synchronized CreateQueueResponse createQueue(CreateQueueRequest request) {
        queues.putIfAbsent(request.queueName(), new ArrayList<>());
        created.put(request.queueName(), request.attributes());
        return CreateQueueResponse.builder().queueUrl(url(request.queueName())).build();
    }

    @Override
    public synchronized SendMessageBatchResponse sendMessageBatch(SendMessageBatchRequest request) {
        sends.add(request);
        List<Stored> q = queue(request.queueUrl());
        SendMessageBatchResponse.Builder response = SendMessageBatchResponse.builder();
        List<SendMessageBatchResultEntry> ok = new ArrayList<>();
        List<BatchResultErrorEntry> failed = new ArrayList<>();
        for (SendMessageBatchRequestEntry e : request.entries()) {
            if (rejectIds.remove(e.id())) {
                failed.add(BatchResultErrorEntry.builder().id(e.id()).code("InternalError").message("boom").build());
                continue;
            }
            int delay = e.delaySeconds() == null ? 0 : e.delaySeconds();
            q.add(new Stored(e, now + delay * 1000L));
            ok.add(SendMessageBatchResultEntry.builder().id(e.id()).build());
        }
        return response.successful(ok).failed(failed).build();
    }

    @Override
    public synchronized ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
        receives.incrementAndGet();
        List<Message> out = new ArrayList<>();
        int max = request.maxNumberOfMessages() == null ? 1 : request.maxNumberOfMessages();
        for (Stored s : queue(request.queueUrl())) {
            if (out.size() >= max) {
                break;
            }
            if (s.visibleAt > now) {
                continue;
            }
            s.receiveCount++;
            s.receiptHandle = s.id + "#" + s.receiveCount;
            s.visibleAt = now + request.visibilityTimeout() * 1000L;
            out.add(Message.builder()
                    .messageId(s.id)
                    .receiptHandle(s.receiptHandle)
                    .body(s.entry.messageBody())
                    .messageAttributes(s.entry.messageAttributes())
                    .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, String.valueOf(s.receiveCount)))
                    .build());
        }
        return ReceiveMessageResponse.builder().messages(out).build();
    }

    @Override
    public synchronized DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
        queue(request.queueUrl()).removeIf(s -> request.receiptHandle().equals(s.receiptHandle));
        return DeleteMessageResponse.builder().build();
    }

    @Override
    public synchronized DeleteMessageBatchResponse deleteMessageBatch(DeleteMessageBatchRequest request) {
        List<Stored> q = queue(request.queueUrl());
        request.entries().forEach(e -> q.removeIf(s -> e.receiptHandle().equals(s.receiptHandle)));
        return DeleteMessageBatchResponse.builder().successful(request.entries().stream()
                .map(e -> DeleteMessageBatchResultEntry.builder().id(e.id()).build()).toList()).build();
    }

    @Override
    public synchronized ChangeMessageVisibilityBatchResponse changeMessageVisibilityBatch(
            ChangeMessageVisibilityBatchRequest request) {
        List<Stored> q = queue(request.queueUrl());
        request.entries().forEach(e -> q.stream().filter(s -> e.receiptHandle().equals(s.receiptHandle))
                .forEach(s -> s.visibleAt = now + e.visibilityTimeout() * 1000L));
        return ChangeMessageVisibilityBatchResponse.builder().build();
    }

    @Override
    public synchronized GetQueueAttributesResponse getQueueAttributes(GetQueueAttributesRequest request) {
        long visible = queue(request.queueUrl()).stream().filter(s -> s.visibleAt <= now).count();
        return GetQueueAttributesResponse.builder()
                .attributes(Map.of(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, String.valueOf(visible))).build();
    }

    @Override
    public String serviceName() {
        return "sqs";
    }

    @Override
    public void close() {
    }
}
