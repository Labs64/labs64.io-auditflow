"""Tamper-evidence for archive sinks: signed, hash-chained digest records.

For every object a sink writes, a digest record names the object, its SHA-256, size and event ids,
and links to the previous record of the same chain by hash. Records are signed with Ed25519. An
auditor holding the public key can then prove, without trusting AuditFlow:

- every listed object still has exactly the content that was written (its SHA-256);
- no digest record was removed, reordered or altered inside a chain (sequence + previous hash);
- every record was produced by the holder of the signing key.

Chains are per sink process and per tenant (``chainId`` is random per process start), so verifying
one tenant never needs another tenant's data, and the sink needs no read access to the bucket: the
chain state lives in memory. A restart starts a new chain; the verifier checks each chain on its
own. Not covered (phase 1): deleting the newest records at the end of a chain and objects whose
digest was never written (the verifier reports those as unattested). S3 Object Lock closes both.
"""
import base64
import hashlib
import json
import threading
import uuid
from datetime import datetime, timezone

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

DIGEST_VERSION = 1
SIGNATURE_ALGORITHM = "Ed25519"


def load_signing_key(value: str) -> Ed25519PrivateKey:
    """An Ed25519 private key from a PEM block or a base64 32-byte seed."""
    text = (value or "").strip()
    if not text:
        raise ValueError("digest signing key is empty")
    if text.startswith("-----BEGIN"):
        key = serialization.load_pem_private_key(text.encode(), password=None)
        if not isinstance(key, Ed25519PrivateKey):
            raise ValueError("digest signing key is not an Ed25519 key")
        return key
    try:
        seed = base64.b64decode(text, validate=True)
    except ValueError as e:
        raise ValueError("digest signing key is neither PEM nor base64") from e
    if len(seed) != 32:
        raise ValueError("a base64 digest signing key must decode to 32 bytes")
    return Ed25519PrivateKey.from_private_bytes(seed)


def load_public_key(value: str) -> Ed25519PublicKey:
    """An Ed25519 public key from a PEM block or base64 32 raw bytes."""
    text = (value or "").strip()
    if text.startswith("-----BEGIN"):
        key = serialization.load_pem_public_key(text.encode())
        if not isinstance(key, Ed25519PublicKey):
            raise ValueError("public key is not an Ed25519 key")
        return key
    raw = base64.b64decode(text, validate=True)
    if len(raw) != 32:
        raise ValueError("a base64 public key must decode to 32 bytes")
    return Ed25519PublicKey.from_public_bytes(raw)


def public_key_b64(private_key: Ed25519PrivateKey) -> str:
    raw = private_key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return base64.b64encode(raw).decode()


def generate_key_pair() -> tuple:
    """(private seed, public key), both base64."""
    key = Ed25519PrivateKey.generate()
    seed = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                             serialization.NoEncryption())
    return base64.b64encode(seed).decode(), public_key_b64(key)


def canonical(record: dict) -> bytes:
    """The bytes that are signed: the record without its signature, keys sorted, no whitespace."""
    unsigned = {k: v for k, v in record.items() if k != "signature"}
    return json.dumps(unsigned, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def record_sha256(record: dict) -> str:
    """Hash of a complete (signed) record, which the next record of the chain links to."""
    return hashlib.sha256(json.dumps(record, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode("utf-8")).hexdigest()


def verify_signature(record: dict, public_key: Ed25519PublicKey) -> bool:
    try:
        public_key.verify(base64.b64decode(record["signature"]), canonical(record))
        return True
    except Exception:  # noqa: BLE001 - any failure is "not verified"
        return False


class DigestChains:
    """Per (bucket, chain prefix) hash chains of one sink process. Thread-safe."""

    def __init__(self):
        self._lock = threading.Lock()
        self._chains = {}

    def reset(self):
        with self._lock:
            self._chains.clear()

    def record(self, *, bucket: str, digest_prefix: str, object_key: str, body: bytes, event_ids: list,
               signing_key: Ed25519PrivateKey, put) -> str:
        """Write the digest record for one stored object; returns the record's key.

        ``put(key, body_bytes)`` stores the record. The chain only advances after a successful put,
        so a failed write leaves no gap (the caller retries the delivery, which re-writes the object
        and its digest).
        """
        chain_key = (bucket, digest_prefix)
        with self._lock:
            state = self._chains.get(chain_key)
            if state is None:
                state = {"chainId": str(uuid.uuid4()), "sequence": 0, "previous": None}
            record = {
                "version": DIGEST_VERSION,
                "chainId": state["chainId"],
                "sequence": state["sequence"],
                "previousDigestSha256": state["previous"],
                "createdAt": datetime.now(timezone.utc).isoformat(),
                "bucket": bucket,
                "object": {
                    "key": object_key,
                    "sha256": hashlib.sha256(body).hexdigest(),
                    "size": len(body),
                    "eventCount": len(event_ids),
                    "eventIds": event_ids,
                },
                "signatureAlgorithm": SIGNATURE_ALGORITHM,
                "publicKey": public_key_b64(signing_key),
            }
            record["signature"] = base64.b64encode(signing_key.sign(canonical(record))).decode()
            key = f"{digest_prefix.rstrip('/')}/chain={state['chainId']}/{state['sequence']:012d}.json"
            put(key, json.dumps(record, sort_keys=True, ensure_ascii=False).encode("utf-8"))
            self._chains[chain_key] = {"chainId": state["chainId"], "sequence": state["sequence"] + 1,
                                       "previous": record_sha256(record)}
            return key


def verify_chain(records: list, public_key: Ed25519PublicKey) -> list:
    """Problems in one chain's records (sorted by sequence); empty when the chain is intact."""
    problems = []
    previous = None
    for expected_sequence, record in enumerate(records):
        where = f"chain {record.get('chainId')} #{record.get('sequence')}"
        if record.get("sequence") != expected_sequence:
            problems.append(f"{where}: expected sequence {expected_sequence} (records missing or reordered)")
        if record.get("previousDigestSha256") != previous:
            problems.append(f"{where}: previous-digest hash does not match the record before it")
        if not verify_signature(record, public_key):
            problems.append(f"{where}: signature does not verify with the given public key")
        previous = record_sha256(record)
    return problems
