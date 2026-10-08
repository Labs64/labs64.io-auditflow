#!/usr/bin/env python3
"""Verify the tamper-evidence digest records an AuditFlow S3 archive pipeline writes (digest=true).

  # one-time: create the signing key (store the private seed as the tenant's secret, publish the public key)
  verify_s3_digests.py --generate-key

  # verify one tenant's archive with the public key (needs s3:ListBucket + s3:GetObject on the prefix)
  verify_s3_digests.py --bucket my-audit-archive \\
      --prefix tenants/tenant=netlicensing --public-key <base64 or @file.pem> [--profile x] [--region y]

Checks, per chain under <prefix>/_digests/chain=<id>/: every record's signature, the sequence and the
link to the previous record, and that each attested object still exists with the recorded SHA-256
(S3's stored checksum when present, otherwise the object is downloaded and hashed). With --unattested
it also lists objects under the prefix that no digest record covers. Exit status 1 on any finding.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import integrity  # noqa: E402


def _key_value(value: str) -> str:
    return pathlib.Path(value[1:]).read_text() if value.startswith("@") else value


def _object_sha256(s3, bucket: str, key: str):
    """(hex sha256, how) of an object, or (None, reason) when it is missing."""
    try:
        head = s3.head_object(Bucket=bucket, Key=key, ChecksumMode="ENABLED")
    except s3.exceptions.ClientError as e:
        return None, f"missing ({e.response['Error']['Code']})"
    checksum = head.get("ChecksumSHA256")
    if checksum and "-" not in checksum:  # a multipart checksum is a checksum of checksums: not comparable
        return base64.b64decode(checksum).hex(), "s3 checksum"
    body = s3.get_object(Bucket=bucket, Key=key)["Body"].read()
    return hashlib.sha256(body).hexdigest(), "downloaded"


def verify(s3, bucket: str, prefix: str, public_key, check_unattested: bool) -> int:
    prefix = prefix.rstrip("/")
    digest_prefix = f"{prefix}/_digests/"
    chains = {}
    attested = set()
    objects = []
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=f"{prefix}/"):
        for item in page.get("Contents", []):
            key = item["Key"]
            if key.startswith(digest_prefix):
                chain = key[len(digest_prefix):].split("/", 1)[0].removeprefix("chain=")
                chains.setdefault(chain, []).append(key)
            else:
                objects.append(key)

    problems = []
    records_total = 0
    for chain, keys in sorted(chains.items()):
        records = [json.loads(s3.get_object(Bucket=bucket, Key=k)["Body"].read()) for k in sorted(keys)]
        records_total += len(records)
        problems += integrity.verify_chain(records, public_key)
        for record in records:
            obj = record["object"]
            attested.add(obj["key"])
            actual, how = _object_sha256(s3, bucket, obj["key"])
            if actual is None:
                problems.append(f"{obj['key']}: {how}")
            elif actual != obj["sha256"]:
                problems.append(f"{obj['key']}: content changed (sha256 {actual} via {how}, attested {obj['sha256']})")
        print(f"chain {chain}: {len(records)} record(s)")

    unattested = sorted(set(objects) - attested)
    print(f"{len(chains)} chain(s), {records_total} record(s), {len(attested)} attested object(s), "
          f"{len(unattested)} object(s) without a digest")
    if check_unattested:
        problems += [f"{key}: no digest record covers it" for key in unattested]
    for p in problems:
        print(f"FAIL {p}")
    if not problems:
        print("OK: every chain is intact and every attested object is unchanged")
    return 1 if problems else 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--generate-key", action="store_true", help="print a new Ed25519 key pair and exit")
    parser.add_argument("--bucket")
    parser.add_argument("--prefix", help="tenant prefix, e.g. tenants/tenant=netlicensing")
    parser.add_argument("--public-key", help="base64 raw key, PEM, or @file")
    parser.add_argument("--unattested", action="store_true", help="also fail on objects without a digest record")
    parser.add_argument("--region")
    parser.add_argument("--profile")
    parser.add_argument("--endpoint-url")
    args = parser.parse_args(argv)

    if args.generate_key:
        seed, public = integrity.generate_key_pair()
        print(f"private seed (store as the tenant secret, e.g. ${{secretRef:digestSigningKey}}): {seed}")
        print(f"public key (give to auditors, keep with the archive): {public}")
        return 0
    if not (args.bucket and args.prefix and args.public_key):
        parser.error("--bucket, --prefix and --public-key are required to verify")
    import boto3
    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    s3 = session.client("s3", endpoint_url=args.endpoint_url)
    return verify(s3, args.bucket, args.prefix, integrity.load_public_key(_key_value(args.public_key)),
                  args.unattested)


if __name__ == "__main__":
    sys.exit(main())
