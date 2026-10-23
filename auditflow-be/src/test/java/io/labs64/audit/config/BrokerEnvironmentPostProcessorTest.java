package io.labs64.audit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class BrokerEnvironmentPostProcessorTest {

    private final BrokerEnvironmentPostProcessor processor = new BrokerEnvironmentPostProcessor();

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    private static List<String> excludes(StandardEnvironment environment) {
        return Binder.get(environment).bind(BrokerEnvironmentPostProcessor.EXCLUDE_PROPERTY, String[].class)
                .map(List::of).orElse(List.of());
    }

    @Test
    void rabbitmqKeepsBootsRabbitAutoConfiguration() {
        StandardEnvironment environment = environment(Map.of("auditflow.broker.type", "rabbitmq"));

        processor.postProcessEnvironment(environment, null);

        assertThat(excludes(environment)).isEmpty();
    }

    @Test
    void theDefaultIsRabbitmq() {
        StandardEnvironment environment = environment(Map.of());

        processor.postProcessEnvironment(environment, null);

        assertThat(excludes(environment)).isEmpty();
    }

    @Test
    void sqsExcludesBootsRabbitAutoConfigurationAndKeepsExistingExclusions() {
        // The Helm chart's no-Redis setup lists its own exclusions as an indexed list.
        StandardEnvironment environment = environment(Map.of("auditflow.broker.type", "sqs",
                "spring.autoconfigure.exclude[0]", "org.example.RedisAutoConfiguration"));

        processor.postProcessEnvironment(environment, null);

        assertThat(excludes(environment)).startsWith("org.example.RedisAutoConfiguration")
                .containsAll(BrokerEnvironmentPostProcessor.RABBIT_AUTO_CONFIGURATIONS);
    }
}
