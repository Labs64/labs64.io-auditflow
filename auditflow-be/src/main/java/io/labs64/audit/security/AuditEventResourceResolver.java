package io.labs64.audit.security;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import io.labs64.authcontext.authorization.ResourceEntity;
import io.labs64.authcontext.authorization.ResourceResolver;
import io.labs64.authcontext.core.AuthContext;

/**
 * Supplies the {@code AuditEvent} resource for {@code @Authorize} on
 * {@code publishEvent}. The event under publication is not yet
 * persisted and its tenant is authoritative from the trusted context (the
 * controller overrides any client-supplied tenantId with X-Auth-Tenant), so
 * the resource is built from the AuthContext alone — no repository lookup,
 * no resource reference.
 */
@Component
public class AuditEventResourceResolver implements ResourceResolver {

    @Override
    public boolean supports(final String resourceType) {
        return "AuditEvent".equals(resourceType);
    }

    @Override
    public ResourceEntity resolve(final String resourceType, @Nullable final Object resourceRef,
            final AuthContext context) {
        final ResourceEntity.Builder event = ResourceEntity.builder("AuditEvent", "publish-request");
        if (context.tenantId() != null) {
            event.attribute("tenant", context.tenantId());
        }
        return event.build();
    }
}
