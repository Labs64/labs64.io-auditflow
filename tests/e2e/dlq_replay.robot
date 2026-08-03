*** Settings ***
Documentation    DLQ inspect and replay (item 12), via the tenant-scoped `/actuator/dlq/<tenantId>`
...              endpoints against the dedicated t_regression tenant.
...
...              `test_secretref_missing_intentional_fail` (see secret_ref_resolution.robot) is used purely as a
...              deterministic, fast way to get a message into the DLQ — this suite's actual
...              subject is the inspect/replay mechanics themselves, not secretRef.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create Regression Session With Empty DLQ
Suite Teardown   Delete All Sessions

*** Test Cases ***
Inspection is non-destructive
    [Documentation]    GET /actuator/dlq/<tenantId> must not consume messages — two consecutive
    ...                GETs (with nothing replayed in between) must report the same count.
    ...                Seeds a message first: the suite starts from a purged DLQ, and comparing
    ...                0 to 0 would pass even if inspection ate every message.
    [Tags]    auditflow    regression    dlq
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Probe Audit Event    ${correlation_id}    test_secretref_missing_intentional_fail
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    t_regression    1
    ${first}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${second}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    Should Be Equal As Integers    ${first}    ${second}
    ...    msg=DLQ count changed between two consecutive inspections (${first} -> ${second}) with no replay in between — inspection must be non-destructive.

Replay drains and reports the tenant's own messages
    [Documentation]    Publish a message into the DLQ, then replay it: the response's
    ...                retriedCount must be at least 1 (the message this test just put there —
    ...                other regression tests may have their own messages queued concurrently,
    ...                so this asserts a lower bound, not an exact count).
    [Tags]    auditflow    regression    dlq    critical
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    test_secretref_missing_intentional_fail
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    ${expected}=    Evaluate    ${before} + 1
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    t_regression    ${expected}

    ${replay_result}=    Replay DLQ    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    Should Be Equal As Strings    ${replay_result}[status]    success
    ...    msg=DLQ replay for t_regression did not report success: ${replay_result}
    Should Be Equal As Strings    ${replay_result}[tenantId]    t_regression
    Should Be True    ${replay_result}[retriedCount] >= 1
    ...    msg=Replay reported retriedCount=${replay_result}[retriedCount], expected at least 1 (this test's own message).

    # Every replayed message re-enters the same permanently-broken pipelines (unresolvable
    # secretRef), so all of them will deterministically fail again and land back in the DLQ —
    # asynchronously, after retry/backoff and possibly a circuit-breaker open period (see
    # auditflow-be's resilience4j.circuitbreaker.wait-duration-in-open-state). Wait for that
    # full round-trip here so t_regression's DLQ is settled before the next suite (which shares
    # this tenant) takes its own before/after snapshot — otherwise this replay's re-arrivals
    # land mid-flight in someone else's window.
    #
    # This window is fixed, so it only holds because the suite starts from a purged DLQ (see
    # `Create Regression Session With Empty DLQ`) and replays a handful of messages rather than
    # an unbounded backlog — the round-trip scales with retriedCount, this timeout does not.
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ...    ${replay_result}[retriedCount]    timeout=45s

Replay only touches the requesting tenant's own messages
    [Documentation]    Replay for t_regression_quota (a distinct tenant, no messages of its own
    ...                expected here) must not report a positive retriedCount just because
    ...                t_regression has messages queued — the DLQ is genuinely tenant-scoped, not
    ...                a shared pool filtered client-side.
    [Tags]    auditflow    regression    dlq    tenant-isolation
    Create Regression Quota Session
    ${replay_result}=    Replay DLQ    ${AUDITFLOW_REGRESSION_QUOTA_SESSION}    t_regression_quota
    Should Be Equal As Strings    ${replay_result}[status]    success
    Should Be Equal As Strings    ${replay_result}[tenantId]    t_regression_quota
    Should Be Equal As Integers    ${replay_result}[retriedCount]    0
    ...    msg=Replay for t_regression_quota retried ${replay_result}[retriedCount] message(s) — it should never see t_regression's DLQ entries.
