package io.labs64.audit.exception;

import io.labs64.audit.controller.AuditEventController;
import io.labs64.audit.publisher.AuditPublisherService;
import io.labs64.audit.tenant.TenantGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @Mock
    private AuditPublisherService publisherService;

    @Mock
    private TenantGate tenantGate;

    @BeforeEach
    void setup() {
        AuditEventController controller = new AuditEventController(publisherService, tenantGate);
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void badUuidReturns400WithFieldMessage() throws Exception {
        String badPayload = """
                {
                  "eventId": "not-a-uuid",
                  "sourceSystem": "test",
                  "eventType": "test.event",
                  "timestamp": "2026-07-03T10:00:00Z"
                }
                """;

        mockMvc.perform(post("/audit/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid value for field 'eventId'"))
                .andExpect(jsonPath("$.message").value(not(containsString("InvalidFormatException"))))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        String badPayload = "{ malformed: json ";

        mockMvc.perform(post("/audit/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        mockMvc.perform(get("/audit/publish"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        String payload = """
                {
                  "eventId": "11111111-1111-1111-1111-111111111111",
                  "sourceSystem": "test",
                  "eventType": "test.event"
                }
                """;

        mockMvc.perform(post("/audit/publish")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(payload))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    private static final String VALID_PAYLOAD = """
            {
              "sourceSystem": "test",
              "eventType": "test.event",
              "tenantId": "acme"
            }
            """;

    @Test
    void notProvisionedTenantReturns403() throws Exception {
        doThrow(new TenantNotProvisionedException("Tenant 'acme' is not provisioned"))
                .when(tenantGate).check(any());

        mockMvc.perform(post("/audit/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_NOT_PROVISIONED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void disabledTenantReturns403() throws Exception {
        doThrow(new TenantDisabledException("Tenant 'acme' is disabled"))
                .when(tenantGate).check(any());

        mockMvc.perform(post("/audit/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_DISABLED"));
    }

    @Test
    void rateLimitedTenantReturns429WithRetryAfter() throws Exception {
        doThrow(new TenantRateLimitedException("Tenant 'acme' exceeded its ingest rate limit", 1))
                .when(tenantGate).check(any());

        mockMvc.perform(post("/audit/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PAYLOAD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("TENANT_RATE_LIMITED"));
    }

}
