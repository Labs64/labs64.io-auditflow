package io.labs64.audit.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Keeps Spring Boot's RabbitMQ auto-configuration out when another broker is selected
 * ({@code auditflow.broker.type} other than {@code rabbitmq}). Otherwise Boot would still build a
 * RabbitMQ connection factory, and its health indicator would report the application DOWN for a
 * broker it does not use.
 *
 * <p>The exclusions are added to whatever {@code spring.autoconfigure.exclude} already lists (e.g.
 * the Redis auto-configurations of a no-Redis deployment), never replace it.</p>
 */
public class BrokerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String BROKER_TYPE_PROPERTY = "auditflow.broker.type";
    static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";
    static final String PROPERTY_SOURCE_NAME = "auditflowBrokerExclusions";

    static final List<String> RABBIT_AUTO_CONFIGURATIONS = List.of(
            "org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
            "org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration",
            "org.springframework.boot.amqp.autoconfigure.metrics.RabbitMetricsAutoConfiguration");

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment,
            final SpringApplication application) {
        String type = environment.getProperty(BROKER_TYPE_PROPERTY, "rabbitmq").trim();
        if (type.isEmpty() || "rabbitmq".equalsIgnoreCase(type)) {
            return;
        }
        Set<String> excludes = new LinkedHashSet<>(Binder.get(environment)
                .bind(EXCLUDE_PROPERTY, String[].class).map(List::of).orElse(List.of()));
        excludes.addAll(RABBIT_AUTO_CONFIGURATIONS);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME,
                Map.of(EXCLUDE_PROPERTY, String.join(",", new ArrayList<>(excludes)))));
    }

    @Override
    public int getOrder() {
        // After the config data (application.yml, profiles) is loaded, so the type and the existing
        // exclusions can be read.
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
