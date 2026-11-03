package io.labs64.audit.broker.rabbit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Refuses to start on RabbitMQ's well-known guest/guest. They are fine on a laptop and nowhere else, and AuditFlow has
 * no production mode to tell the two apart, so a local stack says so itself: the {@code local} profile and
 * docker-compose.yml set {@code auditflow.broker.allow-default-credentials}.
 */
@Component
@ConditionalOnProperty(name = "auditflow.broker.type", havingValue = "rabbitmq", matchIfMissing = true)
class DefaultBrokerCredentialsGuard {

    private static final String DEFAULT = "guest";

    DefaultBrokerCredentialsGuard(@Value("${spring.rabbitmq.username:}") String username,
            @Value("${spring.rabbitmq.password:}") String password,
            @Value("${auditflow.broker.allow-default-credentials:false}") boolean allowed) {
        if (!allowed && DEFAULT.equals(username) && DEFAULT.equals(password)) {
            throw new IllegalStateException("The RabbitMQ credentials are the default guest/guest. Set RABBITMQ_USERNAME "
                    + "and RABBITMQ_PASSWORD from a secret, or, on a local stack only, "
                    + "AUDITFLOW_BROKER_ALLOW_DEFAULT_CREDENTIALS=true.");
        }
    }
}
