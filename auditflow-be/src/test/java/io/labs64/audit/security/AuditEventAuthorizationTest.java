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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import io.labs64.audit.controller.AuditEventController;
import io.labs64.auditflow.model.AuditEvent;
import io.labs64.authcontext.authorization.AuthorizationDecision;
import io.labs64.authcontext.authorization.AuthorizationProperties;
import io.labs64.authcontext.authorization.AuthorizationService;
import io.labs64.authcontext.authorization.AuthorizeInterceptor;
import io.labs64.authcontext.authorization.ResourceEntity;
import io.labs64.authcontext.core.AuthContext;
import io.labs64.authcontext.core.AuthContextHolder;

/**
 * RFC-07 rename migration: the real {@link AuditEventResourceResolver} + the
 * {@code @Authorize} PEP on {@code publishEvent}, exercised against a stub
 * {@link AuthorizationService} that mirrors the generated Cerbos domain policy
 * (audit-event:write scope + cross-tenant guard). Service principals and users
 * authorize through the same path (P8); a tenantless service principal
 * publishes platform events. The real Cerbos client is covered by the commons
 * {@code CerbosAuthorizationServiceTest}, whole-policy equivalence by the
 * commons {@code auth-policy-cerbos} truth-table gate.
 */
class AuditEventAuthorizationTest {

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

    /**
     * Stub PDP mirroring the generated auditflow domain policy: publishEvent
     * needs the {@code audit-event:write} scope, and the tenant guard denies
     * whenever the resource carries a tenant that differs from the principal.
     * The resolver builds the resource tenant from the trusted context, so the
     * guard reduces to the scope check (and passes for tenantless platform
     * events).
     */
    private static final class StubAuthorizationService implements AuthorizationService {

        private final AuthorizationProperties.Mode mode;

        StubAuthorizationService(final AuthorizationProperties.Mode mode) {
            this.mode = mode;
        }

        @Override
        public boolean isEnforcing() {
            return mode == AuthorizationProperties.Mode.ENFORCE;
        }

        @Override
        public AuthorizationDecision decide(final AuthContext ctx, final String action, final ResourceEntity resource) {
            Object tenant = resource.attributes().get("tenant");
            boolean tenantGuard = tenant == null || tenant.equals(ctx.tenantId());
            boolean scopeOk = ctx.hasScope("audit-event:write");
            boolean allowed = tenantGuard && scopeOk;
            return new AuthorizationDecision(action, resource.type(), resource.id(),
                    allowed, isEnforcing(), allowed ? List.of("policy0") : List.of(), null,
                    ctx.userId(), ctx.tenantId(), ctx.requestId());
        }
    }

    private AuthorizeInterceptor interceptor(final AuthorizationProperties.Mode mode) {
        return new AuthorizeInterceptor(new StubAuthorizationService(mode),
                List.of(new AuditEventResourceResolver()), List.of(decisions::add));
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
        assertThat(invoke(interceptor(AuthorizationProperties.Mode.ENFORCE))).isTrue();
        assertThat(decisions.get(0).allowed()).isTrue();
    }

    @Test
    void tenantlessServicePrincipalPublishesPlatformEvents() throws Exception {
        AuthContextHolder.set(new AuthContext("svc:auditflow-publisher", null,
                Set.of("audit-event:write"), "r-2"));
        assertThat(invoke(interceptor(AuthorizationProperties.Mode.ENFORCE))).isTrue();
    }

    @Test
    void missingWriteScopeIsDenied() throws Exception {
        AuthContextHolder.set(new AuthContext("mallory", "t_100", Set.of("audit-event:read"), "r-3"));
        assertThat(invoke(interceptor(AuthorizationProperties.Mode.ENFORCE))).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void shadowModeAuditsDenyWithoutBlocking() throws Exception {
        AuthContextHolder.set(new AuthContext("mallory", "t_100", Set.of(), "r-4"));
        assertThat(invoke(interceptor(AuthorizationProperties.Mode.SHADOW))).isTrue();
        assertThat(decisions.get(0).allowed()).isFalse();
        assertThat(decisions.get(0).enforced()).isFalse();
    }
}
