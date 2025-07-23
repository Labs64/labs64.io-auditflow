package io.labs64.audit.subscriber;

import io.labs64.audit.service.AuditService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
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

    @Bean
    public Consumer<String> audit() {
        return message -> {
            auditService.processAuditEvent(message);
        };
    }

}
