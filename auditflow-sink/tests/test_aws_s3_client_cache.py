"""aws_s3_sink builds an S3 client once per configuration, not once per event."""
import pytest

from sinks import aws_s3_sink

PROPS = {"bucket": "b", "prefix": "tenants/", "region": "eu-west-1"}
EVENT = {"eventId": "e1", "tenantId": "acme", "timestamp": "2026-10-02T14:26:39Z"}


class FakeClient:
    def put_object(self, **kwargs):
        return {"ETag": '"x"'}


@pytest.fixture(autouse=True)
def fresh_cache(monkeypatch):
    aws_s3_sink._CLIENTS.clear()
    created = []
    monkeypatch.setattr(aws_s3_sink.boto3, "client", lambda *a, **kw: created.append((a, kw)) or FakeClient())
    yield created
    aws_s3_sink._CLIENTS.clear()


def test_client_is_reused_across_events(fresh_cache):
    for _ in range(5):
        assert aws_s3_sink.process(dict(EVENT), PROPS)["sent"] is True
    assert len(fresh_cache) == 1


def test_different_region_or_endpoint_gets_its_own_client(fresh_cache):
    aws_s3_sink.process(dict(EVENT), PROPS)
    aws_s3_sink.process(dict(EVENT), {**PROPS, "region": "us-east-1"})
    aws_s3_sink.process(dict(EVENT), {**PROPS, "endpoint-url": "http://minio:9000"})
    assert len(fresh_cache) == 3


def test_credentials_and_endpoint_are_passed_to_boto3(fresh_cache):
    aws_s3_sink.process(dict(EVENT), {**PROPS, "access-key-id": "AK", "secret-access-key": "SK",
                                      "endpoint-url": "http://minio:9000"})
    (args, kwargs), = fresh_cache
    assert args == ("s3",)
    assert kwargs == {"region_name": "eu-west-1", "aws_access_key_id": "AK",
                      "aws_secret_access_key": "SK", "endpoint_url": "http://minio:9000"}


def test_default_credential_chain_when_no_keys(fresh_cache):
    aws_s3_sink.process(dict(EVENT), PROPS)
    (_, kwargs), = fresh_cache
    assert kwargs == {"region_name": "eu-west-1"}


def test_cache_is_bounded(fresh_cache):
    for i in range(aws_s3_sink._MAX_CLIENTS + 5):
        aws_s3_sink._get_s3_client(f"r{i}", None, None, None)
    assert len(aws_s3_sink._CLIENTS) <= aws_s3_sink._MAX_CLIENTS
