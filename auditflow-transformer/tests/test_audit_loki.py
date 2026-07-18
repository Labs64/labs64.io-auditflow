import pytest
from datetime import datetime, timezone
from transformers import audit_loki

def test_get_log_level():
    assert audit_loki.get_log_level("SUCCESS") == "INFO"
    assert audit_loki.get_log_level("FAILURE") == "ERROR"
    assert audit_loki.get_log_level("PENDING") == "WARN"
    assert audit_loki.get_log_level("unknown") == "UNKNOWN"

def test_transform_basic_event():
    input_data = {
        "timestamp": "2025-07-04T10:00:00Z",
        "eventId": "1234-5678",
        "eventType": "audit.test",
        "sourceSystem": "test-system",
        "tenantId": "t_mock",
        "geolocation": {
            "lat": 1.23,
            "lon": 4.56,
            "countryCode": "US"
        },
        "extra": {
            "action_name": "TEST_ACTION",
            "action_status": "SUCCESS",
            "action_message": "Test successful",
            "userId": "u1"
        }
    }

    result = audit_loki.transform(input_data)
    
    assert "streams" in result
    assert len(result["streams"]) == 1
    stream_obj = result["streams"][0]
    
    assert stream_obj["stream"]["job"] == "auditflow"
    assert stream_obj["stream"]["service_name"] == "test-system"
    assert stream_obj["stream"]["tenant_id"] == "t_mock"
    assert stream_obj["stream"]["event_type"] == "audit.test"
    assert stream_obj["stream"]["action_name"] == "TEST_ACTION"
    assert stream_obj["stream"]["action_status"] == "SUCCESS"
    
    values = stream_obj["values"][0]
    assert len(values) == 3
    
    # 2025-07-04T10:00:00Z in Unix nano
    dt_object = datetime.fromisoformat("2025-07-04T10:00:00+00:00")
    expected_nano = str(int(dt_object.timestamp() * 1_000_000_000))
    assert values[0] == expected_nano
    
    assert values[1] == "Test successful"
    
    val_dict = values[2]
    assert val_dict["eventId"] == "1234-5678"
    assert val_dict["level"] == "INFO"
    assert val_dict["userId"] == "u1"
    assert val_dict["country_code"] == "US"
    assert val_dict["latitude"] == "1.23"
    assert val_dict["longitude"] == "4.56"

def test_transform_empty_event():
    input_data = {}
    result = audit_loki.transform(input_data)
    
    stream_obj = result["streams"][0]
    assert stream_obj["stream"]["service_name"] == "unknown"
    assert stream_obj["stream"]["tenant_id"] == "unknown"
    
    values = stream_obj["values"][0]
    assert values[0] == "0"
    assert values[1] == "N/A"
    
    val_dict = values[2]
    assert val_dict["eventId"] == "N/A"
    assert val_dict["level"] == "UNKNOWN"
