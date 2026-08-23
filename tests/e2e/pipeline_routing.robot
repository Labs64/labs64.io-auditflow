*** Settings ***
Documentation    Pipeline routing/dispatch mechanics (item 12) — distinct from
...              condition_operators.robot, which proves individual operators' boolean
...              correctness. This suite proves the DISPATCH mechanism itself: an event that
...              matches no pipeline produces no delivery attempt at all, and an event that
...              matches multiple pipelines simultaneously (fan-out) is still delivered — and
...              dead-lettered — as exactly one unit, never duplicated or lost.
...
...              Tenant silo isolation (an event only ever routes through its own tenant's
...              pipeline set, no fall-through) is covered by tenant_isolation.robot; not
...              repeated here.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create Regression Session With Empty DLQ
Suite Teardown   Delete All Sessions

*** Test Cases ***
Event matching no pipeline condition produces no delivery attempt
    [Documentation]    An event whose extra.op matches none of t_regression's probe conditions
    ...                must not be dead-lettered — proving pipelines correctly skip an event
    ...                they were never meant to process, not merely that this particular probe
    ...                didn't match (condition_operators.robot's concern).
    [Tags]    auditflow    regression    pipeline-routing
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    no_such_operator_anywhere
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    Sleep    5s    Grace period for a would-be delivery attempt to reach the DLQ.
    ${after}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    Should Be Equal As Integers    ${before}    ${after}
    ...    msg=DLQ count grew from ${before} to ${after} for an event matching no pipeline condition.

Event matching two pipelines fans out and dead-letters exactly once
    [Documentation]    Sets extra.op/extra.value to satisfy probe_eq_intentional_fail's condition AND
    ...                extra.op_any to independently satisfy probe_any_mode_intentional_fail's condition — one
    ...                event, two matching (and each independently failing) pipelines. AuditFlow
    ...                fans a single event out to every matching pipeline concurrently but
    ...                dead-letters the whole event once on redelivery exhaustion, not once per
    ...                failing pipeline, so the correct outcome is a rise of exactly one — not
    ...                two, and not zero (which would mean one pipeline's failure silently
    ...                swallowed the other, or the event was lost rather than retried).
    [Tags]    auditflow    regression    pipeline-routing
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    eq    target    any_x
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    ${expected}=    Evaluate    ${before} + 1
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    t_regression    ${expected}
    # Confirm it settles at +1 and does not keep climbing to +2 (duplicated delivery).
    Sleep    5s    Grace period to rule out a second, delayed DLQ entry for the same event.
    ${after}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    Should Be Equal As Integers    ${expected}    ${after}
    ...    msg=Fan-out to two matching pipelines produced ${after} DLQ entries (expected exactly ${expected}) for one published event.
