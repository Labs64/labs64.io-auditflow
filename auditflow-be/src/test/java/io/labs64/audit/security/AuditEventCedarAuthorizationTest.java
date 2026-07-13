package io.labs64.audit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import io.labs64.audit.controller.AuditEventController;
import io.labs64.auditflow.model.AuditEvent;
import io.labs64.authcontext.cedar.AuthorizationDecision;
import io.labs64.authcontext.cedar.AuthorizeInterceptor;
import io.labs64.authcontext.cedar.CedarAuthorizationService;
import io.labs64.authcontext.cedar.CedarProperties;
import io.labs64.authcontext.core.AuthContext;
import io.labs64.authcontext.core.AuthContextHolder;

/**
 * RFC-05 P4 fan-out: the REAL auditflow domain policy set (generated from
 * OpenAPI x-labs64-auth, {@code classpath:auth-policy-domain.cedar}) + resolver
 * + PEP on publishEvent — service principals and users authorize through the
 * same path (P8).
 */
class AuditEventCedarAuthorizationTest {

    private final List<AuthorizationDecision> decisions = new ArrayList<>();
    private MockHttpServletResponse response;

    @BeforeEach
    void resetResponse() {
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void cleanup() {
        AuthContextHolder.clear();
    }

    private AuthorizeInterceptor interceptor(final CedarProperties.Mode mode) {
        CedarProperties properties = new CedarProperties();
        properties.setEnabled(true);
        properties.setMode(mode);
        CedarAuthorizationService service = new CedarAuthorizationService(properties,
                new ClassPathResource("auth-policy-domain.cedar"));
        return new AuthorizeInterceptor(service, List.of(new AuditEventCedarEntityResolver()),
                List.of(decisions::add));
    }

    private boolean invoke(final AuthorizeInterceptor interceptor) throws Exception {
        Method method = AuditEventController.class.getMethod("publishEvent", AuditEvent.class);
        HandlerMethod handler = new HandlerMethod(mock(AuditEventController.class), method);
        return interceptor.preHandle(new MockHttpServletRequest(), response, handler);
    }

    @Test
    void servicePrincipalWithWriteScopePublishes() throws Exception {
        AuthContextHolder.set(new AuthContext("svc:auditflow-publisher", "t_100",
                Set.of("audit-event:write"), "r-1"));
        assertThat(invoke(interceptor(CedarProperties.Mode.ENFORCE))).isTrue();
        assertThat(decisions.get(0).allowed()).isTrue();
    }

    @Test
    void tenantlessServicePrincipalPublishesPlatformEvents() throws Exception {
        AuthContextHolder.set(new AuthContext("svc:auditflow-publisher", null,
                Set.of("audit-event:write"), "r-2"));
        assertThat(invoke(interceptor(CedarProperties.Mode.ENFORCE))).isTrue();
    }

    @Test
    void missingWriteScopeIsDenied() throws Exception {
        AuthContextHolder.set(new AuthContext("mallory", "t_100", Set.of("audit-event:read"), "r-3"));
        assertThat(invoke(interceptor(CedarProperties.Mode.ENFORCE))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void shadowModeAuditsDenyWithoutBlocking() throws Exception {
        AuthContextHolder.set(new AuthContext("mallory", "t_100", Set.of(), "r-4"));
        assertThat(invoke(interceptor(CedarProperties.Mode.SHADOW))).isTrue();
        assertThat(decisions.get(0).allowed()).isFalse();
        assertThat(decisions.get(0).enforced()).isFalse();
    }
}
