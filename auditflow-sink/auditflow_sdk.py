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
import gzip
import hashlib
import json
import re
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Iterable, List, Sequence

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


def sql_identifier(value: Any, what: str, max_parts: int = 1, max_length: int = 255, dollar: bool = False) -> str:
    """Return ``value`` if it is a plain SQL identifier, else raise ValueError.

    Table, column and database names are part of the SQL text and cannot be bound parameters. They
    come from the tenant file, never from an event, but a typo or a pasted fragment there must not
    become SQL. So only an unquoted identifier is accepted: a letter or underscore, then letters,
    digits and underscores (``dollar=True`` also allows ``$``, which Snowflake permits), at most
    ``max_length`` characters, in up to ``max_parts`` dot-separated parts (``schema.table``). A name
    that needs quoting is not supported: rename it or point the sink at a view.
    """
    text = str(value).strip() if value is not None else ''
    parts = text.split('.')
    tail = 'A-Za-z0-9_$' if dollar else 'A-Za-z0-9_'
    part = re.compile(rf'[A-Za-z_][{tail}]*')
    if not text or len(parts) > max_parts or any(
            not part.fullmatch(name) or len(name) > max_length for name in parts):
        allowed = 'letters, digits, underscores' + (' and $' if dollar else '')
        shape = 'a plain identifier' if max_parts == 1 else f'a plain identifier of up to {max_parts} dot-separated parts'
        raise ValueError(f"Invalid {what} '{text}': use {shape} ({allowed}, not starting with a digit, "
                         f"at most {max_length} characters per part)")
    return text


# ── Batch delivery ──────────────────────────────────────────────────────────────────────────────
#
# A sink module may define ``process_batch(events, properties) -> list`` next to ``process``. It is
# called for pipelines with ``batch.enabled`` and returns ONE outcome per event, in input order: a
# result dict (or None) for a delivered event, an ``Exception`` for a failed one. The service turns
# that into the per-event answer of ``POST /sink/<id>/batch``:
#
#   * a ``ValueError`` outcome (``RejectedEvent`` is one) is NOT retried: the destination refused
#     this event's data, and sending the same event again cannot succeed;
#   * any other exception is retried by the backend with its usual delays.
#
# Raising from ``process_batch`` fails the whole call and every event in it is retried. Use that for
# configuration errors (a missing property), which an operator can fix while the events wait.
#
# Delivery is at-least-once: a batch whose answer is lost is sent again, possibly grouped with other
# events. Write so that a repeat is harmless (a deterministic object name, a key the destination
# de-duplicates on) or tell readers to de-duplicate by ``eventId``.


class RejectedEvent(ValueError):
    """The destination refused this event's data; retrying the same event cannot succeed."""


def deliver_each(events: Sequence[Dict[str, Any]], properties: Dict[str, Any], process: ProcessFn) -> List[Any]:
    """Outcomes of sending the events one by one through ``process``.

    For a ``process_batch`` whose destination refused the batch as a whole because of one event: the
    others are still delivered, and only the refused one fails.
    """
    outcomes: List[Any] = []
    for event in events:
        try:
            outcomes.append(process(event, properties))
        except Exception as e:  # noqa: BLE001 - reported per event
            outcomes.append(e)
    return outcomes


def chunk_indexes(sizes: Sequence[int], max_count: int, max_bytes: int) -> List[List[int]]:
    """Split ``range(len(sizes))`` into consecutive chunks of at most ``max_count`` entries and
    ``max_bytes`` in total, for APIs that limit both. An entry larger than ``max_bytes`` gets a chunk
    of its own (the destination then rejects just that one)."""
    chunks: List[List[int]] = []
    current: List[int] = []
    total = 0
    for index, size in enumerate(sizes):
        if current and (len(current) >= max_count or total + size > max_bytes):
            chunks.append(current)
            current, total = [], 0
        current.append(index)
        total += size
    if current:
        chunks.append(current)
    return chunks


def event_datetime(event_data: Dict[str, Any]) -> datetime:
    """The event's server receipt time (``timestamp``); now when absent or unparseable."""
    value = event_data.get('timestamp')
    if isinstance(value, str) and value:
        try:
            parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
            return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)
        except ValueError:
            pass
    return datetime.now(timezone.utc)


def batch_object_name(group: Sequence[Dict[str, Any]], compress: bool) -> str:
    """Deterministic file name of a batch object: the earliest event time plus a hash of the sorted
    event ids. The same group of events gets the same name in any order, so a redelivery overwrites
    the object instead of adding a copy."""
    ids = sorted(str(e.get('eventId', '')) for e in group)
    digest = hashlib.sha256('\n'.join(ids).encode('utf-8')).hexdigest()[:32]
    stamp = min(event_datetime(e) for e in group).strftime('%Y%m%d-%H%M%S')
    return f"{stamp}-batch-{digest}.jsonl" + ('.gz' if compress else '')


def jsonl_body(group: Sequence[Dict[str, Any]], compress: bool) -> bytes:
    """The events as JSON Lines (one event per line), gzip-compressed on request."""
    content = ''.join(json.dumps(event) + '\n' for event in group).encode('utf-8')
    # mtime=0: the same events give the same bytes, so a rewritten object keeps its checksum.
    return gzip.compress(content, mtime=0) if compress else content


# ── Reserved-name collisions ────────────────────────────────────────────────────────────────────
#
# `extra` is an OPEN, publisher-controlled key space, and a sink that flattens it into a wire format
# writes into a namespace that already has names of its own (a CEF extension key, a log field). Two
# values therefore compete for one name.
#
# ONE convention everywhere: the sink's own value keeps the plain name, and the `extra` key is
# emitted under `extra_<name>` (then `extra_<name>_2`, `_3`, … if that is taken too). Dropping the
# `extra` key would violate "nothing is dropped"; overwriting would let a publisher rewrite its own
# audit record. Both values survive, and which is which is unambiguous.
#
# Kept identical to the transformer's copy of this SDK — the two services share one convention.
RENAMED_PREFIX = "extra_"


def collision_free_name(field: str, taken: Iterable[str]) -> str:
    """Return ``field``, or ``extra_<field>`` (``_2``, ``_3``, …) when ``field`` is already taken."""
    taken = set(taken)
    if field not in taken:
        return field
    candidate = f"{RENAMED_PREFIX}{field}"
    suffix = 2
    while candidate in taken:
        candidate = f"{RENAMED_PREFIX}{field}_{suffix}"
        suffix += 1
    return candidate
