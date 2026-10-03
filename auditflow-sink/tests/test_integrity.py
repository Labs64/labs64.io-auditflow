"""Tamper-evidence: digest chains (integrity.py), the S3 sink's digest=true, and the verifier."""
import base64
import hashlib
import io
import json
import sys
import pathlib

import pytest

import integrity
from sinks import aws_s3_sink

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent / "scripts"))
import verify_s3_digests  # noqa: E402


class FakeS3:
    """Just enough S3 for the sink and the verifier: put/get/head/list with stored SHA-256 checksums."""

    class exceptions:  # noqa: N801 - mirrors boto3's client.exceptions
        class ClientError(Exception):
            def __init__(self, code):
                super().__init__(code)
                self.response = {"Error": {"Code": code}}

    def __init__(self):
        self.objects = {}
        self.puts = []

    def put_object(self, Bucket, Key, Body, ContentType=None, Metadata=None, ChecksumAlgorithm=None):  # noqa: N803
        self.objects[Key] = Body
        self.puts.append({"Key": Key, "ChecksumAlgorithm": ChecksumAlgorithm})
        return {"ETag": '"x"'}

    def get_object(self, Bucket, Key):  # noqa: N803
        return {"Body": io.BytesIO(self.objects[Key])}

    def head_object(self, Bucket, Key, ChecksumMode=None):  # noqa: N803
        if Key not in self.objects:
            raise self.exceptions.ClientError("404")
        return {"ChecksumSHA256": base64.b64encode(hashlib.sha256(self.objects[Key]).digest()).decode()}

    def get_paginator(self, name):
        s3 = self

        class Paginator:
            def paginate(self, Bucket, Prefix):  # noqa: N803
                yield {"Contents": [{"Key": k} for k in sorted(s3.objects) if k.startswith(Prefix)]}
        return Paginator()


@pytest.fixture
def keys():
    seed, public = integrity.generate_key_pair()
    return seed, integrity.load_public_key(public)


@pytest.fixture
def s3(monkeypatch):
    fake = FakeS3()
    monkeypatch.setattr(aws_s3_sink, "_get_s3_client", lambda *a: fake)
    monkeypatch.setattr(aws_s3_sink, "_DIGESTS", integrity.DigestChains())
    return fake


def event(i, tenant="acme"):
    return {"eventId": f"e{i}", "tenantId": tenant, "timestamp": "2026-10-02T14:26:39Z", "extra": {"n": i}}


def props(seed, **extra):
    return {"bucket": "b", "prefix": "tenants/", "digest": "true", "digest-signing-key": seed, **extra}


# --- integrity.py ---

def test_a_chain_links_records_and_signs_them(keys):
    seed, public = keys
    chains = integrity.DigestChains()
    written = {}
    put = written.__setitem__
    for i in range(3):
        chains.record(bucket="b", digest_prefix="p/_digests", object_key=f"o{i}", body=b"x" * i, event_ids=[f"e{i}"],
                      signing_key=integrity.load_signing_key(seed), put=put)
    records = [json.loads(written[k]) for k in sorted(written)]
    assert [r["sequence"] for r in records] == [0, 1, 2]
    assert records[0]["previousDigestSha256"] is None
    assert integrity.verify_chain(records, public) == []


def test_tampering_is_detected(keys):
    seed, public = keys
    chains = integrity.DigestChains()
    written = {}
    for i in range(3):
        chains.record(bucket="b", digest_prefix="p/_digests", object_key=f"o{i}", body=b"x", event_ids=[],
                      signing_key=integrity.load_signing_key(seed), put=written.__setitem__)
    records = [json.loads(written[k]) for k in sorted(written)]

    altered = [dict(r) for r in records]
    altered[1] = {**altered[1], "object": {**altered[1]["object"], "eventCount": 99}}
    assert any("signature" in p for p in integrity.verify_chain(altered, public))

    removed = [records[0], records[2]]
    problems = integrity.verify_chain(removed, public)
    assert any("expected sequence 1" in p for p in problems)
    assert any("previous-digest" in p for p in problems)

    _, other_public = integrity.generate_key_pair()
    assert any("signature" in p for p in integrity.verify_chain(records, integrity.load_public_key(other_public)))


def test_a_failed_put_does_not_advance_the_chain(keys):
    seed, public = keys
    chains = integrity.DigestChains()
    written = {}

    def flaky(key, data):
        raise RuntimeError("SlowDown")

    with pytest.raises(RuntimeError):
        chains.record(bucket="b", digest_prefix="p", object_key="o0", body=b"x", event_ids=[],
                      signing_key=integrity.load_signing_key(seed), put=flaky)
    chains.record(bucket="b", digest_prefix="p", object_key="o0", body=b"x", event_ids=[],
                  signing_key=integrity.load_signing_key(seed), put=written.__setitem__)
    assert json.loads(next(iter(written.values())))["sequence"] == 0


@pytest.mark.parametrize("bad", ["", "not base64!", base64.b64encode(b"short").decode()])
def test_invalid_signing_keys_are_rejected(bad):
    with pytest.raises(ValueError):
        integrity.load_signing_key(bad)


# --- S3 sink ---

def test_objects_get_an_s3_checksum_by_default(s3):
    aws_s3_sink.process(event(1), {"bucket": "b", "prefix": "tenants/"})
    assert s3.puts[0]["ChecksumAlgorithm"] == "SHA256"
    aws_s3_sink.process(event(2), {"bucket": "b", "prefix": "tenants/", "checksum": "none"})
    assert s3.puts[1]["ChecksumAlgorithm"] is None


def test_digest_records_are_per_tenant_chains_under_the_tenant_prefix(s3, keys):
    seed, public = keys
    aws_s3_sink.process(event(1), props(seed))
    aws_s3_sink.process_batch([event(2), event(3)], props(seed))
    aws_s3_sink.process(event(4, tenant="globex"), props(seed))

    acme = sorted(k for k in s3.objects if k.startswith("tenants/tenant=acme/_digests/"))
    globex = [k for k in s3.objects if k.startswith("tenants/tenant=globex/_digests/")]
    assert len(acme) == 2 and len(globex) == 1
    records = [json.loads(s3.objects[k]) for k in acme]
    assert [r["sequence"] for r in records] == [0, 1]
    assert records[1]["object"]["eventIds"] == ["e2", "e3"]
    assert integrity.verify_chain(records, public) == []


def test_digest_without_a_key_fails_before_anything_is_written(s3):
    with pytest.raises(ValueError):
        aws_s3_sink.process(event(1), {"bucket": "b", "digest": "true"})
    with pytest.raises(ValueError):
        aws_s3_sink.process_batch([event(1)], {"bucket": "b", "digest": "true"})
    assert s3.objects == {}


# --- verifier ---

def test_the_verifier_passes_an_intact_archive_and_catches_changes(s3, keys, capsys):
    seed, public = keys
    for i in range(3):
        aws_s3_sink.process(event(i), props(seed))
    assert verify_s3_digests.verify(s3, "b", "tenants/tenant=acme", public, check_unattested=True) == 0

    object_key = next(k for k in s3.objects if "/_digests/" not in k)
    s3.objects[object_key] = b'{"tampered": true}'
    assert verify_s3_digests.verify(s3, "b", "tenants/tenant=acme", public, check_unattested=False) == 1
    assert "content changed" in capsys.readouterr().out


def test_the_verifier_reports_deleted_digests_and_unattested_objects(s3, keys, capsys):
    seed, public = keys
    for i in range(3):
        aws_s3_sink.process(event(i), props(seed))
    middle = sorted(k for k in s3.objects if "/_digests/" in k)[1]
    del s3.objects[middle]
    s3.objects["tenants/tenant=acme/year=2026/injected.json"] = b"{}"

    assert verify_s3_digests.verify(s3, "b", "tenants/tenant=acme", public, check_unattested=True) == 1
    out = capsys.readouterr().out
    assert "expected sequence 1" in out
    assert "injected.json: no digest record covers it" in out
