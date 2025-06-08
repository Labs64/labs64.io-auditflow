package io.labs64.audit.service;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class AuditSubscriberService {

    private static final Logger logger = LoggerFactory.getLogger(AuditSubscriberService.class);

    @Bean
    public Consumer<String> receive() {
        return message -> logger.info("Received message: {}", message);
    }

}
