"""
Optional SDK for AuditFlow plugins (P2-5).

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
from abc import ABC, abstractmethod
from typing import Any, Callable, Dict

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
