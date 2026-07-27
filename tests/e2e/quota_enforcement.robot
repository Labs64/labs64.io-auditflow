*** Settings ***
Documentation    Per-tenant ingest quota enforcement (item 12), asserted directly via HTTP status
...              — unlike the other item-12 suites this needs no DLQ indirection, since the
...              ingest gate's 429 is itself the observable outcome.
...
...              t_regression_quota (see overrides/auditflow/values.local.yaml) is provisioned
...              with a deliberately tiny token bucket (rateLimitPerSec=1, burst=2) so a handful
...              of rapid publishes trips 429 deterministically without flooding the shared
...              t_mock tenant every other suite depends on.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create Regression Quota Session
Test Teardown    Sleep    2s    Let the token bucket recover so later tests are not starved by this one.

*** Test Cases ***
Exceeding the burst is rejected with 429 and Retry-After
    [Documentation]    Publish past the tenant's burst capacity (2) and confirm the ingest gate
    ...                rejects the excess with 429 TENANT_RATE_LIMITED and a Retry-After header —
    ...                never a 5xx, never silently accepted.
    [Tags]    auditflow    regression    quota    critical
    ${statuses}=    Create List
    FOR    ${i}    IN RANGE    5
        ${correlation_id}=    Generate Correlation ID
        ${event}=    Build Valid Audit Event    ${correlation_id}
        ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_QUOTA_SESSION}
        Append To List    ${statuses}    ${response.status_code}
        IF    ${response.status_code} == 429
            Should Be Equal As Strings    ${response.json()}[code]    TENANT_RATE_LIMITED
            ...    msg=429 response carried the wrong error code: ${response.text}
            Dictionary Should Contain Key    ${response.headers}    Retry-After
            ...    msg=429 response is missing the Retry-After header.
        ELSE
            Should Be Equal As Integers    ${response.status_code}    200
            ...    msg=Unexpected non-200/429 status ${response.status_code} (body: ${response.text}).
        END
    END
    Should Contain    ${statuses}    ${{429}}
    ...    msg=None of 5 rapid publishes against a burst=2 tenant were rate-limited: ${statuses}

Retry-After is honoured: waiting recovers the bucket
    [Documentation]    After being rate-limited, waiting past Retry-After must allow a
    ...                subsequent publish through — the limiter recovers, it does not lock the
    ...                tenant out permanently.
    [Tags]    auditflow    regression    quota
    FOR    ${i}    IN RANGE    5
        ${correlation_id}=    Generate Correlation ID
        ${event}=    Build Valid Audit Event    ${correlation_id}
        ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_QUOTA_SESSION}
        IF    ${response.status_code} == 429
            BREAK
        END
    END
    Should Be Equal As Integers    ${response.status_code}    429
    ...    msg=Failed to trip the rate limiter within 5 rapid publishes — cannot test recovery.
    ${retry_after}=    Convert To Integer    ${response.headers}[Retry-After]
    Sleep    ${retry_after + 1}s    Wait past the server-declared Retry-After.
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${recovered}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_QUOTA_SESSION}
    Response Status Should Be    ${recovered}    200
