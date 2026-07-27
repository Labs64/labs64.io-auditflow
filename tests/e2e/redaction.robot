*** Settings ***
Documentation    PII redaction (item 12): applied globally, at ingest, before publish (see
...              RedactionService) — masking `extra.redact_target` is enabled in
...              overrides/auditflow/values.local.yaml, scoped to a field name no other suite's
...              payload ever uses so it has zero effect on any other test.
...
...              Redaction mutates event CONTENT before the event reaches the broker; nothing in
...              the synchronous POST /audit/publish response echoes that content back, so its
...              effect is not observable through the response the way an HTTP status is. The
...              gateway-edge case below (CI-safe, runs everywhere) proves redaction does not
...              break publishing; the local-k8s-only case additionally corroborates against the
...              sink's own log output that the raw value never appears and the mask does — the
...              same "no kubectl in CI" exception already used by tenant_isolation.robot and
...              authz.robot, for the same reason: proving an in-flight content transformation
...              genuinely requires seeing content somewhere.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create AuditFlow Session
Suite Teardown   Delete All Sessions

*** Test Cases ***
Publishing a redaction-target field does not break ingest
    [Documentation]    CI-safe baseline: an event carrying the configured redaction field is
    ...                accepted normally. Does not prove the value was actually masked — see the
    ...                local-k8s corroboration below for that.
    [Tags]    auditflow    regression    redaction
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    Set To Dictionary    ${event}[extra]    redact_target=super-secret-raw-value
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200

Redaction masks the configured field before delivery (local-k8s)
    [Documentation]    Publishes a uniquely-tagged sensitive value and asserts, via the sink
    ...                container's own log output (logging_sink prints the delivered event JSON),
    ...                that the raw value never appears and the configured mask does — proving
    ...                the mutation happens before publish, not merely that it's configured.
    [Tags]    auditflow    regression    redaction    local-k8s-only
    Skip Unless Local Kubernetes
    ${correlation_id}=    Generate Correlation ID
    ${uuid}=    Evaluate    __import__('uuid').uuid4().hex[:16]
    ${raw_value}=    Set Variable    unredacted-secret-${uuid}
    ${event}=    Build Valid Audit Event    ${correlation_id}
    Set To Dictionary    ${event}[extra]    redact_target=${raw_value}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    AuditFlow Backend Logs Should Contain Correlation Id    ${correlation_id}
    ${logs}=    Fetch Recent Pod Logs    ${LABS64IO_K8S_NAMESPACE}    ${AUDITFLOW_K8S_DEPLOYMENT}    90s
    Should Not Contain    ${logs}    ${raw_value}
    ...    msg=Raw redaction-target value '${raw_value}' appeared in pod logs — redaction did not mask it before delivery.
    Should Contain    ${logs}    "redact_target": "***"
    ...    msg=Expected the configured mask '***' in place of extra.redact_target; not found in pod logs.
