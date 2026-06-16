package io.labs64.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import reactor.core.publisher.Hooks;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditApplication {
    public static void main(String[] args) {
        // Bridge ThreadLocal-bound context (tracing, MDC/correlation-id) into the Reactor
        // context so it survives the reactive pipeline fan-out in AuditService.
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(AuditApplication.class, args);
    }
}
