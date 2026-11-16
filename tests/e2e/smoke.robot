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
    [Tags]    auditflow    smoke
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    Should Be A UUID    ${response.headers}[X-Audit-Event-Id]
    Timestamp Should Be Recent    ${response.headers}[X-Audit-Received-At]

Publish valid batch (200)
    [Documentation]    POST /audit/publish/batch with two valid events: 200, both ACCEPTED.
    [Tags]    auditflow    smoke    batch
    ${events}=    Build Valid Audit Event Batch    2
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    2
    Should Be Equal As Integers    ${body}[acceptedCount]    2

Reject event missing required field (400)
    [Documentation]    POST missing `eventType` returns 400 VALIDATION_ERROR.
    [Tags]    auditflow    smoke    error-handling
    ${event}=    Build Invalid Audit Event Missing Required Field
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    400
    ${body}=    Set Variable    ${response.json()}
    Should Be Equal As Strings    ${body}[code]    VALIDATION_ERROR

Reject oversized payload (413)
    [Documentation]    A 15 MB body exceeds the gateway's request-size limit (2.5 MiB by default) and must be refused at the edge with 413.
    [Tags]    auditflow    smoke    error-handling
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Oversized Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    413
