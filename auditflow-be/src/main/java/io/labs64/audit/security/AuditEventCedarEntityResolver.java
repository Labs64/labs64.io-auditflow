package io.labs64.audit.security;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.labs64.authcontext.cedar.CedarEntity;
import io.labs64.authcontext.cedar.CedarEntityResolver;
import io.labs64.authcontext.core.AuthContext;

/**
 * Supplies the Cedar {@code AuditEvent} resource for {@code @Authorize} on
 * {@code publishEvent} (RFC-05 P4). The event under publication is not yet
 * persisted and its tenant is authoritative from the trusted context (the
 * controller overrides any client-supplied tenantId with X-Auth-Tenant), so
 * the resource is built from the AuthContext alone — no repository lookup,
 * no resource reference.
 */
@Component
public class AuditEventCedarEntityResolver implements CedarEntityResolver {

    @Override
    public boolean supports(final String resourceType) {
        return "AuditEvent".equals(resourceType);
    }

    @Override
    public CedarEntity resolve(final String resourceType, @Nullable final Object resourceRef,
            final AuthContext context) {
        final CedarEntity.Builder event = CedarEntity.builder("AuditEvent", "publish-request");
        if (context.tenantId() != null) {
            final CedarEntity tenant = CedarEntity.ref("Tenant", context.tenantId());
            event.attribute("tenant", tenant).parent(tenant);
        }
        return event.build();
    }
}
