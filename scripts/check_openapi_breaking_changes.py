#!/usr/bin/env python3
"""Fail the build when a change to the frozen v1 API contract is breaking.

Item 8 (roadmap): `openapi-audit-v1.yaml` is frozen with a written compatibility
promise — additive-only within v1, breaking changes require v2 (a new spec
file, a new major version). This is the mechanical enforcement of that promise:
it diffs the spec against its version at the merge-base with `master` and
fails if the diff removes or narrows anything a v1 client could already be
relying on.

Deliberately hand-rolled rather than a dependency on an external OpenAPI-diff
tool: the ruleset only needs to know about the shapes this spec actually uses
(paths, operations, parameters, request/response schemas, enums), and keeping
it in-repo means no version-pinning or supply-chain surface for a one-file
check.

What counts as breaking:
  - A path is removed.
  - An operation (method) under an existing path is removed.
  - A response status code is removed from an existing operation.
  - A schema is removed from components.schemas.
  - A property is removed from an existing schema.
  - A property becomes required that was not required before.
  - A parameter (path/query/header) is removed from an operation.
  - A parameter becomes required that was not required before.
  - An enum value is removed from an existing enum.
  - A property's, or parameter's, declared `type` changes.

What is explicitly NOT breaking (additive, allowed):
  - A new path, operation, response code, schema, property, parameter, or enum
    value.
  - A property or parameter becoming optional (loosening a requirement).
  - Documentation-only changes (`description`, `example`, `summary`, ...).

Usage:
    scripts/check_openapi_breaking_changes.py <spec-path> [--base-ref master]
    scripts/check_openapi_breaking_changes.py <spec-path> --old-file <path> --new-file <path>
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass, field

import yaml


@dataclass
class Break:
    rule: str
    where: str
    detail: str

    def __str__(self) -> str:
        return f"[{self.rule}] {self.where}: {self.detail}"


@dataclass
class Diff:
    breaks: list[Break] = field(default_factory=list)

    def add(self, rule: str, where: str, detail: str) -> None:
        self.breaks.append(Break(rule, where, detail))


HTTP_METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")


def load_spec(text: str) -> dict:
    return yaml.safe_load(text) or {}


def resolve_ref(node, root: dict):
    """Follow a single local `$ref` (the only kind this spec uses)."""
    if isinstance(node, dict) and "$ref" in node and len(node) == 1:
        ref = node["$ref"]
        if not ref.startswith("#/"):
            return node
        target = root
        for part in ref[2:].split("/"):
            target = target.get(part, {}) if isinstance(target, dict) else {}
        return target
    return node


# --- schema comparison ---------------------------------------------------------


def compare_schema(old: dict, new: dict, old_root: dict, new_root: dict, where: str, diff: Diff) -> None:
    old = resolve_ref(old, old_root)
    new = resolve_ref(new, new_root)
    if not isinstance(old, dict) or not isinstance(new, dict):
        return

    old_type = old.get("type")
    new_type = new.get("type")
    if old_type is not None and new_type is not None and old_type != new_type:
        diff.add("type-changed", where, f"type changed from '{old_type}' to '{new_type}'")

    old_enum = old.get("enum")
    new_enum = new.get("enum")
    if isinstance(old_enum, list) and isinstance(new_enum, list):
        removed = set(old_enum) - set(new_enum)
        for value in sorted(removed, key=str):
            diff.add("enum-value-removed", where, f"enum value '{value}' removed")

    old_props = old.get("properties")
    new_props = new.get("properties")
    if isinstance(old_props, dict):
        new_props = new_props if isinstance(new_props, dict) else {}
        old_required = set(old.get("required") or [])
        new_required = set(new.get("required") or [])
        for name, old_prop in old_props.items():
            prop_where = f"{where}.properties.{name}"
            if name not in new_props:
                diff.add("property-removed", prop_where, "property removed")
                continue
            if name not in old_required and name in new_required:
                diff.add("property-now-required", prop_where,
                          "property was optional and is now required")
            compare_schema(old_prop, new_props[name], old_root, new_root, prop_where, diff)

    old_items = old.get("items")
    new_items = new.get("items")
    if isinstance(old_items, dict) and isinstance(new_items, dict):
        compare_schema(old_items, new_items, old_root, new_root, f"{where}[]", diff)


def compare_components_schemas(old_root: dict, new_root: dict, diff: Diff) -> None:
    old_schemas = ((old_root.get("components") or {}).get("schemas")) or {}
    new_schemas = ((new_root.get("components") or {}).get("schemas")) or {}
    for name, old_schema in old_schemas.items():
        where = f"components.schemas.{name}"
        if name not in new_schemas:
            diff.add("schema-removed", where, "schema removed from components")
            continue
        compare_schema(old_schema, new_schemas[name], old_root, new_root, where, diff)


# --- operation comparison -------------------------------------------------------


def index_parameters(params) -> dict:
    if not isinstance(params, list):
        return {}
    indexed = {}
    for param in params:
        if isinstance(param, dict) and "name" in param and "in" in param:
            indexed[(param["in"], param["name"])] = param
    return indexed


def compare_parameters(old_params, new_params, old_root: dict, new_root: dict, where: str, diff: Diff) -> None:
    old_index = index_parameters(old_params)
    new_index = index_parameters(new_params)
    for key, old_param in old_index.items():
        location, name = key
        param_where = f"{where} parameter {location}:{name}"
        if key not in new_index:
            diff.add("parameter-removed", param_where, "parameter removed")
            continue
        new_param = new_index[key]
        if not old_param.get("required") and new_param.get("required"):
            diff.add("parameter-now-required", param_where,
                      "parameter was optional and is now required")
        if "schema" in old_param and "schema" in new_param:
            compare_schema(old_param["schema"], new_param["schema"], old_root, new_root,
                           f"{param_where}.schema", diff)


def compare_operation(old_op: dict, new_op: dict, old_root: dict, new_root: dict, where: str, diff: Diff) -> None:
    compare_parameters(old_op.get("parameters"), new_op.get("parameters"), old_root, new_root, where, diff)

    old_responses = old_op.get("responses") or {}
    new_responses = new_op.get("responses") or {}
    for status, old_response in old_responses.items():
        response_where = f"{where} response {status}"
        if status not in new_responses:
            diff.add("response-removed", response_where, "response status code removed")
            continue
        old_content = (old_response or {}).get("content") or {}
        new_content = (new_responses[status] or {}).get("content") or {}
        for media_type, old_media in old_content.items():
            if media_type not in new_content:
                continue  # removing a representation of a still-documented response: out of scope
            old_schema = old_media.get("schema")
            new_schema = new_content[media_type].get("schema")
            if old_schema and new_schema:
                compare_schema(old_schema, new_schema, old_root, new_root,
                               f"{response_where}.content.{media_type}", diff)

    old_body = ((old_op.get("requestBody") or {}).get("content") or {})
    new_body = ((new_op.get("requestBody") or {}).get("content") or {})
    for media_type, old_media in old_body.items():
        if media_type not in new_body:
            diff.add("request-body-media-type-removed", f"{where} requestBody",
                      f"'{media_type}' request content type removed")
            continue
        old_schema = old_media.get("schema")
        new_schema = new_body[media_type].get("schema")
        if old_schema and new_schema:
            compare_schema(old_schema, new_schema, old_root, new_root,
                           f"{where} requestBody.content.{media_type}", diff)


def compare_paths(old_root: dict, new_root: dict, diff: Diff) -> None:
    old_paths = old_root.get("paths") or {}
    new_paths = new_root.get("paths") or {}
    for path, old_item in old_paths.items():
        if path not in new_paths:
            diff.add("path-removed", path, "path removed")
            continue
        new_item = new_paths[path]
        for method in HTTP_METHODS:
            if method not in old_item:
                continue
            where = f"{method.upper()} {path}"
            if method not in new_item:
                diff.add("operation-removed", where, "operation removed")
                continue
            compare_operation(old_item[method], new_item[method], old_root, new_root, where, diff)


def find_breaking_changes(old_root: dict, new_root: dict) -> Diff:
    diff = Diff()
    compare_paths(old_root, new_root, diff)
    compare_components_schemas(old_root, new_root, diff)
    return diff


# --- git integration -------------------------------------------------------------


def read_at_ref(ref: str, path: str) -> str | None:
    proc = subprocess.run(["git", "show", f"{ref}:{path}"], capture_output=True, text=True)
    if proc.returncode != 0:
        return None  # file did not exist at that ref (e.g. spec just created) — nothing to diff
    return proc.stdout


def merge_base(base_ref: str) -> str:
    proc = subprocess.run(["git", "merge-base", "HEAD", base_ref], capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"git merge-base HEAD {base_ref} failed: {proc.stderr.strip()}")
    return proc.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("spec_path", help="path to the spec, relative to the repo root")
    parser.add_argument("--base-ref", default="origin/master",
                         help="git ref to diff against (merge-base with HEAD is used)")
    parser.add_argument("--old-file", help="explicit path to the OLD spec (bypasses git)")
    parser.add_argument("--new-file", help="explicit path to the NEW spec (bypasses git)")
    args = parser.parse_args()

    if args.old_file or args.new_file:
        if not (args.old_file and args.new_file):
            print("check_openapi_breaking_changes: --old-file and --new-file must be used together",
                  file=sys.stderr)
            return 2
        old_text = open(args.old_file).read()
        new_text = open(args.new_file).read()
        old_label, new_label = args.old_file, args.new_file
    else:
        try:
            base = merge_base(args.base_ref)
        except RuntimeError as exc:
            print(f"check_openapi_breaking_changes: {exc}", file=sys.stderr)
            return 2
        old_text = read_at_ref(base, args.spec_path)
        if old_text is None:
            print(f"check_openapi_breaking_changes: {args.spec_path} did not exist at "
                  f"{base} — nothing to compare, treating as new.")
            return 0
        new_text = open(args.spec_path).read()
        old_label, new_label = f"{base}:{args.spec_path}", args.spec_path

    try:
        old_root = load_spec(old_text)
        new_root = load_spec(new_text)
    except yaml.YAMLError as exc:
        print(f"check_openapi_breaking_changes: failed to parse spec YAML: {exc}", file=sys.stderr)
        return 2

    diff = find_breaking_changes(old_root, new_root)

    if diff.breaks:
        print(f"Breaking change(s) found comparing {old_label} -> {new_label}:\n")
        for b in diff.breaks:
            print(f"  {b}")
        print(
            "\nThe v1 contract is frozen: additive changes only. A genuine breaking change "
            "needs a v2 spec (new file, new major version), not an edit to openapi-audit-v1.yaml. "
            "See the compatibility promise in README.md."
        )
        return 1

    print(f"check_openapi_breaking_changes: no breaking changes ({old_label} -> {new_label}).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
