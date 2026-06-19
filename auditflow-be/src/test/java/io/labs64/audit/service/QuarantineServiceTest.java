package io.labs64.audit.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuarantineServiceTest {

    @Mock
    private StreamBridge streamBridge;

    private SimpleMeterRegistry meterRegistry;
    private QuarantineService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new QuarantineService(streamBridge, meterRegistry);
    }

    @Test
    @DisplayName("quarantine sends the raw payload to the quarantine binding and counts it")
    void quarantineSendsAndCounts() {
        when(streamBridge.send(eq("quarantine-out-0"), any(Message.class))).thenReturn(true);

        service.quarantine("{bad json", "parse error");

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(streamBridge).send(eq("quarantine-out-0"), captor.capture());
        assertEquals("{bad json", captor.getValue().getPayload());
        assertEquals(1.0, meterRegistry.counter("auditflow.events.quarantined").count());
    }
}
