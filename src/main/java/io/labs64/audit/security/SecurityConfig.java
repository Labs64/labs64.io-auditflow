package io.labs64.audit.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        "/public/**",
        "/actuator/**",
        "/v3/api-docs/**"
    };

    private static final String[] PROTECTED_PATHS = {
        "/api/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                    // public endpoints
                    .requestMatchers(PUBLIC_PATHS).permitAll()
                    // protected endpoints
                    .requestMatchers(PROTECTED_PATHS).authenticated()
                    // ...other requests
                    .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2ResourceServer -> oauth2ResourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

}
