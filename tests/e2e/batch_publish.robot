*** Settings ***
Documentation    POST /audit/publish/batch (operationId publishEvents) at the gateway edge: one
...              result per event in request order, partial acceptance (an invalid event never
...              blocks the valid ones), the 100-event limit, and the same authz contract as
...              /audit/publish (tenant + audit-event:write). The anonymous-401 case lives in the
...              generated labs64.io-tests/tests/common/auth_enforcement.robot.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Test Teardown    Delete All Sessions

*** Variables ***
# Request bodies that make the whole batch invalid (400, no per-event results).
${EMPTY_EVENTS}        {"events": []}
${NO_EVENTS_KEY}       {}
${EVENTS_NOT_ARRAY}    {"events": "not-an-array"}
${TRUNCATED_BATCH}     {"events":

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

Batch of exactly 100 events is accepted whole
    [Documentation]    maxItems is 100, so 100 is the largest valid batch: every event ACCEPTED, one
    ...                result each. The paired 101-event case above proves the limit from the other side.
    [Tags]    auditflow    regression    batch
    Create AuditFlow Session
    ${events}=    Build Valid Audit Event Batch    100
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    100
    Should Be Equal As Integers    ${body}[acceptedCount]    100
    Should Be Equal As Integers    ${body}[rejectedCount]    0

Results mirror the request event for event
    [Documentation]    Five events of mixed validity, one of them a JSON null: the response has exactly
    ...                five results, each ``index`` equal to its position, the two counts equal to the
    ...                ACCEPTED/REJECTED results, and the rejections exactly where the bad events were.
    [Tags]    auditflow    regression    batch
    Create AuditFlow Session
    ${valid}=    Build Valid Audit Event Batch    3
    ${invalid}=    Build Invalid Audit Event Missing Required Field
    ${events}=    Create List    ${valid}[0]    ${invalid}    ${valid}[1]    ${NONE}    ${valid}[2]
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    5
    Should Be Equal As Strings    ${body}[results][0][status]    ACCEPTED
    Should Be Equal As Strings    ${body}[results][1][status]    REJECTED
    Should Be Equal As Strings    ${body}[results][2][status]    ACCEPTED
    Should Be Equal As Strings    ${body}[results][3][status]    REJECTED
    Should Be Equal As Strings    ${body}[results][4][status]    ACCEPTED
    Should Be Equal As Integers    ${body}[acceptedCount]    3
    Should Be Equal As Integers    ${body}[rejectedCount]    2

A batch of only invalid events is still processed (200)
    [Documentation]    "Present when the batch was processed, even if every event was rejected": the
    ...                request is valid, so the answer is 200 with acceptedCount 0 — not a 4xx. A
    ...                validation rejection is final, so no Retry-After is offered for it.
    [Tags]    auditflow    regression    batch    error-handling
    Create AuditFlow Session
    ${first}=    Build Invalid Audit Event Missing Required Field
    ${second}=    Build Invalid Audit Event Missing Required Field
    ${events}=    Create List    ${first}    ${second}
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    2
    Should Be Equal As Integers    ${body}[acceptedCount]    0
    Should Be Equal As Integers    ${body}[rejectedCount]    2
    Dictionary Should Not Contain Key    ${response.headers}    Retry-After
    ...    msg=Retry-After offered for events rejected by validation, which a retry cannot fix.

Supplied eventId is echoed in its result
    [Documentation]    An event that brings an eventId keeps it (a retry therefore never duplicates);
    ...                one that does not gets a server-generated UUID, distinct per event.
    [Tags]    auditflow    regression    batch
    Create AuditFlow Session
    ${event_id}=    Evaluate    str(__import__('uuid').uuid4())
    ${events}=    Build Valid Audit Event Batch    3
    Set To Dictionary    ${events}[1]    eventId=${event_id}
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    3
    Should Be Equal As Strings    ${body}[results][1][eventId]    ${event_id}
    Should Be A UUID    ${body}[results][0][eventId]
    Should Be A UUID    ${body}[results][2][eventId]
    Should Not Be Equal    ${body}[results][0][eventId]    ${body}[results][2][eventId]
    ...    msg=Two events without an eventId were given the same id.

An event outside the eventTime window is rejected on its own
    [Documentation]    eventTime a day in the future breaks max-future: that one event is REJECTED with
    ...                VALIDATION_ERROR naming eventTime, while its neighbours are ACCEPTED.
    [Tags]    auditflow    regression    batch    validation
    Create AuditFlow Session
    ${events}=    Build Valid Audit Event Batch    3
    ${future}=    Get UTC Timestamp    86400
    Set To Dictionary    ${events}[1]    eventTime=${future}
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    3
    Should Be Equal As Strings    ${body}[results][0][status]    ACCEPTED
    Should Be Equal As Strings    ${body}[results][1][status]    REJECTED
    Should Be Equal As Strings    ${body}[results][1][error][code]    VALIDATION_ERROR
    Should Contain    ${body}[results][1][error][message]    eventTime
    Should Be Equal As Strings    ${body}[results][2][status]    ACCEPTED

A malformed event is rejected on its own
    [Documentation]    A property the schema does not know, and a JSON null in place of an event, each
    ...                REJECT only their own slot with VALIDATION_ERROR; the valid events around them
    ...                are ACCEPTED.
    [Tags]    auditflow    regression    batch    validation
    Create AuditFlow Session
    ${events}=    Build Valid Audit Event Batch    3
    Set To Dictionary    ${events}[0]    notInTheSchema=1
    ${events}=    Create List    ${events}[0]    ${events}[1]    ${NONE}    ${events}[2]
    ${response}=    Publish Audit Event Batch    ${events}
    ${body}=    Batch Response Should Be Consistent    ${response}    4
    Should Be Equal As Strings    ${body}[results][0][status]    REJECTED
    Should Be Equal As Strings    ${body}[results][0][error][code]    VALIDATION_ERROR
    Should Be Equal As Strings    ${body}[results][1][status]    ACCEPTED
    Should Be Equal As Strings    ${body}[results][2][status]    REJECTED
    Should Be Equal As Strings    ${body}[results][2][error][code]    VALIDATION_ERROR
    Should Be Equal As Strings    ${body}[results][3][status]    ACCEPTED

An empty events list is rejected whole (400)
    [Tags]    auditflow    regression    batch    validation    error-handling
    [Template]    Batch Body Should Be Rejected Whole
    ${EMPTY_EVENTS}

A body without events is rejected whole (400)
    [Tags]    auditflow    regression    batch    validation    error-handling
    [Template]    Batch Body Should Be Rejected Whole
    ${NO_EVENTS_KEY}

A non-array events value is rejected whole (400)
    [Tags]    auditflow    regression    batch    validation    error-handling
    [Template]    Batch Body Should Be Rejected Whole
    ${EVENTS_NOT_ARRAY}

A truncated JSON batch is rejected whole (400)
    [Tags]    auditflow    regression    batch    validation    error-handling
    [Template]    Batch Body Should Be Rejected Whole
    ${TRUNCATED_BATCH}

An empty request body is rejected whole (400)
    [Tags]    auditflow    regression    batch    validation    error-handling
    [Template]    Batch Body Should Be Rejected Whole
    ${EMPTY}

A batch with an unsupported Content-Type is refused (415)
    [Documentation]    publishEvents accepts application/json only: text/plain, and no Content-Type at
    ...                all, are 415 VALIDATION_ERROR — also when the body itself is a valid batch.
    [Tags]    auditflow    regression    batch    error-handling
    Create AuditFlow Session
    ${events}=    Build Valid Audit Event Batch    1
    ${body}=    Evaluate    json.dumps({'events': $events})    modules=json
    ${as_text}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish/batch    ${body}    text/plain
    Response Should Be Error Response    ${as_text}    415    VALIDATION_ERROR
    ${without}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish/batch    ${body}    ${NONE}
    Response Should Be Error Response    ${without}    415    VALIDATION_ERROR

A batch above the gateway body limit is refused (413)
    [Documentation]    A 15 MB body is over the gateway's request-size limit (2.5 MiB by default), so it
    ...                is cut off at the edge with 413 before reaching AuditFlow — the same limit as the
    ...                single operation, and never a 5xx.
    [Tags]    auditflow    regression    batch    error-handling
    Create AuditFlow Session
    ${body}=    Evaluate
    ...    json.dumps({'events': [{'eventType': 'a', 'sourceSystem': 'p', 'extra': {'blob': 'x' * 15000000}}]})
    ...    modules=json
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish/batch    ${body}
    Response Status Should Be    ${response}    413

A batch from an unprovisioned tenant is rejected whole (403)
    [Documentation]    The t_mock_2 persona has a valid token and the right scope but no tenant record:
    ...                the ingest gate refuses the whole batch with 403 TENANT_NOT_PROVISIONED and
    ...                returns no per-event results.
    [Tags]    auditflow    regression    batch    auth    tenant-isolation    error-handling
    Create Secondary Tenant Session
    ${events}=    Build Valid Audit Event Batch    2
    ${response}=    Publish Audit Event Batch    ${events}    alias=${AUDITFLOW_TENANT2_SESSION}
    Response Should Be Error Response    ${response}    403    TENANT_NOT_PROVISIONED
    Dictionary Should Not Contain Key    ${response.json()}    results

A batch with no scopes is denied (403)
    [Tags]    auditflow    regression    batch    auth
    Create Session With Scope    ${AUDITFLOW_SESSION_ALIAS}    ${AUDITFLOW_BASE_URL}    no-access
    ${events}=    Build Valid Audit Event Batch    1
    ${response}=    Publish Audit Event Batch    ${events}
    Response Should Be Error Response    ${response}    403    FORBIDDEN

A batch with a malformed bearer token is rejected (401)
    [Tags]    auditflow    regression    batch    auth
    ${headers}=    Create Dictionary
    ...    Authorization=Bearer test-fixture-malformed-jwt
    ...    Content-Type=application/json
    Create Session    auditflow-batch-bad-auth    ${AUDITFLOW_BASE_URL}    headers=${headers}
    ${events}=    Build Valid Audit Event Batch    1
    ${response}=    Publish Audit Event Batch    ${events}    alias=auditflow-batch-bad-auth
    Response Should Be Error Response    ${response}    401    UNAUTHORIZED

*** Keywords ***
Batch Body Should Be Rejected Whole
    [Documentation]    POST ``${body}`` to /audit/publish/batch as application/json: the request itself is
    ...                invalid, so 400 VALIDATION_ERROR and no per-event ``results`` at all.
    [Arguments]    ${body}
    Create AuditFlow Session
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish/batch    ${body}
    Response Should Be Error Response    ${response}    400    VALIDATION_ERROR
    Dictionary Should Not Contain Key    ${response.json()}    results
