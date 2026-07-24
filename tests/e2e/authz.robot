*** Settings ***
Documentation    AuditFlow authentication & authorization regression, asserted entirely at the
...              gateway edge (Traefik authproxy + cerbos). Mirrors the manual scope-matrix
...              validation this suite replaces: a token with the wrong scope must be denied
...              (403), the correct scope must be allowed (200), and missing/malformed
...              credentials must be rejected (401) before any Authz Decision is made.
...
...              The ``local-k8s-only`` tagged cases below additionally corroborate the HTTP
...              assertion against kubectl pod logs (authproxy Authz Decision + AuditFlow
...              backend delivery) — a deliberate, narrow exception to this suite's normal
...              "no kubectl" rule. They call `Skip Unless Local Kubernetes` first and skip
...              cleanly outside the local k3d dev cluster, so CI never fails on them. See
...              AGENTS.md "Local-only pod-log corroboration" for the full rationale/scope.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Test Teardown    Delete All Sessions

*** Test Cases ***
Reject missing authorization (401)
    [Documentation]    POST /audit/publish without auth header returns 401.
    [Tags]    auditflow    regression    critical    p0-blocker    auth
    Create Unauthenticated AuditFlow Session
    ${event}=    Build Valid Audit Event    unauth-check
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    401

Reject malformed bearer token (401)
    [Documentation]    Invalid bearer credential returns 401.
    [Tags]    auditflow    regression    critical    p0-blocker    auth
    # Deliberately nonsensical test fixture credential — not a real credential or signing key.
    ${headers}=    Create Dictionary
    ...    Authorization=Bearer test-fixture-malformed-jwt
    ...    Accept=application/json
    ...    Content-Type=application/json
    Create Session    auditflow-bad-auth    ${AUDITFLOW_BASE_URL}    headers=${headers}    verify=True
    ${event}=    Build Valid Audit Event    malformed-check
    ${response}=    POST On Session    auditflow-bad-auth    /audit/publish    json=${event}    expected_status=any
    Response Status Should Be    ${response}    401

Reject wrong scope (403)
    [Documentation]    Token missing audit-event:write scope is denied with 403 by cerbos.
    [Tags]    auditflow    regression    critical    auth    tenant-isolation
    Create Session With Scope    auditflow-wrong-scope    ${AUDITFLOW_BASE_URL}    audit-event:read
    ${event}=    Build Valid Audit Event    wrong-scope-check
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-wrong-scope
    Response Status Should Be    ${response}    403

Allow correct scope (200)
    [Documentation]    Token with audit-event:write scope is allowed.
    [Tags]    auditflow    regression    critical    auth
    Create Session With Scope    auditflow-correct-scope    ${AUDITFLOW_BASE_URL}    audit-event:write
    ${event}=    Build Valid Audit Event    correct-scope-check
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-correct-scope
    Response Status Should Be    ${response}    200

Allow multiple scopes including correct scope (200)
    [Documentation]    Token with audit-event:write alongside other scopes is allowed.
    [Tags]    auditflow    regression    critical    auth
    Create Session With Scope    auditflow-multi-scope    ${AUDITFLOW_BASE_URL}
    ...    audit-event:read audit-event:write customer:read
    ${event}=    Build Valid Audit Event    multi-scope-check
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-multi-scope
    Response Status Should Be    ${response}    200

Reject token with no scopes (403)
    [Documentation]    Token with empty scope set is denied with 403 by cerbos.
    [Tags]    auditflow    regression    critical    auth
    Create Session With Scope    auditflow-no-scope    ${AUDITFLOW_BASE_URL}    no-access
    ${event}=    Build Valid Audit Event    no-scope-check
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-no-scope
    Response Status Should Be    ${response}    403

Prevent 500 on unauthenticated request
    [Documentation]    Unauthenticated request gracefully returns 401, not a 500 server crash.
    [Tags]    auditflow    regression    auth    error-handling
    Create Unauthenticated AuditFlow Session
    ${event}=    Build Valid Audit Event    no-500-check
    ${response}=    Publish Audit Event    ${event}
    ${status}=    Convert To Integer    ${response.status_code}
    Should Be True    ${status} != 500
    ...    msg=Unauthenticated request caused HTTP 500 — possible misconfigured auth filter. Expected 401.

Verify authproxy logs show 401 (local-k8s)
    [Documentation]    Authproxy logs must record no-token rejection.
    [Tags]    auditflow    regression    auth    local-k8s-only
    Skip Unless Local Kubernetes
    Create Unauthenticated AuditFlow Session
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    401
    Authproxy Logs Should Show No-Token Rejection For Publish

Verify Cerbos deny in authproxy logs for wrong scope (local-k8s)
    [Documentation]    Authproxy logs show Cerbos deny and event never reaches backend.
    [Tags]    auditflow    regression    auth    tenant-isolation    local-k8s-only
    Skip Unless Local Kubernetes
    Create Session With Scope    auditflow-wrong-scope-k8s    ${AUDITFLOW_BASE_URL}    audit-event:read
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-wrong-scope-k8s
    Response Status Should Be    ${response}    403
    Authproxy Logs Should Show Authz Decision For Publish    enforced-deny
    AuditFlow Backend Logs Should Not Contain Correlation Id    ${correlation_id}

Verify Cerbos allow in authproxy logs for correct scope (local-k8s)
    [Documentation]    Authproxy logs show Cerbos allow and event reaches backend.
    [Tags]    auditflow    regression    auth    local-k8s-only
    Skip Unless Local Kubernetes
    Create Session With Scope    auditflow-correct-scope-k8s    ${AUDITFLOW_BASE_URL}    audit-event:write
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-correct-scope-k8s
    Response Status Should Be    ${response}    200
    Authproxy Logs Should Show Authz Decision For Publish    enforced-allow
    AuditFlow Backend Logs Should Contain Correlation Id    ${correlation_id}

Verify Cerbos allow in authproxy logs for multiple scopes (local-k8s)
    [Documentation]    Authproxy logs show Cerbos allow and event reaches backend.
    [Tags]    auditflow    regression    auth    local-k8s-only
    Skip Unless Local Kubernetes
    Create Session With Scope    auditflow-multi-scope-k8s    ${AUDITFLOW_BASE_URL}
    ...    audit-event:read audit-event:write customer:read
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-multi-scope-k8s
    Response Status Should Be    ${response}    200
    Authproxy Logs Should Show Authz Decision For Publish    enforced-allow
    AuditFlow Backend Logs Should Contain Correlation Id    ${correlation_id}

Verify Cerbos deny in authproxy logs for no scopes (local-k8s)
    [Documentation]    Authproxy logs show Cerbos deny and event never reaches backend.
    [Tags]    auditflow    regression    auth    local-k8s-only
    Skip Unless Local Kubernetes
    Create Session With Scope    auditflow-no-scope-k8s    ${AUDITFLOW_BASE_URL}    no-access
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}    alias=auditflow-no-scope-k8s
    Response Status Should Be    ${response}    403
    Authproxy Logs Should Show Authz Decision For Publish    enforced-deny
    AuditFlow Backend Logs Should Not Contain Correlation Id    ${correlation_id}
