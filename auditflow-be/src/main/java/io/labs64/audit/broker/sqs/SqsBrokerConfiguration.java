package io.labs64.audit.broker.sqs;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import io.labs64.audit.broker.Destination;
import io.labs64.audit.broker.IngestConsumer;
import io.labs64.audit.delivery.DeliveryWorker;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * The Amazon SQS adapter ({@code auditflow.broker.type=sqs}): an SQS client from the AWS SDK default
 * credentials chain, {@link SqsMessageBroker}, the consumers of both stages, and a health indicator.
 * Spring Boot's RabbitMQ auto-configuration is switched off in this mode
 * ({@code BrokerEnvironmentPostProcessor}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "auditflow.broker.type", havingValue = SqsMessageBroker.TYPE)
@EnableConfigurationProperties(SqsBrokerProperties.class)
public class SqsBrokerConfiguration {

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(SqsBrokerProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .httpClientBuilder(ApacheHttpClient.builder().maxConnections(Math.max(10, properties.getMaxConnections())));
        if (StringUtils.hasText(properties.getRegion())) {
            builder.region(Region.of(properties.getRegion()));
        }
        if (StringUtils.hasText(properties.getEndpoint())) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    @Bean
    public SqsMessageBroker sqsMessageBroker(SqsClient sqsClient, SqsBrokerProperties properties,
                                             @Value("${auditflow.broker.confirm-timeout:PT5S}") Duration sendTimeout) {
        return new SqsMessageBroker(sqsClient, new SqsQueues(sqsClient, properties), properties, sendTimeout);
    }

    @Bean
    SqsConsumers sqsConsumers(SqsMessageBroker broker, SqsBrokerProperties properties, IngestConsumer ingestConsumer,
                              DeliveryWorker deliveryWorker,
                              @Value("${auditflow.ingest.concurrency:8}") int ingestConcurrency,
                              @Value("${auditflow.delivery.enabled:true}") boolean deliveryEnabled,
                              @Value("${auditflow.delivery.concurrency:4}") int deliveryConcurrency,
                              @Value("${auditflow.delivery.batch-size:50}") int batchSize,
                              @Value("${auditflow.delivery.batch-receive-timeout:PT1S}") Duration receiveTimeout,
                              @Value("${auditflow.delivery.batch-window:PT0S}") Duration batchWindow) {
        return new SqsConsumers(broker, properties, ingestConsumer, deliveryWorker, ingestConcurrency,
                new SqsConsumers.DeliverySettings(deliveryEnabled, deliveryConcurrency, batchSize, receiveTimeout,
                        batchWindow));
    }

    /** {@code sqs} in /actuator/health: the delivery queue is reachable, with its depth. */
    @Bean
    public HealthIndicator sqsHealthIndicator(SqsClient sqsClient, SqsMessageBroker broker) {
        return () -> {
            try {
                String url = broker.url(Destination.delivery());
                var attributes = sqsClient.getQueueAttributes(r -> r.queueUrl(url).attributeNames(
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED)).attributesAsStrings();
                return Health.up()
                        .withDetail("queue", broker.queueName(Destination.delivery()))
                        .withDetails(attributes)
                        .build();
            } catch (RuntimeException e) {
                return Health.down().withDetail("error", e.getMessage()).build();
            }
        };
    }
}
