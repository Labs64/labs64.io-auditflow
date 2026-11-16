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
Ignore spoofed client TenantId (200)
    [Documentation]    Payload tenantId must not override the gateway-stamped tenant. t_mock is provisioned,
    ...                so the plain event and the one carrying a made-up tenantId are both accepted: had the
    ...                payload tenant been honoured, the second would be refused as an unprovisioned tenant.
    [Tags]    auditflow    regression    auth    tenant-isolation
    Create AuditFlow Session
    ${control_id}=    Generate Correlation ID
    ${control_event}=    Build Valid Audit Event    ${control_id}
    ${control}=    Publish Audit Event    ${control_event}
    ${spoof_tenant}=    Generate Spoof Tenant Id
    ${spoof_id}=    Generate Correlation ID
    ${spoof_event}=    Build Audit Event With Tenant    ${spoof_id}    ${spoof_tenant}
    ${spoofed}=    Publish Audit Event    ${spoof_event}
    Response Status Should Be    ${control}    200
    Response Status Should Be    ${spoofed}    200

An unprovisioned tenant cannot borrow a provisioned one through the payload (403)
    [Documentation]    The t_mock_2 persona names t_mock in the event's tenantId. The tenant that counts is the
    ...                token's, so the ingest gate still refuses it: a payload must not be a way past the gate.
    [Tags]    auditflow    regression    auth    tenant-isolation    error-handling
    Create Secondary Tenant Session
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Audit Event With Tenant    ${correlation_id}    t_mock
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_TENANT2_SESSION}
    Response Should Be Error Response    ${response}    403    TENANT_NOT_PROVISIONED

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

Spoofed tenantId in a batch event changes nothing
    [Documentation]    Same rule for the batch operation: an event naming another tenant is ACCEPTED like its
    ...                neighbours (stamped with the token's tenant), not refused as an unprovisioned one.
    [Tags]    auditflow    regression    auth    tenant-isolation    batch
    Create AuditFlow Session
    ${events}=    Build Valid Audit Event Batch    2
    ${spoof_tenant}=    Generate Spoof Tenant Id
    Set To Dictionary    ${events}[1]    tenantId=${spoof_tenant}
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    2
    Should Be Equal As Integers    ${body}[acceptedCount]    2

A client-supplied X-Auth-Tenant header is ignored
    [Documentation]    X-Auth-* headers are the gateway's to set. A t_mock token that also sends
    ...                ``X-Auth-Tenant`` for a tenant that does not exist is still t_mock (200): had the
    ...                header been trusted, the ingest gate would refuse it as unprovisioned.
    [Tags]    auditflow    regression    auth    tenant-isolation
    Create AuditFlow Session
    ${headers}=    Create Dictionary    X-Auth-Tenant=${{'spoofed-' + __import__('uuid').uuid4().hex[:12]}}
    ${response}=    Publish With Extra Headers    ${AUDITFLOW_SESSION_ALIAS}    ${headers}
    Response Status Should Be    ${response}    200

A client-supplied X-Auth-Tenant header cannot unlock an unprovisioned tenant
    [Documentation]    The reverse: the t_mock_2 persona sends ``X-Auth-Tenant: t_mock``, the provisioned
    ...                tenant. It is still t_mock_2, and still refused.
    [Tags]    auditflow    regression    auth    tenant-isolation    error-handling
    Create Secondary Tenant Session
    ${headers}=    Create Dictionary    X-Auth-Tenant=t_mock
    ${response}=    Publish With Extra Headers    ${AUDITFLOW_TENANT2_SESSION}    ${headers}
    Response Should Be Error Response    ${response}    403    TENANT_NOT_PROVISIONED

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

*** Keywords ***
Publish With Extra Headers
    [Documentation]    Publish a valid event on ``${alias}`` with additional request headers.
    [Arguments]    ${alias}    ${headers}
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${body}=    Evaluate    json.dumps($event)    modules=json
    ${response}=    Send Raw Request    ${alias}    POST    /audit/publish    ${body}    headers=${headers}
    RETURN    ${response}
