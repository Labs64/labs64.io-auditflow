*** Settings ***
Documentation    POST /audit/publish/batch (operationId publishEvents) at the gateway edge: one
...              result per event in request order, partial acceptance (an invalid event never
...              blocks the valid ones), the 100-event limit, and the same authz contract as
...              /audit/publish (tenant + audit-event:write). The anonymous-401 case lives in the
...              generated labs64.io-tests/tests/common/auth_enforcement.robot.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Test Teardown    Delete All Sessions

*** Test Cases ***
Batch returns one result per event and accepts the valid ones
    [Documentation]    Three events, the middle one missing the required eventType: 200 with
    ...                acceptedCount 2, rejectedCount 1, results in request order, the invalid
    ...                one REJECTED with VALIDATION_ERROR and the others ACCEPTED with an eventId.
    [Tags]    auditflow    regression    batch
    Create AuditFlow Session
    ${correlation_id}=    Generate Correlation ID
    ${valid_1}=    Build Valid Audit Event    ${correlation_id}
    ${valid_2}=    Build Valid Audit Event    ${correlation_id}
    ${invalid}=    Build Invalid Audit Event Missing Required Field
    ${events}=    Create List    ${valid_1}    ${invalid}    ${valid_2}
    ${response}=    Publish Audit Event Batch    ${events}
    Response Status Should Be    ${response}    200
    ${body}=    Set Variable    ${response.json()}
    Should Be Equal As Integers    ${body}[acceptedCount]    2
    Should Be Equal As Integers    ${body}[rejectedCount]    1
    Should Be Equal As Integers    ${body}[results][0][index]    0
    Should Be Equal As Strings    ${body}[results][0][status]    ACCEPTED
    Should Not Be Empty    ${body}[results][0][eventId]
    Should Be Equal As Strings    ${body}[results][1][status]    REJECTED
    Should Be Equal As Strings    ${body}[results][1][error][code]    VALIDATION_ERROR
    Should Be Equal As Strings    ${body}[results][2][status]    ACCEPTED

Batch above 100 events is rejected whole (400)
    [Documentation]    maxItems is 100: a 101-event batch is a 400 for the whole request, no
    ...                per-event results and nothing published.
    [Tags]    auditflow    regression    batch
    Create AuditFlow Session
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${events}=    Evaluate    [$event] * 101
    ${response}=    Publish Audit Event Batch    ${events}
    Response Status Should Be    ${response}    400

Batch with a wrong scope is denied (403)
    [Documentation]    publishEvents requires audit-event:write like publishEvent: a valid token
    ...                with an unrelated scope is refused at the edge.
    [Tags]    auditflow    regression    auth    batch
    Create Session With Scope    ${AUDITFLOW_SESSION_ALIAS}    ${AUDITFLOW_BASE_URL}    audit-event:read
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${events}=    Create List    ${event}
    ${response}=    Publish Audit Event Batch    ${events}
    Response Status Should Be    ${response}    403
