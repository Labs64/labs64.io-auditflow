*** Settings ***
Documentation    Tenant isolation at the gateway edge for the AuditFlow tenant model
...              (silo routing, ingest gate, authoritative tenant stamping).
...
...              The gateway-derived tenant (token ``tenant`` claim → ``X-Auth-Tenant``) is
...              authoritative: a client-supplied ``tenantId`` in the payload must NEVER
...              influence which tenant an event is stamped, gated, or routed as. The local
...              mock-oidc provider mints every token with the same fixed ``tenant: t_mock``
...              claim (no second-tenant persona exists yet), so a literal two-tenant matrix
...              cannot be minted here; these cases instead prove the two facts that ARE
...              observable at the edge: (1) a spoofed body tenantId changes nothing, and
...              (2) when the ingest gate rejects, it does so with the contract's 403
...              ``TENANT_NOT_PROVISIONED`` shape, never a 5xx.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Test Teardown    Delete All Sessions

*** Test Cases ***
Ignore spoofed client TenantId (200|403)
    [Documentation]    Payload tenantId must not override the gateway-stamped tenant.
    [Tags]    auditflow    regression    critical    auth    tenant-isolation
    Create AuditFlow Session
    ${control_id}=    Generate Correlation ID
    ${control_event}=    Build Valid Audit Event    ${control_id}
    ${control}=    Publish Audit Event    ${control_event}
    ${spoof_tenant}=    Generate Spoof Tenant Id
    ${spoof_id}=    Generate Correlation ID
    ${spoof_event}=    Build Audit Event With Tenant    ${spoof_id}    ${spoof_tenant}
    ${spoofed}=    Publish Audit Event    ${spoof_event}
    Should Be Equal As Integers    ${control.status_code}    ${spoofed.status_code}
    ...    msg=Spoofed body tenantId changed the publish outcome (${control.status_code} vs ${spoofed.status_code}) — client-supplied tenantId must never override the gateway tenant.
    Should Be True    ${control.status_code} in (200, 403)
    ...    msg=Publish returned unexpected status ${control.status_code} (body: ${control.text}).

Reject unprovisioned tenant (403)
    [Documentation]    Ingest gate rejects unprovisioned tenants with 403 TENANT_NOT_PROVISIONED.
    [Tags]    auditflow    regression    auth    tenant-isolation    error-handling
    Create AuditFlow Session
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}
    Should Be True    ${response.status_code} in (200, 403)
    ...    msg=Publish returned unexpected status ${response.status_code} (body: ${response.text}).
    IF    ${response.status_code} == 403
        Should Be Equal As Strings    ${response.json()}[code]    TENANT_NOT_PROVISIONED
        ...    msg=Ingest-gate 403 carried the wrong error code: ${response.text}
    END

Reject unprovisioned secondary tenant strictly (403)
    [Documentation]    Using the t_mock_2 persona, which is guaranteed to be unprovisioned locally, proves a strict 403 rejection.
    [Tags]    auditflow    regression    auth    tenant-isolation    error-handling
    Create Session With Scope    tenant2_session    ${AUDITFLOW_BASE_URL}    auditflow-tenant-2
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}    alias=tenant2_session
    Should Be Equal As Integers    ${response.status_code}    403
    ...    msg=Publish returned unexpected status ${response.status_code} for unprovisioned tenant t_mock_2.
    Should Be Equal As Strings    ${response.json()}[code]    TENANT_NOT_PROVISIONED
    ...    msg=Ingest-gate 403 carried the wrong error code: ${response.text}

Verify backend logs ignore spoofed TenantId (local-k8s)
    [Documentation]    Backend logs must process event under token tenant, never the spoofed payload tenant.
    [Tags]    auditflow    regression    auth    tenant-isolation    local-k8s-only
    Skip Unless Local Kubernetes
    Create AuditFlow Session
    ${spoof_tenant}=    Generate Spoof Tenant Id
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Audit Event With Tenant    ${correlation_id}    ${spoof_tenant}
    ${response}=    Publish Audit Event    ${event}
    IF    ${response.status_code} != 200
        Skip    Publish was rejected at ingest (${response.status_code}) — no pipeline processing to corroborate.
    END
    # Sync on this event's own delivery before asserting the negative, so the absence check
    # cannot pass merely because the async pipeline hasn't run yet.
    AuditFlow Backend Logs Should Contain Correlation Id    ${correlation_id}
    AuditFlow Backend Logs Should Not Mention Tenant    ${spoof_tenant}
