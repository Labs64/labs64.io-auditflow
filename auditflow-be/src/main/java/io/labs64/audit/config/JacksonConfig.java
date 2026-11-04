package io.labs64.audit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import io.labs64.auditflow.model.AuditEvent;

@Configuration
public class JacksonConfig {

    @Bean
    ObjectMapper objectMapper() {
        return JsonMapper.builder()
            .findAndAddModules()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .addMixIn(AuditEvent.class, ReservedContext.class)
            .build();
    }

    /**
     * {@code context} is read from a publish (so ingest can refuse a publisher-set one) but never written:
     * the published event carries no {@code context} key, not even an empty one. Only a pipeline step
     * adds it, to the copy its next step's transformers see.
     */
    @JsonIgnoreProperties(value = "context", allowSetters = true)
    abstract static class ReservedContext {
    }
}
