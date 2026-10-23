package io.labs64.audit.broker.sqs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.labs64.audit.broker.BrokerMessage;
import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.IngestConsumer;
import io.labs64.audit.broker.MessageBroker;
import io.labs64.audit.delivery.DeliveryWorker;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The two consumer stages on SQS: long-polling threads on the ingest queue (each message to
 * {@link IngestConsumer}) and on the delivery queue (batches to {@link DeliveryWorker}). A message is
 * deleted only after it was handled; anything else makes it visible again, now (requeue) or after a
 * short backoff (a failing ingest message).
 *
 * <p>Started and stopped with the application context; a stop lets each thread finish what it holds
 * and releases what it received but did not start.</p>
 */
class SqsConsumers implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(SqsConsumers.class);

    /** Delivery settings shared with the RabbitMQ adapter ({@code auditflow.delivery.*}). */
    record DeliverySettings(boolean enabled, int concurrency, int batchSize, Duration receiveTimeout, Duration batchWindow) {
    }

    private static final long SHUTDOWN_MILLIS = 25_000;
    /** A message that is not due yet comes back this early at most, to be parked again or worked on. */
    private static final long NOT_BEFORE_SLACK_MILLIS = 1_000;

    private final SqsMessageBroker broker;
    private final SqsBrokerProperties properties;
    private final IngestConsumer ingestConsumer;
    private final DeliveryWorker deliveryWorker;
    private final int ingestConcurrency;
    private final DeliverySettings delivery;

    // Package-private: tests drive pollIngest/pollDelivery directly, without the threads.
    volatile boolean running;
    private ExecutorService executor;

    SqsConsumers(SqsMessageBroker broker, SqsBrokerProperties properties, IngestConsumer ingestConsumer,
                 DeliveryWorker deliveryWorker, int ingestConcurrency, DeliverySettings delivery) {
        this.broker = broker;
        this.properties = properties;
        this.ingestConsumer = ingestConsumer;
        this.deliveryWorker = deliveryWorker;
        this.ingestConcurrency = Math.max(1, ingestConcurrency);
        this.delivery = delivery;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        int deliveryThreads = delivery.enabled() ? Math.max(1, delivery.concurrency()) : 0;
        executor = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().name("sqs-consumer-", 1).factory());
        for (int i = 0; i < ingestConcurrency; i++) {
            executor.submit(() -> loop("ingest", this::pollIngest));
        }
        for (int i = 0; i < deliveryThreads; i++) {
            executor.submit(() -> loop("delivery", this::pollDelivery));
        }
        // Names only: resolving the queues here would make a briefly unreachable SQS fail the startup.
        logger.info("SQS consumers started: {} on {}, {} on {}", ingestConcurrency,
                broker.queueName(Destination.ingest()), deliveryThreads, broker.queueName(Destination.delivery()));
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.warn("SQS consumers did not stop within {} ms; interrupting them", SHUTDOWN_MILLIS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stop early, before the web server and the beans the consumers use. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    private void loop(String stage, Runnable poll) {
        long backoff = 0;
        while (running) {
            try {
                poll.run();
                backoff = 0;
            } catch (RuntimeException e) {
                backoff = Math.min(30_000, backoff == 0 ? 1_000 : backoff * 2);
                logger.warn("SQS {} consumer failed ({}); retrying in {} ms", stage, e.getMessage(), backoff);
                if (!sleep(backoff)) {
                    return;
                }
            }
        }
    }

    // ---- ingest ---------------------------------------------------------------------------------

    void pollIngest() {
        String url = broker.url(Destination.ingest());
        int visibility = seconds(properties.getIngestVisibilityTimeout());
        List<Message> received = broker.receive(url, SqsMessageBroker.BATCH, properties.waitTimeSeconds(), visibility);
        List<String> done = new ArrayList<>();
        List<String> release = new ArrayList<>();
        try {
            for (Message m : received) {
                if (!running) {
                    release.add(m.receiptHandle());
                    continue;
                }
                SqsCodec.Received r = SqsCodec.decode(m);
                try {
                    ingestConsumer.consume(r.message(), r.receiveCount() > 1);
                    done.add(r.receiptHandle());
                } catch (RuntimeException e) {
                    // Back on the queue: at once when stopping, else after a growing pause.
                    int retryIn = running ? Math.min(60, 5 * r.receiveCount()) : 0;
                    broker.changeVisibility(url, List.of(r.receiptHandle()), retryIn);
                }
            }
        } finally {
            broker.delete(url, done);
            broker.changeVisibility(url, release, 0);
        }
    }

    // ---- delivery -------------------------------------------------------------------------------

    void pollDelivery() {
        String url = broker.url(Destination.delivery());
        List<SqsCodec.Received> batch = gather(url);
        if (batch.isEmpty()) {
            return;
        }
        if (!running) {
            broker.changeVisibility(url, handles(batch), 0);
            return;
        }
        long now = broker.now();
        List<SqsCodec.Received> early = new ArrayList<>();
        List<SqsCodec.Received> ready = new ArrayList<>();
        for (SqsCodec.Received r : batch) {
            (r.notBefore() > now + NOT_BEFORE_SLACK_MILLIS ? early : ready).add(r);
        }
        if (!early.isEmpty()) {
            park(url, early, now);
        }
        if (ready.isEmpty()) {
            return;
        }
        List<BrokerMessage> messages = ready.stream().map(SqsCodec.Received::message).toList();
        try {
            deliveryWorker.process(messages);
        } catch (RuntimeException e) {
            logger.error("Could not settle a delivery batch of {} message(s); requeueing it: {}",
                    ready.size(), e.getMessage(), e);
            broker.changeVisibility(url, handles(ready), 0);
            return;
        }
        broker.delete(url, handles(ready));
    }

    /** Up to batch-size messages: wait for the first, then take what arrives within the receive timeout. */
    private List<SqsCodec.Received> gather(String url) {
        int visibility = seconds(properties.getDeliveryVisibilityTimeout());
        int batchSize = Math.max(1, delivery.batchSize());
        List<SqsCodec.Received> batch = new ArrayList<>();
        for (Message m : broker.receive(url, batchSize, properties.waitTimeSeconds(), visibility)) {
            batch.add(SqsCodec.decode(m));
        }
        if (batch.isEmpty()) {
            return batch;
        }
        long started = System.currentTimeMillis();
        long window = delivery.batchWindow().toMillis();
        int wait = (int) Math.max(0, Math.min(20, (delivery.receiveTimeout().toMillis() + 999) / 1000));
        while (running && batch.size() < batchSize && (window <= 0 || System.currentTimeMillis() - started < window)) {
            List<Message> more = broker.receive(url, batchSize - batch.size(), wait, visibility);
            if (more.isEmpty()) {
                break;
            }
            for (Message m : more) {
                batch.add(SqsCodec.decode(m));
            }
        }
        return batch;
    }

    /** A delivery delayed beyond SQS's 15-minute limit: send it on for the rest of its delay. */
    private void park(String url, List<SqsCodec.Received> early, long now) {
        List<MessageBroker.Outgoing> out = new ArrayList<>(early.size());
        for (SqsCodec.Received r : early) {
            out.add(new MessageBroker.Outgoing(Destination.delay(Duration.ofMillis(r.notBefore() - now)), r.message()));
        }
        List<String> results = broker.publishEach(out);
        List<String> parked = new ArrayList<>();
        List<String> retry = new ArrayList<>();
        for (int i = 0; i < early.size(); i++) {
            (results.get(i) == null ? parked : retry).add(early.get(i).receiptHandle());
        }
        broker.delete(url, parked);
        broker.changeVisibility(url, retry, 30);
    }

    private static List<String> handles(List<SqsCodec.Received> received) {
        return received.stream().map(SqsCodec.Received::receiptHandle).toList();
    }

    private static int seconds(Duration d) {
        return (int) Math.max(1, Math.min(43_200, d.toSeconds()));
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
