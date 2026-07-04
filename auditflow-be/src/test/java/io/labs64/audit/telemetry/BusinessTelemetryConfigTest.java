package io.labs64.audit.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BusinessTelemetryConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BusinessTelemetryConfig.class));

    @Test
    void defaultsToOtelImplementation() {
        runner.run(ctx -> assertThat(ctx).getBean(BusinessTelemetry.class)
                .isInstanceOf(OtelBusinessTelemetry.class));
    }

    @Test
    void disabledSelectsNoop() {
        runner.withPropertyValues("labs64.telemetry.enabled=false")
                .run(ctx -> assertThat(ctx).getBean(BusinessTelemetry.class)
                        .isInstanceOf(NoopBusinessTelemetry.class));
    }

    @Test
    void otelImplementationIsSafeWithoutAgent() {
        // Without the Java Agent, opentelemetry-api returns no-op tracers/spans:
        // calls must never throw.
        BusinessTelemetry telemetry = new OtelBusinessTelemetry();
        assertThatCode(() -> {
            telemetry.auditEventReceived("evt-1", "user.login");
            telemetry.pipelineCompleted("pipeline-a", "SUCCESS");
        }).doesNotThrowAnyException();
    }

    @Test
    void noopIsSafe() {
        BusinessTelemetry telemetry = new NoopBusinessTelemetry();
        assertThatCode(() -> {
            telemetry.auditEventReceived(null, null);
            telemetry.pipelineCompleted(null, null);
        }).doesNotThrowAnyException();
    }
}
