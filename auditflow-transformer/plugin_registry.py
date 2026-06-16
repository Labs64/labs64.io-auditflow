"""
Plugin registry for dynamically-loaded modules (transformers / sinks).

Hardening (P1-4): instead of importing an arbitrary module by name on every request, the registry
discovers the modules shipped in the internal directory and mounted in the bootstrap directory
*once at startup*, validates that each satisfies the plugin contract (defines the required entry
function), and serves an **allow-list**. A request for an id that is not on the allow-list is
rejected before any import is attempted — closing the arbitrary-import / path-traversal surface.

Resilience: a single broken plugin never crashes the service. Modules that fail to import
(e.g. a missing optional dependency) or violate the contract are excluded from the allow-list and
reported via :meth:`errors`, so healthy plugins keep serving.

Both Python services ship an identical copy of this file (mirrors the existing ``tracing.py``).
"""
import importlib
import logging
import os
import re

logger = logging.getLogger(__name__)

# Single source of truth for the id format — must stay identical to the backend Java validation.
VALID_ID = re.compile(r'[a-zA-Z0-9_]+')


class PluginNotFoundError(Exception):
    """The requested plugin id is not on the discovered allow-list."""


class PluginRegistry:
    """Discovers, validates, and resolves plugin modules from a fixed set of directories."""

    def __init__(self, base_dir, dir_specs, entry_point):
        """
        :param base_dir: absolute base directory of the service.
        :param dir_specs: ordered list of ``(subdir_name, kind)`` tuples, e.g.
                          ``[("transformers", "internal"), ("transformers_bootstrap", "external")]``.
                          Later entries override earlier ones when ids collide (bootstrap wins).
        :param entry_point: required entry-function name, e.g. ``"transform"`` or ``"process"``.
        """
        self.base_dir = base_dir
        self.dir_specs = dir_specs
        self.entry_point = entry_point
        self._plugins = {}   # id -> {"callable", "kind", "path", "module"}
        self._errors = {}    # id -> {"kind", "error"}

    def discover(self):
        """(Re)scan the configured directories and rebuild the allow-list. Returns self."""
        self._plugins = {}
        self._errors = {}
        for subdir, kind in self.dir_specs:
            path = os.path.join(self.base_dir, subdir)
            if not os.path.isdir(path):
                logger.warning("Plugin directory not found: %s. Skipping.", path)
                continue
            for filename in sorted(os.listdir(path)):
                if not filename.endswith('.py') or filename.startswith('__'):
                    continue
                plugin_id = filename[:-3]
                if not VALID_ID.fullmatch(plugin_id):
                    logger.warning("Skipping plugin with invalid id '%s' in %s", plugin_id, subdir)
                    continue
                self._load_one(plugin_id, kind, f"{subdir}/{filename}")
        logger.info(
            "Plugin registry ready: %d available, %d failed (entry point '%s'). Available: %s",
            len(self._plugins), len(self._errors), self.entry_point, sorted(self._plugins))
        if self._errors:
            logger.warning("Plugins excluded due to errors: %s", self._errors)
        return self

    def reload(self):
        """Re-run discovery (hot-reload of newly mounted bootstrap plugins). Returns self."""
        return self.discover()

    def _load_one(self, plugin_id, kind, rel_path):
        try:
            module = importlib.import_module(plugin_id)
            entry = getattr(module, self.entry_point, None)
            if not callable(entry):
                raise AttributeError(
                    f"module does not define a callable '{self.entry_point}(...)'")
            self._plugins[plugin_id] = {
                "callable": entry, "kind": kind, "path": rel_path, "module": module,
                # Optional SDK metadata — surfaced by the registry, never required.
                "version": str(getattr(module, "__version__", "0.0.0")),
                "description": (module.__doc__ or "").strip().splitlines()[0] if module.__doc__ else "",
                "properties": getattr(module, "PROPERTIES", None),
            }
            self._errors.pop(plugin_id, None)
        except Exception as exc:  # noqa: BLE001 - any failure excludes the plugin, never crashes
            logger.error("Plugin '%s' (%s) failed to load and is excluded: %s", plugin_id, rel_path, exc)
            self._errors[plugin_id] = {"kind": kind, "error": str(exc)}

    def resolve(self, plugin_id):
        """Return the entry-point callable for an allow-listed plugin.

        :raises PluginNotFoundError: if the id is malformed or not on the allow-list.
        """
        if not plugin_id or not VALID_ID.fullmatch(plugin_id):
            raise PluginNotFoundError(f"invalid plugin id '{plugin_id}'")
        entry = self._plugins.get(plugin_id)
        if entry is None:
            raise PluginNotFoundError(f"plugin '{plugin_id}' is not registered")
        return entry["callable"]

    def list_available(self):
        """List the allow-listed plugins (id, type, path)."""
        return [
            {"id": pid, "type": meta["kind"], "path": meta["path"]}
            for pid, meta in sorted(self._plugins.items())
        ]

    def details(self):
        """Full registry view including optional SDK metadata (version, description, properties)."""
        return [
            {
                "id": pid,
                "type": meta["kind"],
                "path": meta["path"],
                "version": meta["version"],
                "description": meta["description"],
                "properties": meta["properties"],
            }
            for pid, meta in sorted(self._plugins.items())
        ]

    def errors(self):
        """Map of discovered-but-excluded plugin id -> {kind, error}."""
        return dict(self._errors)
