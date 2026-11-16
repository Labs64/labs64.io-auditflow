*** Settings ***
Documentation    POST /audit/publish (operationId publishEvent) request validation at the gateway edge:
...              the AuditEvent schema's limits, the Content-Type and HTTP-method contract, the
...              eventTime window, eventId handling, the response headers, and robustness against
...              hostile or malformed bodies.
...
...              Every case sends one request and asserts one rule, so a failure names the rule that
...              broke. Boundary cases come in pairs: the last valid value is accepted (200) and the
...              first invalid one is refused (400 VALIDATION_ERROR naming the field), which catches
...              a limit drifting in either direction. The limits are the ones in
...              auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml.
...
...              Nothing here needs a fixture beyond the shared t_mock tenant: a rejected request
...              publishes nothing, and the few accepted ones are inert events no pipeline matches.
...              The batch form is covered in batch_publish.robot, authentication in authz.robot,
...              tenancy in tenant_isolation.robot.
Resource         ../../../labs64.io-tests/resources/auditflow.resource
Suite Setup      Create AuditFlow Session
Suite Teardown   Delete All Sessions
Test Tags        auditflow    regression    validation

*** Variables ***
# The two eventTime bounds (auditflow.event-time.max-future = 5 min, max-age = 3 years). The
# probes sit far outside them, so a clock skew between this host and the cluster cannot flip a result.
${ONE_DAY_SECONDS}        86400
${FORTY_YEARS_SECONDS}    1261440000

# Request bodies that are not valid JSON, or not a JSON object.
${TRUNCATED_JSON}         {"eventType":
${JSON_ARRAY}             [1, 2]
${JSON_STRING}            "just a string"
${JSON_NULL}              null

*** Test Cases ***
# --- field limits: the last valid value is accepted ------------------------------------------------

eventType of 255 characters is accepted
    [Template]    Event With Field Should Be Accepted
    eventType    ${{'a' * 255}}

sourceSystem of 255 characters is accepted
    [Template]    Event With Field Should Be Accepted
    sourceSystem    ${{'a' * 255}}

correlationId of 64 characters is accepted
    [Template]    Event With Field Should Be Accepted
    correlationId    ${{'a' * 64}}

geolocation at the coordinate limits is accepted
    [Template]    Event With Field Should Be Accepted
    geolocation    ${{ {'lat': 90, 'lon': 180} }}

geolocation at the negative coordinate limits is accepted
    [Template]    Event With Field Should Be Accepted
    geolocation    ${{ {'lat': -90, 'lon': -180} }}

geolocation with a two-letter countryCode is accepted
    [Template]    Event With Field Should Be Accepted
    geolocation    ${{ {'lat': 0, 'lon': 0, 'countryCode': 'DE'} }}

# --- field limits: the first invalid value is refused ----------------------------------------------

eventType of 256 characters is rejected
    [Template]    Event With Field Should Be Rejected
    eventType    ${{'a' * 256}}    eventType

eventType empty is rejected
    [Template]    Event With Field Should Be Rejected
    eventType    ${EMPTY}    eventType

eventType null is rejected
    [Template]    Event With Field Should Be Rejected
    eventType    ${NONE}    eventType

sourceSystem of 256 characters is rejected
    [Template]    Event With Field Should Be Rejected
    sourceSystem    ${{'a' * 256}}    sourceSystem

sourceSystem empty is rejected
    [Template]    Event With Field Should Be Rejected
    sourceSystem    ${EMPTY}    sourceSystem

sourceSystem missing is rejected
    [Template]    Event Without Field Should Be Rejected
    sourceSystem    sourceSystem

eventType missing is rejected
    [Template]    Event Without Field Should Be Rejected
    eventType    eventType

correlationId of 65 characters is rejected
    [Template]    Event With Field Should Be Rejected
    correlationId    ${{'a' * 65}}    correlationId

tenantId of 65 characters is rejected
    [Template]    Event With Field Should Be Rejected
    tenantId    ${{'a' * 65}}    tenantId

tenantId starting with a separator is rejected
    [Template]    Event With Field Should Be Rejected
    tenantId    -leading-dash    tenantId

tenantId with a space is rejected
    [Template]    Event With Field Should Be Rejected
    tenantId    has a space    tenantId

tenantId with a path separator is rejected
    [Template]    Event With Field Should Be Rejected
    tenantId    t_mock/../t_other    tenantId

geolocation latitude above 90 is rejected
    [Template]    Event With Field Should Be Rejected
    geolocation    ${{ {'lat': 91, 'lon': 0} }}    geolocation.lat

geolocation latitude below -90 is rejected
    [Template]    Event With Field Should Be Rejected
    geolocation    ${{ {'lat': -91, 'lon': 0} }}    geolocation.lat

geolocation longitude above 180 is rejected
    [Template]    Event With Field Should Be Rejected
    geolocation    ${{ {'lat': 0, 'lon': 181} }}    geolocation.lon

geolocation longitude below -180 is rejected
    [Template]    Event With Field Should Be Rejected
    geolocation    ${{ {'lat': 0, 'lon': -181} }}    geolocation.lon

geolocation countryCode of three letters is rejected
    [Template]    Event With Field Should Be Rejected
    geolocation    ${{ {'lat': 0, 'lon': 0, 'countryCode': 'DEU'} }}    geolocation.countryCode

geolocation countryCode of one letter is rejected
    [Template]    Event With Field Should Be Rejected
    geolocation    ${{ {'lat': 0, 'lon': 0, 'countryCode': 'D'} }}    geolocation.countryCode

extra that is not an object is rejected
    [Template]    Event With Field Should Be Rejected
    extra    just a string    Malformed

# --- eventTime window -------------------------------------------------------------------------------

eventTime in the recent past is accepted
    [Documentation]    eventTime is the publisher's claim of when the action happened; an hour ago
    ...                is well inside the accepted window.
    ${event_time}=    Get UTC Timestamp    -3600
    Event With Field Should Be Accepted    eventTime    ${event_time}

eventTime far in the future is rejected
    [Documentation]    More than max-future (5 min) after the receipt time: 400, naming eventTime.
    ${event_time}=    Get UTC Timestamp    ${ONE_DAY_SECONDS}
    Event With Field Should Be Rejected    eventTime    ${event_time}    eventTime

eventTime far in the past is rejected
    [Documentation]    Older than max-age (3 years): 400, naming eventTime.
    ${event_time}=    Get UTC Timestamp    -${FORTY_YEARS_SECONDS}
    Event With Field Should Be Rejected    eventTime    ${event_time}    eventTime

eventTime that is not a timestamp is rejected
    [Template]    Event With Field Should Be Rejected
    eventTime    not-a-date    Malformed

# --- eventId and the response headers --------------------------------------------------------------

Server generates a unique eventId when none is supplied
    [Documentation]    Without an eventId the response carries a server-generated UUID in
    ...                X-Audit-Event-Id, and two events never share one.
    ${first}=    Publish Event Without Event Id
    ${second}=    Publish Event Without Event Id
    Should Be A UUID    ${first}
    Should Be A UUID    ${second}
    Should Not Be Equal    ${first}    ${second}
    ...    msg=Two events without an eventId were given the same id '${first}'.

Supplied eventId is echoed in X-Audit-Event-Id
    ${event_id}=    Evaluate    str(__import__('uuid').uuid4())
    ${event}=    Build Valid Audit Event    ${event_id}
    Set To Dictionary    ${event}    eventId=${event_id}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    Should Be Equal As Strings    ${response.headers}[X-Audit-Event-Id]    ${event_id}

Retrying with the same eventId is accepted and keeps the id
    [Documentation]    The batch contract tells publishers to keep an event's eventId when retrying so
    ...                a retry never creates a duplicate. The single operation must accept that retry
    ...                and answer with the same id both times.
    ${event_id}=    Evaluate    str(__import__('uuid').uuid4())
    ${event}=    Build Valid Audit Event    ${event_id}
    Set To Dictionary    ${event}    eventId=${event_id}
    ${first}=    Publish Audit Event    ${event}
    ${second}=    Publish Audit Event    ${event}
    Response Status Should Be    ${first}    200
    Response Status Should Be    ${second}    200
    Should Be Equal As Strings    ${first.headers}[X-Audit-Event-Id]    ${event_id}
    Should Be Equal As Strings    ${second.headers}[X-Audit-Event-Id]    ${event_id}

eventId that is not a UUID is rejected
    [Template]    Event With Field Should Be Rejected
    eventId    not-a-uuid    Malformed

Receipt time is server-assigned and ignores a client timestamp
    [Documentation]    timestamp is readOnly: a publisher cannot backdate the receipt time. The
    ...                X-Audit-Received-At header (the same value the event carries as timestamp) is
    ...                the server's clock, not the year-2001 value sent.
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    Set To Dictionary    ${event}    timestamp=2001-01-01T00:00:00Z
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    Timestamp Should Be Recent    ${response.headers}[X-Audit-Received-At]

Response carries a valid receipt time
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    Timestamp Should Be Recent    ${response.headers}[X-Audit-Received-At]

# --- payloads that must be accepted ----------------------------------------------------------------

Fully populated event is accepted
    [Documentation]    Every well-known field at once, with a valid value each.
    ${correlation_id}=    Generate Correlation ID
    ${event_id}=    Evaluate    str(__import__('uuid').uuid4())
    ${event_time}=    Get UTC Timestamp    -3600
    ${geolocation}=    Create Dictionary    lat=${48.1351}    lon=${11.582}    countryCode=DE
    ...    country=Germany    region=BY    city=Munich
    ${extra}=    Create Dictionary    userId=test-suite    action_name=test.full
    ${context}=    Create Dictionary
    ${event}=    Create Dictionary
    ...    eventType=api.call    sourceSystem=labs64.io-tests    eventId=${event_id}
    ...    eventTime=${event_time}    correlationId=${correlation_id}
    ...    geolocation=${geolocation}    extra=${extra}    context=${context}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    Should Be Equal As Strings    ${response.headers}[X-Audit-Event-Id]    ${event_id}

Minimal event with only the required fields is accepted
    [Documentation]    The spec's ``minimal`` example: eventType and sourceSystem, no extra at all.
    ${event}=    Create Dictionary    eventType=api.call    sourceSystem=netlicensing/core
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200

Unicode in event content is accepted
    [Documentation]    Raw UTF-8 (not escape sequences) in extra keys and values: non-Latin scripts,
    ...                symbols and an emoji outside the Basic Multilingual Plane.
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    ${extra}=    Create Dictionary    名前=テスト ✓    emoji=🚀    umlaut=Grüße
    Set To Dictionary    ${event}    extra=${extra}
    ${body}=    Evaluate    json.dumps($event, ensure_ascii=False).encode('utf-8')    modules=json
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish    ${body}
    Response Status Should Be    ${response}    200

Nested structures and nulls in extra are accepted
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    ${extra}=    Evaluate    {'nested': {'list': [1, 2, {'deep': None}]}, 'nothing': None, 'flag': True}
    Set To Dictionary    ${event}    extra=${extra}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200

Injection-like strings are stored as data and not interpreted
    [Documentation]    Markup and SQL fragments in event fields are plain text to AuditFlow: accepted
    ...                without an error, and without the request being treated as anything else.
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    Set To Dictionary    ${event}
    ...    eventType=<script>alert(1)</script>
    ...    sourceSystem=x'; DROP TABLE audit;--
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200

Large extra payload under the size limit is accepted
    [Documentation]    About 200 KB of content: far below the gateway's body limit and the brokers'
    ...                message limits, so it must not be refused.
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    ${extra}=    Evaluate    {'blob': 'x' * 200000}
    Set To Dictionary    ${event}    extra=${extra}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200

# --- malformed and hostile bodies: 400, never a server error ---------------------------------------

Truncated JSON is rejected
    [Template]    Body Should Be Rejected As Malformed
    ${TRUNCATED_JSON}

Empty body is rejected
    [Template]    Body Should Be Rejected As Malformed
    ${EMPTY}

JSON array instead of an object is rejected
    [Template]    Body Should Be Rejected As Malformed
    ${JSON_ARRAY}

JSON string instead of an object is rejected
    [Template]    Body Should Be Rejected As Malformed
    ${JSON_STRING}

JSON null is rejected
    [Template]    Body Should Be Rejected As Malformed
    ${JSON_NULL}

Empty object is rejected naming both required fields
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish    {}
    Response Should Be Error Response    ${response}    400    VALIDATION_ERROR
    Should Contain    ${response.json()}[message]    eventType
    Should Contain    ${response.json()}[message]    sourceSystem

Invalid UTF-8 is rejected
    ${body}=    Evaluate    b'{"eventType":"a' + bytes([255, 254]) + b'","sourceSystem":"p"}'
    Body Should Be Rejected As Malformed    ${body}

Deeply nested JSON is rejected, not a server error
    [Documentation]    20 000 nested arrays would exhaust the stack of a naive parser. The parser's
    ...                nesting limit must turn it into a 400.
    ${body}=    Evaluate
    ...    '{"eventType":"a","sourceSystem":"p","extra":{"d":' + '[' * 20000 + ']' * 20000 + '}}'
    Body Should Be Rejected As Malformed    ${body}

# --- Content-Type -----------------------------------------------------------------------------------

JSON with a charset parameter is accepted
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    ${body}=    Evaluate    json.dumps($event)    modules=json
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish
    ...    ${body}    application/json; charset=utf-8
    Response Status Should Be    ${response}    200

text/plain is refused with 415
    [Template]    Content Type Should Be Refused
    text/plain

application/xml is refused with 415
    [Template]    Content Type Should Be Refused
    application/xml

A form post is refused with 415
    [Template]    Content Type Should Be Refused
    application/x-www-form-urlencoded

A missing Content-Type is refused with 415
    [Template]    Content Type Should Be Refused
    ${NONE}

# --- HTTP methods and paths the contract does not declare ------------------------------------------

GET on the publish path is denied
    [Template]    Undeclared Request Should Be Denied
    GET    /audit/publish

PUT on the publish path is denied
    [Template]    Undeclared Request Should Be Denied
    PUT    /audit/publish

PATCH on the publish path is denied
    [Template]    Undeclared Request Should Be Denied
    PATCH    /audit/publish

DELETE on the publish path is denied
    [Template]    Undeclared Request Should Be Denied
    DELETE    /audit/publish

GET on the batch path is denied
    [Template]    Undeclared Request Should Be Denied
    GET    /audit/publish/batch

PUT on the batch path is denied
    [Template]    Undeclared Request Should Be Denied
    PUT    /audit/publish/batch

A path the contract does not declare is denied
    [Documentation]    An authenticated call to a made-up path under the module prefix must not reach
    ...                the backend and must not distinguish "no such route" from "not allowed".
    [Template]    Undeclared Request Should Be Denied
    POST    /audit/does-not-exist

An anonymous call on an undeclared method is refused
    [Documentation]    No credentials and no policy for the method: still refused (401 or 403), never
    ...                served and never an error.
    Create Unauthenticated AuditFlow Session
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    GET    /audit/publish
    Should Be True    ${response.status_code} in (401, 403)
    ...    msg=Anonymous GET /audit/publish returned ${response.status_code}, expected 401 or 403. Body: ${response.text}
    Response Should Not Leak Internals    ${response}

*** Keywords ***
Build Event With Field
    [Documentation]    A valid audit event whose ``${field}`` is set to ``${value}``. ``${NONE}`` sends
    ...                an explicit JSON null; ``${EMPTY}`` an empty string.
    [Arguments]    ${field}    ${value}
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    Set To Dictionary    ${event}    ${field}=${value}
    RETURN    ${event}

Event With Field Should Be Accepted
    [Arguments]    ${field}    ${value}
    ${event}=    Build Event With Field    ${field}    ${value}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    Should Be A UUID    ${response.headers}[X-Audit-Event-Id]

Event With Field Should Be Rejected
    [Documentation]    400 VALIDATION_ERROR whose message names ``${message_fragment}`` (the field).
    [Arguments]    ${field}    ${value}    ${message_fragment}
    ${event}=    Build Event With Field    ${field}    ${value}
    ${response}=    Publish Audit Event    ${event}
    Response Should Be Error Response    ${response}    400    VALIDATION_ERROR
    Should Contain    ${response.json()}[message]    ${message_fragment}
    ...    msg=The 400 does not name '${message_fragment}': ${response.text}

Event Without Field Should Be Rejected
    [Arguments]    ${field}    ${message_fragment}
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    Remove From Dictionary    ${event}    ${field}
    ${response}=    Publish Audit Event    ${event}
    Response Should Be Error Response    ${response}    400    VALIDATION_ERROR
    Should Contain    ${response.json()}[message]    ${message_fragment}
    ...    msg=The 400 does not name '${message_fragment}': ${response.text}

Publish Event Without Event Id
    [Documentation]    Publish a valid event that carries no eventId and return the id the server gave it.
    ${correlation_id}=    Generate Correlation ID
    ${event}=    Build Valid Audit Event    ${correlation_id}
    ${response}=    Publish Audit Event    ${event}
    Response Status Should Be    ${response}    200
    RETURN    ${response.headers}[X-Audit-Event-Id]

Body Should Be Rejected As Malformed
    [Documentation]    POST ``${body}`` as application/json: 400 VALIDATION_ERROR, never a 5xx.
    [Arguments]    ${body}
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish    ${body}
    Response Should Be Error Response    ${response}    400    VALIDATION_ERROR

Content Type Should Be Refused
    [Documentation]    A valid event sent with an unsupported Content-Type (``${NONE}`` for none) is 415
    ...                VALIDATION_ERROR: the operation accepts application/json only.
    [Arguments]    ${content_type}
    ${event}=    Build Valid Audit Event    ${{__import__('uuid').uuid4().hex}}
    ${body}=    Evaluate    json.dumps($event)    modules=json
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    POST    /audit/publish    ${body}    ${content_type}
    Response Should Be Error Response    ${response}    415    VALIDATION_ERROR

Undeclared Request Should Be Denied
    [Documentation]    The edge has no authorization policy for this method and path, so it fails
    ...                closed: 403 (or 405 should a policy ever declare the method), never a 2xx, a
    ...                404 and never a server error — and the body gives nothing of the
    ...                implementation away. The contract lists a 405 for these methods; today the edge
    ...                answers before the backend can.
    [Arguments]    ${method}    ${path}
    ${body}=    Set Variable If    '${method}' in ('POST', 'PUT', 'PATCH')    {}    ${NONE}
    ${response}=    Send Raw Request    ${AUDITFLOW_SESSION_ALIAS}    ${method}    ${path}    ${body}
    Should Be True    ${response.status_code} in (403, 405)
    ...    msg=${method} ${path} returned ${response.status_code}; an undeclared request must be denied (403/405). Body: ${response.text}
    Response Should Not Leak Internals    ${response}
