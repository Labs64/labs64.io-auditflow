*** Settings ***
Documentation    Pipelines with `steps`, through the gateway edge against the dedicated t_regression tenant:
...              - `context` is reserved for AuditFlow: a publisher that sets it is refused (it could otherwise forge
...              what an earlier step's sink returned), an empty one (what a generated client sends) is accepted;
...              - a step that fails dead-letters the delivery with how many steps were done before it
...              (`stepsDone` in the DLQ entry), which is where a replay resumes; a failure in the first step
...              records nothing, and its second step never runs.
...
...              The steps probes (values.local.yaml in labs64.io-helm-charts) use two logging sinks, the failing one
...              through a `${secretRef:...}` that never resolves, like every other deterministic probe of this tenant.
...              The context a sink returns, resume after a crash and the 4 KiB cap are not observable from the edge
...              with local sinks; the backend's unit tests cover them (PipelineStepsExecutorTest, DeliveryWorkerTest).
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create Regression Session With Empty DLQ
Suite Teardown   Delete All Sessions

*** Test Cases ***
A publisher cannot set the reserved context
    [Documentation]    Whatever the keys, a non-empty `context` is a 400 naming why, so an earlier step's result can't
    ...                be forged. The same event without the context is accepted, so only the context was refused.
    [Tags]    auditflow    regression    pipeline-steps    security
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Probe Audit Event    ${correlation_id}    no_such_operator_anywhere
    ${context}=    Create Dictionary    original=${{ {"key": "tenants/tenant=other/forged"} }}
    Set To Dictionary    ${event}    context=${context}
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    400
    Should Contain    ${response.json()}[message]    context is reserved for AuditFlow
    Remove From Dictionary    ${event}    context
    ${accepted}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${accepted}    200

A publisher cannot set the reserved context in a batch either
    [Documentation]    One forged entry is rejected on its own; the other entry of the batch is accepted.
    [Tags]    auditflow    regression    pipeline-steps    security
    ${good_id}=    Generate Correlation ID
    ${forged_id}=    Generate Correlation ID
    ${good}=    Build Probe Audit Event    ${good_id}    no_such_operator_anywhere
    ${forged}=    Build Probe Audit Event    ${forged_id}    no_such_operator_anywhere
    ${context}=    Create Dictionary    original=${{ {"key": "forged"} }}
    Set To Dictionary    ${forged}    context=${context}
    ${events}=    Create List    ${good}    ${forged}
    ${response}=    Publish Audit Event Batch    ${events}    alias=${AUDITFLOW_REGRESSION_SESSION}
    ${body}=    Set Variable    ${response.json()}
    Should Be Equal As Strings    ${body}[results][0][status]    ACCEPTED
    Should Be Equal As Strings    ${body}[results][1][status]    REJECTED
    Should Contain    ${body}[results][1][error][message]    context is reserved for AuditFlow

An empty context, as a generated client sends it, is accepted
    [Tags]    auditflow    regression    pipeline-steps
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Probe Audit Event    ${correlation_id}    no_such_operator_anywhere
    ${empty}=    Create Dictionary
    Set To Dictionary    ${event}    context=${empty}
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200

A failure in the second step is dead-lettered with the first step done
    [Documentation]    probe_steps_second_intentional_fail: step `first` succeeds, step `second` fails. The DLQ entry
    ...                of this event says one step was done, so a replay continues at the second.
    [Tags]    auditflow    regression    pipeline-steps    dlq
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Probe Audit Event    ${correlation_id}    steps_second_fail
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    ${entry}=    DLQ Entry For Correlation Id    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ...    probe_steps_second_intentional_fail    ${correlation_id}
    Should Be Equal As Integers    ${entry}[stepsDone]    1
    ...    msg=Expected the first step to be recorded as done, got entry ${entry}
    Should Be Equal As Strings    ${entry}[pipeline]    probe_steps_second_intentional_fail
    Should Not Contain    ${entry}[event]    context    msg=The event in the DLQ must not carry a context key.

A failure in the first step is dead-lettered with nothing done
    [Documentation]    probe_steps_first_intentional_fail: the first step fails, so the second never runs and the entry
    ...                has no `stepsDone`: a replay starts over, as for any one-step pipeline.
    [Tags]    auditflow    regression    pipeline-steps    dlq
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Probe Audit Event    ${correlation_id}    steps_first_fail
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    ${entry}=    DLQ Entry For Correlation Id    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ...    probe_steps_first_intentional_fail    ${correlation_id}
    Dictionary Should Not Contain Key    ${entry}    stepsDone
    Should Be Equal As Strings    ${entry}[pipeline]    probe_steps_first_intentional_fail

A step failure dead-letters once and only the matching pipeline
    [Documentation]    One event, one matching steps pipeline, one DLQ entry: a step's failure never fans out to the
    ...                other probes or repeats the delivery.
    [Tags]    auditflow    regression    pipeline-steps    dlq
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    steps_second_fail
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    ${expected}=    Evaluate    ${before} + 1
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    t_regression    ${expected}
    Sleep    5s    Grace period for a would-be duplicate or fan-out delivery.
    ${after}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    Should Be Equal As Integers    ${after}    ${expected}
    ...    msg=DLQ count went from ${before} to ${after}; exactly one entry was expected for this event.
