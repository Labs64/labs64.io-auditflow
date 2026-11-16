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
Test Teardown    Sleep    3s    Let the token bucket (1/s, burst 2) refill completely so the next test starts from a full bucket.

*** Test Cases ***
Exceeding the burst is rejected with 429 and Retry-After
    [Documentation]    Publish past the tenant's burst capacity (2) and confirm the ingest gate
    ...                rejects the excess with 429 TENANT_RATE_LIMITED and a Retry-After header —
    ...                never a 5xx, never silently accepted.
    [Tags]    auditflow    regression    quota
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

A rate-limited publish is a well-formed error with a usable Retry-After
    [Documentation]    The 429 is the contract's ErrorResponse with code TENANT_RATE_LIMITED, and its
    ...                Retry-After is a whole number of seconds of at least 1 — something a client can
    ...                sleep on, not a date, not zero.
    [Tags]    auditflow    regression    quota    error-handling
    ${response}=    Trip The Rate Limiter
    Response Should Be Error Response    ${response}    429    TENANT_RATE_LIMITED
    Should Match Regexp    ${response.headers}[Retry-After]    ^[1-9][0-9]*$
    ...    msg=Retry-After '${response.headers}[Retry-After]' is not a positive whole number of seconds.

A rate-limited batch rejects the excess event by event
    [Documentation]    Ten events in one batch against a burst of 2: the request is still processed (200),
    ...                the first event gets through the full bucket, the excess is REJECTED per event
    ...                with TENANT_RATE_LIMITED, and the response carries Retry-After for the retry.
    [Tags]    auditflow    regression    quota    batch
    ${events}=    Build Valid Audit Event Batch    10
    ${response}=    Publish Audit Event Batch    ${events}    alias=${AUDITFLOW_REGRESSION_QUOTA_SESSION}
    ${body}=    Batch Response Should Be Consistent    ${response}    10
    Should Be Equal As Strings    ${body}[results][0][status]    ACCEPTED
    ...    msg=The first event of a batch against a full bucket was not accepted.
    Should Be True    ${body}[rejectedCount] >= 1
    ...    msg=None of 10 events in one batch were rate-limited against a burst=2 tenant: ${body}
    FOR    ${result}    IN    @{body}[results]
        IF    "${result}[status]" == "REJECTED"
            Should Be Equal As Strings    ${result}[error][code]    TENANT_RATE_LIMITED
            ...    msg=A rate-limited event carried the wrong error code: ${result}
        END
    END
    Should Match Regexp    ${response.headers}[Retry-After]    ^[1-9][0-9]*$
    ...    msg=A batch with rate-limited events must carry a Retry-After header.

One tenant's exhausted quota does not throttle another tenant
    [Documentation]    While t_regression_quota is being rate-limited, t_mock — a different tenant on
    ...                the same AuditFlow instance — publishes without any hindrance: quotas are
    ...                per tenant, never shared.
    [Tags]    auditflow    regression    quota    tenant-isolation
    ${limited}=    Trip The Rate Limiter
    Should Be Equal As Integers    ${limited.status_code}    429
    Create AuditFlow Session
    FOR    ${index}    IN RANGE    3
        ${correlation_id}=    Generate Correlation ID
        ${event}=    Build Valid Audit Event    ${correlation_id}
        ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_SESSION_ALIAS}
        Response Status Should Be    ${response}    200
    END

*** Keywords ***
Trip The Rate Limiter
    [Documentation]    Publish rapidly as t_regression_quota until the ingest gate answers 429, and return
    ...                that response. Fails if five publishes against a burst of 2 are never limited.
    FOR    ${index}    IN RANGE    5
        ${correlation_id}=    Generate Correlation ID
        ${event}=    Build Valid Audit Event    ${correlation_id}
        ${response}=    Publish Audit Event    ${event}    alias=${AUDITFLOW_REGRESSION_QUOTA_SESSION}
        IF    ${response.status_code} == 429    RETURN    ${response}
    END
    Fail    Five rapid publishes against a burst=2 tenant were never rate-limited (last status ${response.status_code}).
