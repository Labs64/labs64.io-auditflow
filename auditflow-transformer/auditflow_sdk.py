"""
Optional SDK for AuditFlow plugins.

Plugins stay simple: a transformer module defines ``transform(input_data: dict) -> dict`` and a
sink module defines ``process(event_data: dict, properties: dict) -> dict``. Everything here is
*optional* — the registry surfaces it but never requires it. Existing bare-function modules keep
working unchanged.

Conventions the registry reads (all optional):
    __version__ = "1.0.0"                 # plugin version, shown in GET /registry
    PROPERTIES  = {"webhook-url": "..."}  # documented config keys (sinks)
    # the module docstring's first line is used as the plugin description

Typed base classes are provided for editor/type-checker support. If you prefer an OO style,
subclass one and bind the entry point at module level, e.g.::

    class MySink(BaseSink):
        version = "1.0.0"
        def process(self, event_data, properties):
            ...

    process = MySink().process   # the registry resolves the module-level callable
"""
import json
import os
import re
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict, Optional

# Entry-point signatures (handy for type hints in bare-function modules).
TransformFn = Callable[[Dict[str, Any]], Dict[str, Any]]
ProcessFn = Callable[[Dict[str, Any], Dict[str, Any]], Dict[str, Any]]


class BaseTransformer(ABC):
    """Optional base class for transformer plugins."""

    version: str = "0.0.0"

    @abstractmethod
    def transform(self, input_data: Dict[str, Any]) -> Dict[str, Any]:
        """Reshape/enrich the event and return the new event dict."""
        raise NotImplementedError


class BaseSink(ABC):
    """Optional base class for sink plugins."""

    version: str = "0.0.0"

    @abstractmethod
    def process(self, event_data: Dict[str, Any], properties: Dict[str, Any]) -> Dict[str, Any]:
        """Deliver the event to the destination and return a result dict."""
        raise NotImplementedError


def require_properties(properties: Dict[str, Any], *required: str) -> None:
    """Raise ValueError if any required property key is missing/empty. Convenience for sinks."""
    missing = [key for key in required if not properties.get(key)]
    if missing:
        raise ValueError(f"Missing required propert{'y' if len(missing) == 1 else 'ies'}: {', '.join(missing)}")


# ── The `extra` promotion vocabulary ────────────────────────────────────────────────────────────
#
# `AuditEvent.extra` is an OPEN map: no key is required, and every deployment defines its own field
# set. The map below is the *generic audit-semantics convention* — the keys the bundled transformers
# recognise and promote out of `extra` into dedicated fields/columns, which is what makes them
# queryable as report dimensions rather than opaque map entries.
#
# It is a convention, never a schema. Unrecognised keys are always passed through to the sink, and
# an absent key yields an omitted field, never a fabricated value.
#
# Published as the "Well-known keys" table on the `Extra` schema in
# auditflow-api/src/main/resources/openapi/openapi-audit-v1.yaml — keep the two in sync. Values are
# the snake_case field/column names; `audit_loki` deliberately keeps the camelCase keys instead.
WELL_KNOWN_EXTRA: Dict[str, str] = {
    "actionName": "action_name",
    "actionStatus": "action_status",
    "actionMessage": "action_message",
    "userId": "user_id",
    "sessionId": "session_id",
    "durationMs": "duration_ms",
    "responseStatus": "response_status",
}

# Promotion targets land in SQL identifier position (a ClickHouse column name), so an
# operator-supplied name is constrained to a plain identifier.
_TARGET_NAME = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")

PROMOTED_KEYS_ENV = "AUDITFLOW_PROMOTED_KEYS"


def _parse_promoted_env(var_name: str) -> Dict[str, str]:
    """Parse one `{extra key: target field}` env mapping, or return {} when unset.

    :raises ValueError: on malformed JSON, a non-object, or an unusable target name. The caller is
        a module body, so this surfaces as a failed import: PluginRegistry excludes the module and
        reports it in GET /registry. That is deliberate — warn-and-ignore would turn an operator's
        typo into silently missing analytics data.
    """
    raw = os.environ.get(var_name)
    if raw is None or not raw.strip():
        return {}
    try:
        parsed = json.loads(raw)
    except ValueError as exc:
        raise ValueError(f"{var_name} is not valid JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise ValueError(
            f"{var_name} must be a JSON object of {{extra key: target field}}, "
            f"got {type(parsed).__name__}")
    for key, target in parsed.items():
        if not isinstance(target, str) or not _TARGET_NAME.fullmatch(target):
            raise ValueError(
                f"{var_name}[{key!r}] target field name must match "
                f"{_TARGET_NAME.pattern!r}, got {target!r}")
    return parsed


def resolve_promoted(base: Dict[str, str], module_id: Optional[str] = None) -> Dict[str, str]:
    """Merge the deployment's env promotion mapping onto a module's built-in one.

    Precedence, lowest to highest: the module's built-in vocabulary and its
    ``make_transform(extra_promoted)`` argument (both already folded into ``base``), then
    ``AUDITFLOW_PROMOTED_KEYS``, then ``AUDITFLOW_PROMOTED_KEYS_<MODULE_ID>``. Code declares the
    domain; the operator overrides the code.

    :param base: ``{extra key: target field}`` the module ships with.
    :param module_id: the module's own id, enabling its scoped env var. Pass ``__name__`` from a
        domain module; omit it to honour only the global mapping.
    :returns: a new mapping — ``base`` is never mutated.
    """
    promoted = dict(base or {})
    promoted.update(_parse_promoted_env(PROMOTED_KEYS_ENV))
    if module_id:
        promoted.update(_parse_promoted_env(f"{PROMOTED_KEYS_ENV}_{module_id.upper()}"))
    return promoted


def stringify_value(value: Any) -> str:
    """Render a value for a string-typed map (a ClickHouse Map(String, String), Loki structured
    metadata, a CEF extension). JSON-encodes anything that is not a scalar."""
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        # Checked before int — bool is an int subclass, and "True" is not valid JSON.
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    return json.dumps(value, separators=(",", ":"), sort_keys=True)
