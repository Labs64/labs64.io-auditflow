"""Tests for the frozen-v1-contract breaking-change checker (item 8).

A gate nobody tests reports "no breaking changes" forever. These tests plant
each rule's violation and assert it is caught, and separately assert every
additive change the promise explicitly allows passes clean.

Run: pytest scripts/test_check_openapi_breaking_changes.py
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest
import yaml

SCRIPT = Path(__file__).parent / "check_openapi_breaking_changes.py"
_spec = importlib.util.spec_from_file_location("check_openapi_breaking_changes", SCRIPT)
mod = importlib.util.module_from_spec(_spec)
sys.modules["check_openapi_breaking_changes"] = mod
_spec.loader.exec_module(mod)


BASE_SPEC = """
openapi: 3.0.3
info:
  title: Test
  version: 1.0.0
paths:
  /widgets:
    get:
      operationId: listWidgets
      parameters:
        - name: filter
          in: query
          required: false
          schema:
            type: string
      responses:
        '200':
          description: ok
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Widget'
        '400':
          description: bad request
    post:
      operationId: createWidget
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/Widget'
      responses:
        '201':
          description: created
components:
  schemas:
    Widget:
      type: object
      required:
        - name
      properties:
        name:
          type: string
        color:
          type: string
          enum: [red, green, blue]
"""


def rules(old_yaml: str, new_yaml: str) -> set[str]:
    diff = mod.find_breaking_changes(yaml.safe_load(old_yaml), yaml.safe_load(new_yaml))
    return {b.rule for b in diff.breaks}


def unchanged() -> str:
    return BASE_SPEC


# --- must be flagged: breaking changes -----------------------------------------


def test_flags_removed_path():
    new = BASE_SPEC.replace("  /widgets:\n", "  /widgets-renamed:\n")
    assert "path-removed" in rules(BASE_SPEC, new)


def test_flags_removed_operation():
    lines = BASE_SPEC.splitlines()
    # Drop the `post:` operation and everything under it.
    start = next(i for i, l in enumerate(lines) if l.strip() == "post:")
    end = next(i for i, l in enumerate(lines) if i > start and l.strip() == "components:")
    new = "\n".join(lines[:start] + lines[end:])
    assert "operation-removed" in rules(BASE_SPEC, new)


def test_flags_removed_response_code():
    new = BASE_SPEC.replace(
        "        '400':\n          description: bad request\n", ""
    )
    assert "response-removed" in rules(BASE_SPEC, new)


def test_flags_removed_schema():
    new = BASE_SPEC.replace(
        """components:
  schemas:
    Widget:
      type: object
      required:
        - name
      properties:
        name:
          type: string
        color:
          type: string
          enum: [red, green, blue]
""",
        "components:\n  schemas: {}\n",
    )
    assert "schema-removed" in rules(BASE_SPEC, new)


def test_flags_removed_property():
    new = BASE_SPEC.replace("        color:\n          type: string\n          enum: [red, green, blue]\n", "")
    assert "property-removed" in rules(BASE_SPEC, new)


def test_flags_property_becoming_required():
    new = BASE_SPEC.replace(
        "      required:\n        - name\n",
        "      required:\n        - name\n        - color\n",
    )
    assert "property-now-required" in rules(BASE_SPEC, new)


def test_flags_removed_parameter():
    new = BASE_SPEC.replace(
        """      parameters:
        - name: filter
          in: query
          required: false
          schema:
            type: string
""",
        "",
    )
    assert "parameter-removed" in rules(BASE_SPEC, new)


def test_flags_parameter_becoming_required():
    new = BASE_SPEC.replace(
        "          in: query\n          required: false\n",
        "          in: query\n          required: true\n",
    )
    assert "parameter-now-required" in rules(BASE_SPEC, new)


def test_flags_removed_enum_value():
    new = BASE_SPEC.replace("enum: [red, green, blue]", "enum: [red, green]")
    assert "enum-value-removed" in rules(BASE_SPEC, new)


def test_flags_type_change():
    new = BASE_SPEC.replace(
        "        name:\n          type: string\n",
        "        name:\n          type: integer\n",
    )
    assert "type-changed" in rules(BASE_SPEC, new)


def test_flags_request_body_media_type_removed():
    new = BASE_SPEC.replace(
        """      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/Widget'
""",
        "      requestBody:\n        content: {}\n",
    )
    assert "request-body-media-type-removed" in rules(BASE_SPEC, new)


# --- must NOT be flagged: additive / loosening changes -------------------------


def test_new_path_is_not_breaking():
    new = BASE_SPEC + "  /gizmos:\n    get:\n      operationId: listGizmos\n      responses:\n        '200':\n          description: ok\n"
    assert rules(BASE_SPEC, new) == set()


def test_new_operation_is_not_breaking():
    new = BASE_SPEC.replace(
        "    post:",
        "    delete:\n      operationId: deleteWidgets\n      responses:\n        '204':\n          description: ok\n    post:",
    )
    assert rules(BASE_SPEC, new) == set()


def test_new_response_code_is_not_breaking():
    new = BASE_SPEC.replace(
        "        '400':\n          description: bad request\n",
        "        '400':\n          description: bad request\n        '404':\n          description: not found\n",
    )
    assert rules(BASE_SPEC, new) == set()


def test_new_optional_property_is_not_breaking():
    new = BASE_SPEC.replace(
        "        color:\n          type: string\n          enum: [red, green, blue]\n",
        "        color:\n          type: string\n          enum: [red, green, blue]\n        weight:\n          type: number\n",
    )
    assert rules(BASE_SPEC, new) == set()


def test_new_enum_value_is_not_breaking():
    new = BASE_SPEC.replace("enum: [red, green, blue]", "enum: [red, green, blue, yellow]")
    assert rules(BASE_SPEC, new) == set()


def test_property_becoming_optional_is_not_breaking():
    """Loosening a requirement can never surprise an existing valid caller."""
    new = BASE_SPEC.replace("      required:\n        - name\n", "      required: []\n")
    assert rules(BASE_SPEC, new) == set()


def test_parameter_becoming_optional_is_not_breaking():
    new = BASE_SPEC.replace(
        "      parameters:\n        - name: filter\n          in: query\n          required: false\n",
        "      parameters:\n        - name: filter\n          in: query\n          required: true\n",
    )
    # sanity: this direction alone (false->true) IS breaking...
    assert "parameter-now-required" in rules(BASE_SPEC, new)
    # ...but the reverse (true->false) must not be.
    assert rules(new, BASE_SPEC) == set()


def test_description_only_change_is_not_breaking():
    new = BASE_SPEC.replace("description: ok", "description: OK, all good")
    assert rules(BASE_SPEC, new) == set()


def test_identical_spec_has_no_findings():
    assert rules(BASE_SPEC, BASE_SPEC) == set()


def test_new_schema_is_not_breaking():
    new = BASE_SPEC.replace(
        "components:\n  schemas:\n",
        "components:\n  schemas:\n    Gizmo:\n      type: object\n      properties:\n        id:\n          type: string\n",
    )
    assert rules(BASE_SPEC, new) == set()


# --- $ref resolution ------------------------------------------------------------


def test_follows_refs_into_nested_schemas():
    nested = """
openapi: 3.0.3
info:
  title: Test
  version: 1.0.0
paths:
  /things:
    post:
      operationId: createThing
      requestBody:
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/Thing'
      responses:
        '200':
          description: ok
components:
  schemas:
    Thing:
      type: object
      properties:
        inner:
          $ref: '#/components/schemas/Inner'
    Inner:
      type: object
      properties:
        value:
          type: string
"""
    new = nested.replace(
        "    Inner:\n      type: object\n      properties:\n        value:\n          type: string\n",
        "    Inner:\n      type: object\n      properties: {}\n",
    )
    assert "property-removed" in rules(nested, new)
