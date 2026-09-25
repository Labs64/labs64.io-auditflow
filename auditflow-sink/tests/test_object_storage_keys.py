"""Object-storage sinks write one object per event, so the object name must be unique per event.

The name used to carry only the first 8 characters of the eventId. Two events of the same second
with the same prefix then got the same name, and the second overwrote the first. With time-ordered
ids (UUIDv7) the first 8 characters are the timestamp, so every event of a second collided.
"""
import pytest

from sinks import aws_s3_sink, azure_blob_sink, gcs_sink

TIMESTAMP = "2026-10-12T09:15:02.123Z"
# UUIDv7 ids of the same millisecond range: identical first 8 characters.
SAME_SECOND_IDS = [
    "0199d8a1-7b2c-7e11-8a4b-2c1d9a6f4e11",
    "0199d8a1-7b2c-7f02-9c3d-5e6f7a8b9c0d",
]

BUILDERS = {
    "aws_s3_sink": lambda event: aws_s3_sink.build_object_key(
        "tenants/acme/", True, "year=%Y/month=%m/day=%d/", "jsonl", True, event),
    "gcs_sink": lambda event: gcs_sink.build_object_name(
        "tenants/acme/", True, "year=%Y/month=%m/day=%d/", True, event),
    "azure_blob_sink": lambda event: azure_blob_sink.build_blob_name(
        "tenants/acme/", True, "year=%Y/month=%m/day=%d/", True, event),
}


def event(event_id):
    return {"eventId": event_id, "tenantId": "acme", "timestamp": TIMESTAMP}


@pytest.mark.parametrize("sink", BUILDERS)
def test_events_of_the_same_second_get_distinct_names(sink):
    names = {BUILDERS[sink](event(i)) for i in SAME_SECOND_IDS}
    assert len(names) == len(SAME_SECOND_IDS)


@pytest.mark.parametrize("sink", BUILDERS)
def test_name_carries_the_full_event_id(sink):
    name = BUILDERS[sink](event(SAME_SECOND_IDS[0]))
    assert name.endswith(f"-{SAME_SECOND_IDS[0]}.json.gz")


def test_s3_redelivery_rewrites_the_same_object():
    # The S3 name uses the event's own timestamp, so a redelivered message rewrites its object
    # instead of adding a copy. (GCS and Azure name objects by upload time.)
    build = BUILDERS["aws_s3_sink"]
    assert build(event(SAME_SECOND_IDS[0])) == build(event(SAME_SECOND_IDS[0]))
