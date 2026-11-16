*** Settings ***
Documentation    The DLQ operator endpoint, ``/actuator/dlq/<tenantId>``, through the gateway edge: who may
...              call it, what an entry carries, how ``limit`` and ``pipeline`` narrow what is returned and
...              discarded, that a purge counts what it discards and touches only its own tenant, and how
...              bad input is answered. Replay itself is dlq_replay.robot's subject.
...
...              The endpoint is an actuator, not part of the OpenAPI contract: its behaviour is described
...              in DEVELOPERS.md ("Observability" endpoint table) and the backend's DlqEndpoint, which is
...              what these cases follow. It stays in this suite because it is the only way an edge client
...              can see what AuditFlow dead-lettered.
...
...              Fixture: the ``t_regression`` tenant, whose probe pipelines always fail. One event with
...              ``extra.op=eq``, ``extra.value=target`` and ``extra.op_any=any_x`` fans out to exactly two
...              of them (``probe_eq_intentional_fail`` and ``probe_any_mode_intentional_fail``) and, with
...              retry.maxAttempts 1, dead-letters exactly two entries at once, so a case seeds a known
...              number of entries and counts them exactly. Every case starts from a purged DLQ and
...              waits for its own entries to land before it ends, so nothing it produced can arrive
...              inside the next suite's measurement window.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create Regression Session With Empty DLQ
Suite Teardown   Purge The DLQ And Close Sessions
Test Setup       Purge DLQ    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
Test Tags        auditflow    regression    dlq

*** Variables ***
${DLQ_TENANT}              t_regression
${EQ_PIPELINE}             probe_eq_intentional_fail
${ANY_PIPELINE}            probe_any_mode_intentional_fail
${NOT_A_TENANT_ID}         -bad-tenant

*** Test Cases ***
# --- access control ---------------------------------------------------------------------------------

Anonymous inspection is refused (401)
    [Tags]    auth
    [Template]    DLQ Request Without Credentials Should Be Refused
    GET

Anonymous replay is refused (401)
    [Tags]    auth
    [Template]    DLQ Request Without Credentials Should Be Refused
    POST

Anonymous purge is refused (401)
    [Tags]    auth
    [Template]    DLQ Request Without Credentials Should Be Refused
    DELETE

A publisher token cannot inspect the DLQ (403)
    [Tags]    auth
    [Template]    DLQ Request With Publisher Token Should Be Denied
    GET

A publisher token cannot replay the DLQ (403)
    [Tags]    auth
    [Template]    DLQ Request With Publisher Token Should Be Denied
    POST

A publisher token cannot purge the DLQ (403)
    [Tags]    auth
    [Template]    DLQ Request With Publisher Token Should Be Denied
    DELETE

# --- what an entry carries --------------------------------------------------------------------------

Inspection returns the documented entry fields
    [Documentation]    One event fanned out to two failing pipelines: two entries, one per pipeline, each
    ...                with the documented fields, the original event (its correlationId proves it is
    ...                this test's) and the dead-letter reason.
    ${ids}=    Seed Fan-Out Events    1
    ${info}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    limit=10
    Should Be Equal As Integers    ${info}[messageCount]    2
    Length Should Be    ${info}[entries]    2
    ${pipelines}=    Create List
    FOR    ${entry}    IN    @{info}[entries]
        FOR    ${field}    IN    eventId    pipeline    reason    attempts    lastError    deadLetteredAt    firstEnqueuedAt    event
            Dictionary Should Contain Key    ${entry}    ${field}
            ...    msg=DLQ entry has no '${field}': ${entry}
        END
        Should Be Equal As Strings    ${entry}[event][correlationId]    ${ids}[0]
        Should Be Equal As Strings    ${entry}[reason]    ATTEMPTS_EXHAUSTED
        Should Not Be Empty    ${entry}[lastError]
        Timestamp Should Be Recent    ${entry}[deadLetteredAt]
        Append To List    ${pipelines}    ${entry}[pipeline]
    END
    Sort List    ${pipelines}
    Should Be Equal    ${pipelines}    ${{ sorted(['${EQ_PIPELINE}', '${ANY_PIPELINE}']) }}
    ...    msg=The two entries are not one per failed pipeline: ${pipelines}

Counts break the DLQ down by pipeline and reason
    ${ids}=    Seed Fan-Out Events    2
    ${info}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}
    Should Be Equal As Integers    ${info}[messageCount]    4
    Should Be Equal As Integers    ${info}[byPipeline][${EQ_PIPELINE}]    2
    Should Be Equal As Integers    ${info}[byPipeline][${ANY_PIPELINE}]    2
    Should Be Equal As Integers    ${info}[byReason][ATTEMPTS_EXHAUSTED]    4
    Should Be Equal As Strings    ${info}[tenantId]    ${DLQ_TENANT}
    Should Be Equal As Strings    ${info}[status]    available

# --- limit and pipeline -----------------------------------------------------------------------------

Without a limit only the counts are returned
    ${ids}=    Seed Fan-Out Events    1
    ${info}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}
    Dictionary Should Not Contain Key    ${info}    entries
    ...    msg=Entries returned without a limit — the default must be counts only.
    Should Be Equal As Integers    ${info}[messageCount]    2

The limit bounds the number of entries returned
    [Documentation]    Four entries queued: limit 1 returns one, limit 3 returns three, and neither
    ...                changes the total the response reports.
    ${ids}=    Seed Fan-Out Events    2
    ${one}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    limit=1
    ${three}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    limit=3
    Length Should Be    ${one}[entries]    1
    Length Should Be    ${three}[entries]    3
    Should Be Equal As Integers    ${one}[messageCount]    4
    Should Be Equal As Integers    ${three}[messageCount]    4

The pipeline filter narrows the entries
    [Documentation]    Only the named pipeline's entries are listed; a pipeline that failed nothing lists
    ...                none. The counts stay the whole tenant's.
    ${ids}=    Seed Fan-Out Events    1
    ${filtered}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    limit=10    pipeline=${EQ_PIPELINE}
    Length Should Be    ${filtered}[entries]    1
    Should Be Equal As Strings    ${filtered}[entries][0][pipeline]    ${EQ_PIPELINE}
    ${unknown}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    limit=10    pipeline=no_such_pipeline
    Length Should Be    ${unknown}[entries]    0
    Should Be Equal As Integers    ${unknown}[messageCount]    2

# --- purge ------------------------------------------------------------------------------------------

Purge discards everything and reports how many
    ${ids}=    Seed Fan-Out Events    1
    ${purged}=    Purge DLQ    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}
    Should Be Equal As Strings    ${purged}[status]    success
    Should Be Equal As Strings    ${purged}[tenantId]    ${DLQ_TENANT}
    Should Be Equal As Integers    ${purged}[purgedCount]    2
    ${count}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}
    Should Be Equal As Integers    ${count}    0
    ...    msg=The DLQ still holds ${count} entries after a purge that reported 2 discarded.

Purging one pipeline leaves the others
    [Documentation]    Four entries, two per pipeline: purging the eq pipeline discards exactly its two
    ...                and leaves the other pipeline's two untouched.
    ${ids}=    Seed Fan-Out Events    2
    ${purged}=    Purge DLQ    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    pipeline=${EQ_PIPELINE}
    Should Be Equal As Integers    ${purged}[purgedCount]    2
    ${info}=    Get DLQ Info    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    limit=10
    Should Be Equal As Integers    ${info}[messageCount]    2
    Dictionary Should Not Contain Key    ${info}[byPipeline]    ${EQ_PIPELINE}
    Should Be Equal As Integers    ${info}[byPipeline][${ANY_PIPELINE}]    2

Purging an empty DLQ is a no-op that reports zero
    ${purged}=    Purge DLQ    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}
    Should Be Equal As Strings    ${purged}[status]    success
    Should Be Equal As Integers    ${purged}[purgedCount]    0

Purging another tenant's DLQ leaves this tenant's entries
    [Tags]    tenant-isolation
    [Documentation]    Entries queued for t_regression survive a purge addressed to t_regression_quota:
    ...                the purge reports zero and the first tenant's count is unchanged.
    ${ids}=    Seed Fan-Out Events    1
    Create Regression Quota Session
    ${purged}=    Purge DLQ    ${AUDITFLOW_REGRESSION_QUOTA_SESSION}    t_regression_quota
    Should Be Equal As Integers    ${purged}[purgedCount]    0
    ...    msg=A purge of t_regression_quota discarded ${purged}[purgedCount] entries it does not own.
    ${count}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}
    Should Be Equal As Integers    ${count}    2
    ...    msg=t_regression's DLQ changed from 2 to ${count} after a purge addressed to another tenant.

# --- bad input --------------------------------------------------------------------------------------

An invalid tenant id is reported, not served
    [Tags]    error-handling
    [Template]    Invalid Tenant Id Should Be Reported As Error
    GET

An invalid tenant id is not replayed
    [Tags]    error-handling
    [Template]    Invalid Tenant Id Should Be Reported As Error
    POST

An invalid tenant id is not purged
    [Tags]    error-handling
    [Template]    Invalid Tenant Id Should Be Reported As Error
    DELETE

A non-numeric limit is a client error, not a server error
    [Documentation]    ``?limit=abc`` is the caller's mistake: 400 is the answer. Today the endpoint
    ...                answers 500 INTERNAL_ERROR, so this case is tagged known-bug and left out of the
    ...                gating runs; target it with `just test-case` to check on it.
    [Tags]    error-handling    known-bug
    ${response}=    Send Raw Request    ${AUDITFLOW_REGRESSION_SESSION}    GET
    ...    /actuator/dlq/${DLQ_TENANT}?limit=abc    ${NONE}    ${NONE}
    Should Be Equal As Integers    ${response.status_code}    400
    ...    msg=A non-numeric limit returned ${response.status_code} instead of 400. Body: ${response.text}

*** Keywords ***
Seed Fan-Out Events
    [Documentation]    Publish ``${count}`` events that each fan out to the two failing probe pipelines,
    ...                wait until all ``2 * count`` entries are in the DLQ, and return the events'
    ...                correlation ids in order.
    [Arguments]    ${count}
    ${ids}=    Create List
    FOR    ${index}    IN RANGE    ${count}
        ${correlation_id}=    Generate Correlation ID
        ${event}=    Build Probe Audit Event    ${correlation_id}    eq    target    any_x
        ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
        Response Status Should Be    ${response}    200
        Append To List    ${ids}    ${correlation_id}
    END
    ${expected}=    Evaluate    2 * ${count}
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}    ${expected}    timeout=45s
    RETURN    ${ids}

DLQ Request Without Credentials Should Be Refused
    [Arguments]    ${method}
    Create Unauthenticated Session    auditflow-dlq-anon    ${AUDITFLOW_BASE_URL}
    ${response}=    Send Raw Request    auditflow-dlq-anon    ${method}    /actuator/dlq/${DLQ_TENANT}    ${NONE}    ${NONE}
    Response Should Be Error Response    ${response}    401    UNAUTHORIZED

DLQ Request With Publisher Token Should Be Denied
    [Documentation]    A token that may publish (audit-event:write) has no business with the DLQ: 403
    ...                FORBIDDEN, and nothing is inspected, replayed or discarded.
    [Arguments]    ${method}
    Create Session With Scope    auditflow-dlq-publisher    ${AUDITFLOW_BASE_URL}    ${AUDITFLOW_WRITE_SCOPE}
    ${response}=    Send Raw Request    auditflow-dlq-publisher    ${method}    /actuator/dlq/${DLQ_TENANT}    ${NONE}    ${NONE}
    Response Should Be Error Response    ${response}    403    FORBIDDEN

Invalid Tenant Id Should Be Reported As Error
    [Documentation]    A tenant id that fails the id pattern is rejected before any queue is touched: never
    ...                a server error, and the body says so (``status: error``) instead of showing counts.
    [Arguments]    ${method}
    ${response}=    Send Raw Request    ${AUDITFLOW_REGRESSION_SESSION}    ${method}
    ...    /actuator/dlq/${NOT_A_TENANT_ID}
    Should Be True    ${response.status_code} in (200, 400)
    ...    msg=${method} with an invalid tenant id returned ${response.status_code}. Body: ${response.text}
    IF    ${response.status_code} == 200
        Should Be Equal As Strings    ${response.json()}[status]    error
        Dictionary Should Not Contain Key    ${response.json()}    messageCount
    END

Purge The DLQ And Close Sessions
    Run Keyword And Ignore Error    Purge DLQ    ${AUDITFLOW_REGRESSION_SESSION}    ${DLQ_TENANT}
    Delete All Sessions
