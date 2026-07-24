*** Settings ***
Documentation    AuditFlow smoke checks — fast critical-path validation via the API edge.
...              These tests run on every PR. AuditFlow's public contract is a single
...              endpoint, POST /audit/publish; there is no query API by design.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create AuditFlow Session
Suite Teardown   Delete All Sessions

*** Test Cases ***
Publish valid event (200)
    [Documentation]    Valid POST to /audit/publish returns 200 and assigns X-Audit-Event-Id.
    [Tags]    auditflow    smoke    critical
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    Dictionary Should Contain Key    ${response.headers}    X-Audit-Event-Id

Reject event missing required field (400)
    [Documentation]    POST missing `eventType` returns 400 VALIDATION_ERROR.
    [Tags]    auditflow    smoke    error-handling
    ${event}=    Build Invalid Audit Event Missing Required Field
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    400
    ${body}=    Set Variable    ${response.json()}
    Should Be Equal As Strings    ${body}[code]    VALIDATION_ERROR

Reject oversized payload (413)
    [Documentation]    POST payload exceeding limits (e.g. 2.5MB) must be rejected gracefully with 413.
    [Tags]    auditflow    smoke    error-handling    known-bug
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Oversized Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    413
