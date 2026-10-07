package io.labs64.audit.subscriber;

import io.labs64.audit.service.AuditService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class AuditSubscriberService {

    private static final Logger logger = LoggerFactory.getLogger(AuditSubscriberService.class);

    private final AuditService auditService;

    public AuditSubscriberService(AuditService auditService) {
        this.auditService = auditService;
    }

    @PostConstruct
    public void init() {
        logger.info("AuditSubscriberService initialized. Ready to receive audit messages");
    }

    /**
     * The broker's redelivered flag travels with the message: it tells the router that an earlier
     * delivery was never acknowledged (see {@link AuditService#processAuditEvent(String, boolean)}).
     */
    @Bean
    public Consumer<Message<String>> audit() {
        return message -> auditService.processAuditEvent(message.getPayload(),
                Boolean.TRUE.equals(message.getHeaders().get(AmqpHeaders.REDELIVERED)));
    }

}
