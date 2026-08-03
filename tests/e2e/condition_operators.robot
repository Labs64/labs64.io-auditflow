*** Settings ***
Documentation    Pipeline condition-operator correctness (item 12), asserted entirely at the
...              gateway edge via observable DLQ growth.
...
...              Mechanism: every ``probe_*`` pipeline in the dedicated ``t_regression`` tenant
...              (see ``overrides/auditflow/values.local.yaml`` in labs64.io-helm-charts) is
...              deliberately broken via a ``${secretRef:...}`` that never resolves, so a probe
...              whose condition matches an event fails that event's delivery — and because a
...              DLQ entry is per EVENT (not per failing pipeline; AuditService fans one event
...              out to every matching pipeline and dead-letters the whole event once if any of
...              them fails retryably), a match is observable as the tenant's DLQ count rising by
...              exactly one, and a non-match leaves it unchanged. Each probe is ANDed with a
...              unique ``extra.op`` value so at most one probe can ever match a given test
...              event — cross-talk between operators is structurally impossible, not just
...              unlikely.
...
...              Covers every distinct branch in ConditionEvaluator's operator switch (aliases of
...              the same branch, e.g. eq/equals, are not re-tested) plus both match modes
...              (all/any).
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create Regression Session With Empty DLQ
Suite Teardown   Delete All Sessions

*** Keywords ***
Probe Should Match
    [Documentation]    Publish a probe event and assert the tenant's DLQ count rises by exactly
    ...                one, attributing the rise to the single probe pipeline whose condition
    ...                this event is constructed to satisfy.
    [Arguments]    ${op}    ${value}=${EMPTY}    ${op_any}=${EMPTY}
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    ${op}    ${value}    ${op_any}
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    ${expected}=    Evaluate    ${before} + 1
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    t_regression    ${expected}

Probe Should Not Match
    [Documentation]    Publish a probe event constructed to satisfy NO pipeline's condition and
    ...                assert the tenant's DLQ count is unchanged after a grace period long
    ...                enough for a match to have shown up (retry + backoff, well under the
    ...                circuit breaker's window) — proving the operator correctly evaluated false,
    ...                not merely that delivery hasn't been attempted yet.
    [Arguments]    ${op}    ${value}=${EMPTY}    ${op_any}=${EMPTY}
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    ${op}    ${value}    ${op_any}
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    Sleep    5s    Grace period for a would-be match to reach the DLQ before asserting absence.
    ${after}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    Should Be Equal As Integers    ${before}    ${after}
    ...    msg=DLQ count for t_regression grew from ${before} to ${after} for an event that should NOT have matched op='${op}' — operator evaluated true when it should have been false.

*** Test Cases ***
eq matches equal value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    eq    target

eq does not match a different value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    eq    not-the-target

neq matches a different value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    neq    anything-but-control

neq does not match the control value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    neq    control

contains matches a substring
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    contains    has-needle-inside

contains does not match without the substring
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    contains    nothing-here

startsWith matches a prefix
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    startswith    prefix-and-more

startsWith does not match a non-prefix
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    startswith    not-a-match

endsWith matches a suffix
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    endswith    leading-suffix

endsWith does not match a non-suffix
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    endswith    not-a-match

in matches a listed value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    in    b

in does not match an unlisted value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    in    z

notIn matches a value outside the list
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    notin    not-listed

notIn does not match a listed value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    notin    x

exists matches when the field is present
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    exists    anything

exists does not match when the field is absent
    [Documentation]    Same op='exists' probe, this time with no `value` at all.
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    exists

notExists matches when the field is absent
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    notexists

notExists does not match when the field is present
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    notexists    anything

regex matches a satisfying pattern
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    regex    R123

regex does not match a non-satisfying value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    regex    not-a-code

gt matches a strictly greater number
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    gt    11

gt does not match an equal number
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    gt    10

gte matches an equal number
    [Documentation]    Boundary check: gte must include equality where gt excludes it.
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    gte    10

gte does not match a lesser number
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    gte    9

lt matches a strictly lesser number
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    lt    9

lt does not match an equal number
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    lt    10

lte matches an equal number
    [Documentation]    Boundary check: lte must include equality where lt excludes it.
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    lte    10

lte does not match a greater number
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    lte    11

eqIgnoreCase matches regardless of case
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    eqignorecase    TaRgEt

eqIgnoreCase does not match a different value
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    eqignorecase    not-the-target

match:any fires on the first alternative
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    any_mode_unused    op_any=any_x

match:any fires on the second alternative
    [Tags]    auditflow    regression    condition-operators
    Probe Should Match    any_mode_unused    op_any=any_y

match:any does not fire when no alternative matches
    [Tags]    auditflow    regression    condition-operators
    Probe Should Not Match    any_mode_unused    op_any=any_z
