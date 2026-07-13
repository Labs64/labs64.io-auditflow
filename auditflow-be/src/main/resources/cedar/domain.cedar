// auditflow domain Cedar policy set (RFC-05 P4 fan-out, Tier 2).
//
// Validated in CI against the ONE shared schema
// (labs64.io-commons/auth-policy-cedar/schema.cedarschema); evaluated
// in-process by the @Authorize PEP (auth-context-spring-boot-starter).

// ── Publish requires the write scope (users and service principals alike —
// no separate machine trust path, RFC-05 P8) ──
permit(
  principal,
  action == Labs64IO::Action::"publishAudit",
  resource
) when { context.scopes.contains("audit-event:write") };

// ── Tenant guard for tenant-scoped events: when the event carries a tenant
// (derived from the trusted X-Auth-Tenant by the controller), the publisher
// must belong to that tenant. Tenant-less platform events stay publishable
// by any principal holding the write scope.
forbid(
  principal,
  action == Labs64IO::Action::"publishAudit",
  resource
) when { resource has tenant && !(principal in resource.tenant) };
