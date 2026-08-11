import json

from transformers import audit_clickhouse

FULL_EVENT = {
    "timestamp": "2026-08-07T10:15:30Z",
    "eventTime": "2026-08-07T10:14:55Z",
    "eventId": "fedcba98-7654-3210-fedc-ba9876543210",
    "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "eventType": "api.call",
    "sourceSystem": "netlicensing/core",
    "tenantId": "V12345678",
    "geolocation": {
        "lat": 48.1264019,
        "lon": 11.5407647,
        "countryCode": "DE",
        "country": "Germany",
        "region": "Bavaria",
        "city": "Munich",
    },
    "extra": {
        "userId": "customer123",
        "actionName": "login",
        "actionStatus": "SUCCESS",
        "actionMessage": "User logged in successfully",
        "sessionId": "sess456",
        "durationMs": 34,
        "responseStatus": 200,
        # Outside the well-known vocabulary — must survive in the map column.
        "invoiceRef": "INV-1",
    },
}


def test_maps_every_top_level_field():
    row = audit_clickhouse.transform(FULL_EVENT)

    assert row["timestamp"] == "2026-08-07T10:15:30Z"
    assert row["event_time"] == "2026-08-07T10:14:55Z"
    assert row["event_id"] == "fedcba98-7654-3210-fedc-ba9876543210"
    assert row["correlation_id"] == "f47ac10b-58cc-4372-a567-0e02b2c3d479"
    assert row["event_type"] == "api.call"
    assert row["source_system"] == "netlicensing/core"
    assert row["tenant_id"] == "V12345678"


def test_flattens_geolocation():
    row = audit_clickhouse.transform(FULL_EVENT)

    assert row["geo_lat"] == 48.1264019
    assert row["geo_lon"] == 11.5407647
    assert row["geo_country_code"] == "DE"
    assert row["geo_country"] == "Germany"
    assert row["geo_region"] == "Bavaria"
    assert row["geo_city"] == "Munich"


def test_promotes_well_known_extra_keys_and_removes_them_from_extra():
    row = audit_clickhouse.transform(FULL_EVENT)

    assert row["action_name"] == "login"
    assert row["action_status"] == "SUCCESS"
    assert row["action_message"] == "User logged in successfully"
    assert row["user_id"] == "customer123"
    assert row["session_id"] == "sess456"
    assert row["duration_ms"] == 34
    assert row["response_status"] == 200
    # Promoted keys must not be duplicated into the map column; unknown ones must stay in it.
    assert row["extra"] == {"invoiceRef": "INV-1"}


def test_missing_geolocation_yields_null_coordinates_not_zero():
    # (0, 0) is a real coordinate, so absent lat/lon must be null, never 0.
    row = audit_clickhouse.transform({"eventType": "api.call", "sourceSystem": "svc"})

    assert row["geo_lat"] is None
    assert row["geo_lon"] is None


def test_event_time_falls_back_to_timestamp():
    row = audit_clickhouse.transform({
        "timestamp": "2026-08-07T10:15:30Z",
        "eventType": "api.call",
        "sourceSystem": "svc",
    })

    assert row["event_time"] == "2026-08-07T10:15:30Z"


def test_absent_scalars_are_omitted_so_clickhouse_applies_defaults():
    # Emitting null into a non-Nullable column fails the insert; omitting the key lets
    # input_format_defaults_for_omitted_fields apply the column DEFAULT.
    row = audit_clickhouse.transform({"eventType": "api.call", "sourceSystem": "svc"})

    assert "event_id" not in row
    assert "correlation_id" not in row
    assert "tenant_id" not in row
    assert "geo_city" not in row


def test_extra_values_are_all_strings():
    row = audit_clickhouse.transform({
        "eventType": "api.call",
        "sourceSystem": "svc",
        "extra": {"count": 3, "ratio": 1.5, "ok": True, "name": "x"},
    })

    assert all(isinstance(value, str) for value in row["extra"].values())
    assert row["extra"]["count"] == "3"
    assert row["extra"]["ratio"] == "1.5"
    assert row["extra"]["ok"] == "true"
    assert row["extra"]["name"] == "x"


def test_non_scalar_extra_values_are_json_encoded_not_dropped():
    row = audit_clickhouse.transform({
        "eventType": "api.call",
        "sourceSystem": "svc",
        "extra": {"cart": {"total": 42, "items": 2}},
    })

    assert json.loads(row["extra"]["cart"]) == {"total": 42, "items": 2}


def test_empty_extra_still_emits_an_empty_map():
    row = audit_clickhouse.transform({"eventType": "api.call", "sourceSystem": "svc"})

    assert row["extra"] == {}


# ── make_transform: the extension point a use-case layer builds on ───────────────────────────────
# The worked example (examples/netlicensing/) is covered by test_audit_clickhouse_netlicensing.py;
# these cover the mechanism itself.

def test_make_transform_promotes_a_domain_key_into_its_own_column():
    domain_transform = audit_clickhouse.make_transform({"orderRef": "order_ref"})
    row = domain_transform({"eventType": "order.placed", "extra": {"orderRef": "ORD-1"}})

    assert row["order_ref"] == "ORD-1"
    # Promoted, so it must no longer be duplicated in the map column.
    assert row["extra"] == {}
    # ...and the generic vocabulary is still promoted alongside it.
    assert set(audit_clickhouse.transform.promoted) <= set(domain_transform.promoted)


def test_make_transform_does_not_mutate_the_generic_vocabulary():
    # A domain layer is additive: building one must not change what any other pipeline promotes.
    audit_clickhouse.make_transform({"orderRef": "order_ref"})

    assert "orderRef" not in audit_clickhouse.transform.promoted
    row = audit_clickhouse.transform({"eventType": "order.placed", "extra": {"orderRef": "ORD-1"}})
    assert "order_ref" not in row
    assert row["extra"] == {"orderRef": "ORD-1"}


def test_make_transform_lets_a_domain_retarget_a_generic_column():
    # Last mapping wins, so a use case can redirect a generic key without forking the module.
    domain_transform = audit_clickhouse.make_transform({"userId": "actor_id"})
    row = domain_transform({"eventType": "api.call", "extra": {"userId": "customer123"}})

    assert row["actor_id"] == "customer123"
    assert "user_id" not in row
