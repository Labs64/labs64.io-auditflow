package io.labs64.audit.v1.model;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the OpenAPI-generated models reflect the v1.x contract changes:
 * new optional AuditEvent fields, relaxed Geolocation coordinates, and the
 * UNAUTHORIZED error code. These assertions only compile/pass after the YAML
 * spec is updated and the models regenerated.
 */
class AuditEventSchemaTest {

    private static Validator validator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator();
        }
    }

    @Test
    void geolocationWithoutCoordinatesIsValid() {
        Geolocation geo = new Geolocation().countryCode("DE").city("Munich");
        assertTrue(validator().validate(geo).isEmpty(),
                "Geolocation with only descriptive fields must be valid (lat/lon now optional)");
    }

    @Test
    void auditEventExposesNewOptionalFields() {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2025-07-04T10:00:00Z");
        AuditEvent event = new AuditEvent()
                .eventType("user.login")
                .sourceSystem("auth-service")
                .correlationId("corr-1")
                .eventTime(occurredAt);
        assertEquals("corr-1", event.getCorrelationId());
        assertEquals(occurredAt, event.getEventTime());
    }

    @Test
    void auditEventWithoutOptionalFieldsIsValid() {
        AuditEvent event = new AuditEvent().eventType("user.login").sourceSystem("auth-service");
        assertTrue(validator().validate(event).isEmpty(),
                "AuditEvent with only required fields must be valid (eventTime/correlationId optional)");
    }

    @Test
    void errorCodeIncludesUnauthorized() {
        assertDoesNotThrow(() -> ErrorCode.valueOf("UNAUTHORIZED"));
    }
}
