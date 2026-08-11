"""Invariants every bundled transformer must satisfy, whatever `extra` contains.

`AuditEvent.extra` is an OPEN map. The well-known keys are a convention that a transformer MAY
promote; they are never guaranteed to be present, and a deployment's own keys are never anticipated
by a shipped module. Three rules follow, and this file is what keeps them true for transformers
that do not exist yet:

  I1  Open by default   — no key is required.
  I2  Graceful absence  — an absent key yields an OMITTED field, never a fabricated value.
  I3  Lossless          — a non-promoted key still reaches the sink.
  I4  Uniform extension — a module that promotes anything exposes make_transform + .promoted.

Scope is the GENERIC modules: the ones a deployment gets by default and is expected to use
unmodified. A DOMAIN module may legitimately require its own keys (netlicensing_sink raises without
`extra.transaction`) — its obligation is to document them and to gate on eventType first.
"""
import importlib
from pathlib import Path

import pytest

from auditflow_sdk import WELL_KNOWN_EXTRA

TRANSFORMERS_DIR = Path(__file__).resolve().parents[1] / "transformers"

# A value that means "we had nothing, so we made something up". Finding any of these in output is
# an I2 violation: a consumer cannot distinguish it from a publisher that really sent that string.
BANNED_PLACEHOLDERS = {
    "unknown", "unknown_action", "unknown_status", "UNKNOWN", "N/A", "n/a", "none", "None", "null",
}

# zero.py returns the input unchanged: it promotes nothing, so there is no vocabulary to extend.
# Listed explicitly so a NEW transformer cannot skip I4 by accident.
NO_PROMOTION = {"zero"}


def transformer_ids():
    return sorted(
        path.stem for path in TRANSFORMERS_DIR.glob("*.py")
        if not path.stem.startswith("__")
    )


def load(transformer_id):
    return importlib.import_module(f"transformers.{transformer_id}")


def walk_values(node):
    """Yield every scalar in a nested structure, keys included — a fabricated value may hide
    anywhere in the output, including inside a Loki stream label or a nested metadata map."""
    if isinstance(node, dict):
        for key, value in node.items():
            yield key
            yield from walk_values(value)
    elif isinstance(node, (list, tuple)):
        for item in node:
            yield from walk_values(item)
    else:
        yield node


def test_the_parametrization_is_not_vacuous():
    # A glob that silently matched nothing would make every test below pass for free.
    ids = transformer_ids()
    assert {"audit_clickhouse", "audit_loki", "audit_opensearch", "zero"} <= set(ids)


@pytest.mark.parametrize("transformer_id", transformer_ids())
def test_transforms_an_event_with_no_extra_at_all(transformer_id):
    """I1: `extra` is optional, and so is every key in it."""
    module = load(transformer_id)
    for event in ({}, {"eventType": "api.call"}, {"eventType": "api.call", "extra": {}}):
        assert isinstance(module.transform(event), dict)


@pytest.mark.parametrize("transformer_id", transformer_ids())
def test_fabricates_no_placeholder_for_an_absent_key(transformer_id):
    """I2: absent must render as omitted, not as an invented value."""
    module = load(transformer_id)
    output = module.transform({"eventType": "api.call", "timestamp": "2026-08-07T10:15:30Z"})
    found = sorted({
        value for value in walk_values(output)
        if isinstance(value, str) and value in BANNED_PLACEHOLDERS
    })
    assert not found, (
        f"{transformer_id} fabricated {found} for absent input. An absent `extra` key must yield an "
        f"omitted field — a placeholder is indistinguishable from a publisher that sent it.")


@pytest.mark.parametrize("transformer_id", transformer_ids())
def test_unrecognised_extra_keys_survive(transformer_id):
    """I3: a shipped module cannot anticipate a deployment's field names, so it must not drop them."""
    module = load(transformer_id)
    custom = {"invoiceRef": "INV-1", "orderRef": "ORD-7", "shipmentId": "SHP-9"}
    output = module.transform({
        "eventType": "api.call", "timestamp": "2026-08-07T10:15:30Z", "extra": dict(custom),
    })
    values = set(walk_values(output))
    missing = sorted(key for key, value in custom.items() if value not in values)
    assert not missing, (
        f"{transformer_id} dropped {missing}. Every `extra` key must reach the sink through some "
        f"metadata channel, even when the module does not recognise it.")


@pytest.mark.parametrize("transformer_id", transformer_ids())
def test_absent_well_known_keys_do_not_appear_in_the_output(transformer_id):
    """I1/I2 together: the well-known vocabulary is a convention, so its absence is silent."""
    module = load(transformer_id)
    output = module.transform({"eventType": "api.call", "timestamp": "2026-08-07T10:15:30Z"})
    keys = {value for value in walk_values(output) if isinstance(value, str)}
    leaked = sorted(field for field in WELL_KNOWN_EXTRA.values() if field in keys)
    assert not leaked, (
        f"{transformer_id} emitted fields {leaked} for keys the event never carried.")


@pytest.mark.parametrize("transformer_id", transformer_ids())
def test_exposes_the_uniform_extension_point(transformer_id):
    """I4: one extension mechanism across every bundled transformer."""
    module = load(transformer_id)
    if transformer_id in NO_PROMOTION:
        pytest.skip(f"{transformer_id} promotes nothing — no vocabulary to extend")

    assert callable(getattr(module, "make_transform", None)), (
        f"{transformer_id} must expose make_transform(extra_promoted=None, module_id=None)")
    assert isinstance(module.transform.promoted, dict)

    # The extension point must actually take effect, not merely exist.
    built = module.make_transform({"invoiceRef": "invoice_ref"})
    assert built.promoted["invoiceRef"] == "invoice_ref"
    output = built({"eventType": "api.call", "extra": {"invoiceRef": "INV-1"}})
    assert "invoice_ref" in set(walk_values(output))
