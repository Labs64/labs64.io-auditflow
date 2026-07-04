package io.labs64.audit.telemetry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the BusinessTelemetry implementation. Enabled by default: without the Java Agent
 * the OTel implementation degrades to a no-op on its own, so the property exists only as an
 * explicit kill switch (labs64.telemetry.enabled=false).
 */
@Configuration
public class BusinessTelemetryConfig {

    @Bean
    @ConditionalOnProperty(name = "labs64.telemetry.enabled", havingValue = "true", matchIfMissing = true)
    public BusinessTelemetry otelBusinessTelemetry() {
        return new OtelBusinessTelemetry();
    }

    @Bean
    @ConditionalOnProperty(name = "labs64.telemetry.enabled", havingValue = "false")
    public BusinessTelemetry noopBusinessTelemetry() {
        return new NoopBusinessTelemetry();
    }
}
