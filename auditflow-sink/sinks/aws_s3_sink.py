"""
AWS S3 Sink - Store events in Amazon S3.

This sink uploads audit events to AWS S3 as JSON objects.
Supports batching, compression, and partitioning by date.
"""
import logging
import json
import gzip
import re
import threading
from datetime import datetime, timezone
import uuid

from auditflow_sdk import batch_object_name, event_datetime, jsonl_body

__version__ = "1.0.0"

PROPERTIES = {
    "bucket": "S3 bucket name (required)",
    "prefix": "Object key prefix/folder (default: auditflow/)",
    "region": "AWS region (default: us-east-1)",
    "access-key-id": "AWS access key ID (optional, uses default credential chain if omitted)",
    "secret-access-key": "AWS secret access key (optional)",
    "compress": "Enable gzip compression: true/false (default: false)",
    "partition-by-date": "Partition objects by event date: true/false (default: true)",
    "partition-format": "strftime pattern for partitioning (default: year=%Y/month=%m/day=%d/). "
                        "{field} placeholders insert an event field, e.g. "
                        "vendor_id={extra.vendor_id}/year=%Y/month=%m/day=%d/; values are sanitized to "
                        "[A-Za-z0-9._-] and a missing value becomes 'unknown'",
    "file-format": "File format: json or jsonl (default: json)",
    "endpoint-url": "Custom S3-compatible endpoint URL (optional)",
    "checksum": "Integrity checksum S3 verifies on upload and stores with the object: sha256 (default) or "
                "none (for S3-compatible stores without checksum support)",
    "digest": "Tamper-evidence: true writes a signed, hash-chained digest record per object under "
              "<prefix>tenant=<id>/_digests/ (default: false). Verify with scripts/verify_s3_digests.py",
    "digest-signing-key": "Ed25519 private key for digest records (base64 32-byte seed or PEM); required "
                          "with digest=true. Use ${secretRef:...}, never a literal",
}

logger = logging.getLogger(__name__)

# Digest chains live for the life of the sink process (see integrity.py).
_DIGESTS = None

try:
    import boto3
    from botocore.exceptions import ClientError
except ImportError:
    logger.error("boto3 is not installed. Install with: pip install boto3")
    boto3 = None


def process(event_data: dict, properties: dict) -> dict:
    """
    Process an audit event by uploading it to AWS S3.

    Args:
        event_data: The transformed audit event data
        properties: Configuration properties
            - bucket: S3 bucket name (required)
            - prefix: Object key prefix/folder (default: auditflow/)
            - region: AWS region (default: us-east-1)
            - access-key-id: AWS access key (optional, uses default credentials if not provided)
            - secret-access-key: AWS secret key (optional)
            - compress: Enable gzip compression (default: false)
            - partition-by-date: Partition by date (default: true)
            - partition-format: strftime pattern, may contain {field} placeholders (default: year=%Y/month=%m/day=%d/)
            - file-format: File format - json or jsonl (default: json)
            - endpoint-url: Custom S3 endpoint URL (optional, for S3-compatible storage)

    Returns:
        dict: Processing result with S3 details
    """
    if boto3 is None:
        raise RuntimeError("boto3 library is required. Install with: pip install boto3")

    # Validate required properties
    bucket = properties.get('bucket')
    if not bucket:
        raise ValueError("Missing required property: 'bucket'")

    # Get configuration
    prefix = properties.get('prefix', 'auditflow/')
    region = properties.get('region', 'us-east-1')
    access_key_id = properties.get('access-key-id')
    secret_access_key = properties.get('secret-access-key')
    compress = properties.get('compress', 'false').lower() == 'true'
    partition_by_date = properties.get('partition-by-date', 'true').lower() == 'true'
    partition_format = properties.get('partition-format', 'year=%Y/month=%m/day=%d/')
    file_format = properties.get('file-format', 'json').lower()
    endpoint_url = properties.get('endpoint-url')

    signing_key = _digest_signing_key(properties)  # fail before writing anything
    _checksum_args(properties)
    s3_client = _get_s3_client(region, access_key_id, secret_access_key, endpoint_url)

    # Build object key
    object_key = build_object_key(
        prefix,
        partition_by_date,
        partition_format,
        file_format,
        compress,
        event_data
    )

    # Prepare content
    if file_format == 'jsonl':
        content = json.dumps(event_data) + '\n'
    else:
        content = json.dumps(event_data, indent=2)

    # Compress if needed
    if compress:
        content_bytes = gzip.compress(content.encode('utf-8'))
        content_type = 'application/gzip'
    else:
        content_bytes = content.encode('utf-8')
        content_type = 'application/json'

    try:
        # Upload to S3
        logger.info("Uploading event to S3: s3://%s/%s", bucket, object_key)

        response = s3_client.put_object(
            Bucket=bucket,
            Key=object_key,
            Body=content_bytes,
            ContentType=content_type,
            **_checksum_args(properties),
            Metadata={
                'timestamp': event_data.get('timestamp', datetime.now(timezone.utc).isoformat()),
                'event-id': event_data.get('eventId', 'unknown'),
                'event-type': event_data.get('eventType', 'unknown'),
                'source-system': event_data.get('sourceSystem', 'unknown'),
                'tenant-id': event_data.get('tenantId', 'unknown'),
            }
        )

        logger.info("Event uploaded to S3 successfully. ETag: %s", response.get('ETag'))
        digest_key = _write_digest(s3_client, bucket, prefix, object_key, content_bytes, [event_data], properties,
                                   signing_key)

        # Strip quotes from ETag (AWS returns ETags wrapped in quotes)
        etag = response.get('ETag', '').strip('"')

        return {
            "sent": True,
            "destination": "s3",
            "bucket": bucket,
            "key": object_key,
            "region": region,
            "compressed": compress,
            "size_bytes": len(content_bytes),
            "etag": etag,
            "version_id": response.get('VersionId'),
            "digest_key": digest_key,
        }

    except ClientError as e:
        error_code = e.response['Error']['Code']
        error_message = e.response['Error']['Message']
        logger.error("Failed to upload to S3: %s - %s", error_code, error_message)
        raise RuntimeError(f"Failed to upload to S3 bucket '{bucket}': {error_code} - {error_message}")
    except Exception as e:
        logger.error("Unexpected error uploading to S3: %s", e)
        raise RuntimeError(f"Unexpected error: {e}")


def process_batch(events: list, properties: dict) -> list:
    """Write a batch of events as one JSON Lines object per key prefix.

    Events whose keys share a prefix (same tenant and partition folders) go into ONE object, so a
    batch costs one PUT per partition instead of one per event. The object name is the first event's
    timestamp plus a hash of the group's event ids: re-delivering exactly the same group overwrites
    the same object instead of adding a copy. A different grouping on redelivery can repeat an event
    in a second object, so readers should de-duplicate by ``eventId`` (at-least-once).

    Returns one outcome per event, in order: a result dict, or the Exception of its group's PUT.
    """
    if boto3 is None:
        raise RuntimeError("boto3 library is required. Install with: pip install boto3")
    bucket = properties.get('bucket')
    if not bucket:
        raise ValueError("Missing required property: 'bucket'")
    prefix = properties.get('prefix', 'auditflow/')
    region = properties.get('region', 'us-east-1')
    compress = properties.get('compress', 'false').lower() == 'true'
    partition_by_date = properties.get('partition-by-date', 'true').lower() == 'true'
    partition_format = properties.get('partition-format', 'year=%Y/month=%m/day=%d/')
    signing_key = _digest_signing_key(properties)  # fail before writing anything
    _checksum_args(properties)
    s3_client = _get_s3_client(region, properties.get('access-key-id'), properties.get('secret-access-key'),
                               properties.get('endpoint-url'))

    groups = {}
    for index, event in enumerate(events):
        key = build_object_key(prefix, partition_by_date, partition_format, 'jsonl', compress, event)
        groups.setdefault(key.rsplit('/', 1)[0], []).append(index)

    outcomes = [None] * len(events)
    for key_prefix, indexes in groups.items():
        group = [events[i] for i in indexes]
        object_key = batch_object_key(key_prefix, group, compress)
        body = jsonl_body(group, compress)
        try:
            s3_client.put_object(
                Bucket=bucket,
                Key=object_key,
                Body=body,
                ContentType='application/gzip' if compress else 'application/x-ndjson',
                **_checksum_args(properties),
                Metadata={'event-count': str(len(group)),
                          'tenant-id': str(group[0].get('tenantId', 'unknown'))},
            )
            logger.info("Uploaded batch of %d event(s) to s3://%s/%s", len(group), bucket, object_key)
            digest_key = _write_digest(s3_client, bucket, prefix, object_key, body, group, properties, signing_key)
            for i in indexes:
                outcomes[i] = {"sent": True, "destination": "s3", "bucket": bucket, "key": object_key,
                               "digest_key": digest_key}
        except Exception as e:  # noqa: BLE001 - the group's PUT failed: every event in it failed
            logger.error("Failed to upload batch object s3://%s/%s: %s", bucket, object_key, e)
            for i in indexes:
                outcomes[i] = RuntimeError(f"Failed to upload to S3 bucket '{bucket}': {e}")
    return outcomes


def batch_object_key(key_prefix: str, group: list, compress: bool) -> str:
    """Deterministic name of a batch object: earliest event time plus a hash of the sorted event ids."""
    return f"{key_prefix}/{batch_object_name(group, compress)}"


def _event_datetime(event_data: dict) -> datetime:
    """The event's server receipt time (``timestamp``); now when absent or unparseable."""
    return event_datetime(event_data)


def build_object_key(
    prefix: str,
    partition_by_date: bool,
    partition_format: str,
    file_format: str,
    compress: bool,
    event_data: dict
) -> str:
    """Build S3 object key with optional tenantId and date partitioning."""
    key_parts = [prefix.rstrip('/')]

    # Add tenantId partition if exists (as first partition element)
    tenant_id = event_data.get('tenantId')
    if tenant_id:
        key_parts.append(f"tenant={tenant_id}")

    dt = _event_datetime(event_data)

    # Add date partition using the event timestamp
    if partition_by_date:
        date_part = _fill_placeholders(dt.strftime(partition_format), event_data)
        key_parts.append(date_part.rstrip('/'))

    # One object per event: the full eventId makes the name unique, and a redelivery of the same
    # message rewrites the same object instead of adding a copy. A shortened id is not unique: with
    # time-ordered ids (UUIDv7) every event of the same second shares its first characters.
    event_id = event_data.get('eventId', str(uuid.uuid4()))
    timestamp = dt.strftime('%Y%m%d-%H%M%S')

    extension = 'json'
    if compress:
        extension += '.gz'

    filename = f"{timestamp}-{event_id}.{extension}"
    key_parts.append(filename)

    return '/'.join(key_parts)


_PLACEHOLDER = re.compile(r'\{([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\}')
_SAFE_SEGMENT = re.compile(r'[^A-Za-z0-9._-]')
_MAX_SEGMENT_LENGTH = 128


def _fill_placeholders(path: str, event_data: dict) -> str:
    """Replace {field} (a dotted path into the event) with its key-safe value. Runs after strftime,
    so a value can never be read as a date directive."""
    return _PLACEHOLDER.sub(lambda m: _partition_value(_lookup(event_data, m.group(1))), path)


def _lookup(event_data: dict, path: str):
    """Value at a dotted path; an 'extra.' path falls back to the top level, where a promoting
    transformer moves well-known keys such as actionName."""
    node = event_data
    for part in path.split('.'):
        if not isinstance(node, dict) or part not in node:
            node = None
            break
        node = node[part]
    if node is None and path.startswith('extra.'):
        node = event_data.get(path.rsplit('.', 1)[-1])
    return node


def _partition_value(value) -> str:
    """A key-safe path segment. Values come from the publisher, so '/', '..' and the like must
    never reach the key: every character outside [A-Za-z0-9._-] (so '/' in 'product/create')
    becomes '_'."""
    if value is None or isinstance(value, (dict, list)):
        return 'unknown'
    segment = _SAFE_SEGMENT.sub('_', str(value))[:_MAX_SEGMENT_LENGTH]
    if not segment or set(segment) == {'.'}:
        return 'unknown'
    return segment


_CLIENTS = {}
_CLIENTS_LOCK = threading.Lock()
_MAX_CLIENTS = 64


def _get_s3_client(region, access_key_id, secret_access_key, endpoint_url):
    """One S3 client per (region, credentials, endpoint), reused across events.

    Building a client per event re-resolves credentials every time (on EKS an STS
    AssumeRoleWithWebIdentity call) and costs far more than the PUT itself. boto3 clients are
    thread-safe and refresh their own temporary credentials.
    """
    key = (region, access_key_id, secret_access_key, endpoint_url)
    client = _CLIENTS.get(key)
    if client is not None:
        return client
    with _CLIENTS_LOCK:
        client = _CLIENTS.get(key)
        if client is None:
            kwargs = {'region_name': region}
            if access_key_id and secret_access_key:
                kwargs['aws_access_key_id'] = access_key_id
                kwargs['aws_secret_access_key'] = secret_access_key
            if endpoint_url:
                kwargs['endpoint_url'] = endpoint_url
            if len(_CLIENTS) >= _MAX_CLIENTS:
                _CLIENTS.clear()
            client = _CLIENTS[key] = boto3.client('s3', **kwargs)
    return client


def _checksum_args(properties: dict) -> dict:
    """S3 computes and verifies a SHA-256 of the upload and stores it with the object."""
    mode = str(properties.get('checksum', 'sha256')).lower()
    if mode == 'none':
        return {}
    if mode != 'sha256':
        raise ValueError(f"Unsupported checksum '{mode}' (use sha256 or none)")
    return {'ChecksumAlgorithm': 'SHA256'}


def _digest_signing_key(properties: dict):
    """The Ed25519 signing key when digest=true, else None. A ValueError is a configuration error."""
    if str(properties.get('digest', 'false')).lower() != 'true':
        return None
    import integrity  # local import: only deployments that sign digests need the cryptography library
    signing_value = properties.get('digest-signing-key')
    if not signing_value:
        raise ValueError("digest=true requires 'digest-signing-key' (use ${secretRef:...})")
    return integrity.load_signing_key(signing_value)


def _write_digest(s3_client, bucket, prefix, object_key, body, events, properties, signing_key):
    """Signed digest record for a stored object (digest=true); returns its key, or None."""
    if signing_key is None:
        return None
    import integrity
    global _DIGESTS
    if _DIGESTS is None:
        _DIGESTS = integrity.DigestChains()
    tenant_id = events[0].get('tenantId') if events else None
    digest_prefix = prefix.rstrip('/') + (f"/tenant={tenant_id}" if tenant_id else '') + '/_digests'

    def put(key, data):
        s3_client.put_object(Bucket=bucket, Key=key, Body=data, ContentType='application/json',
                             **_checksum_args(properties))

    return _DIGESTS.record(bucket=bucket, digest_prefix=digest_prefix, object_key=object_key, body=body,
                           event_ids=[str(e.get('eventId', '')) for e in events], signing_key=signing_key, put=put)
