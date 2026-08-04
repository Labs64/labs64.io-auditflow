*** Settings ***
Documentation    secretRef resolution (item 12), asserted via observable DLQ growth in the
...              dedicated t_regression tenant.
...
...              t_regression carries two otherwise-identical pipelines that both use a sink
...              property of the form ``${secretRef:<key>}``:
...              - ``secretref_present`` references a key that IS provisioned as an environment
...                variable (AUDITFLOW_TENANT_T_REGRESSION_REGRESSIONTESTKEY, set in
...                overrides/auditflow/values.secrets.local.yaml) — resolution succeeds, delivery
...                proceeds normally.
...              - ``test_secretref_missing_intentional_fail`` references a key that is never provisioned anywhere —
...                EnvSecretRefResolver throws a RetryableDeliveryException, which is exactly the
...                documented contract: a missing secretRef key is a retryable failure, never a
...                blank value and never another tenant's credential (see
...                labs64.io-docs/auditflow/operations.md).
...
...              The contrast between the two pipelines — same shape, only the key's
...              resolvability differs — is what proves resolution is actually happening, not
...              merely that delivery succeeds regardless of secretRef.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create Regression Session With Empty DLQ
Suite Teardown   Delete All Sessions

*** Test Cases ***
Resolvable secretRef delivers without dead-lettering
    [Documentation]    A pipeline whose secretRef key IS provisioned must not fail delivery.
    [Tags]    auditflow    regression    secret-ref
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    secretref_present
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    Sleep    5s    Grace period for a would-be failure to reach the DLQ before asserting absence.
    ${after}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    Should Be Equal As Integers    ${before}    ${after}
    ...    msg=DLQ count grew from ${before} to ${after} for a pipeline whose secretRef key is provisioned — resolution should have succeeded.

Missing secretRef key fails delivery, never a blank value
    [Documentation]    A pipeline whose secretRef key is NOT provisioned must be a retryable
    ...                failure that reaches the DLQ — proving the fail-closed contract.
    [Tags]    auditflow    regression    secret-ref    critical
    ${correlation_id}=    Generate Correlation ID
    ${before}=    Get DLQ Message Count    ${AUDITFLOW_REGRESSION_SESSION}    t_regression
    ${event}=    Build Probe Audit Event    ${correlation_id}    test_secretref_missing_intentional_fail
    ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_SESSION}
    Response Status Should Be    ${response}    200
    ${expected}=    Evaluate    ${before} + 1
    Wait Until DLQ Count At Least    ${AUDITFLOW_REGRESSION_SESSION}    t_regression    ${expected}
