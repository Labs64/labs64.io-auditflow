"""
Plugin registry for dynamically-loaded modules (transformers / sinks).

Hardening: instead of importing an arbitrary module by name on every request, the registry
discovers the modules shipped in the internal directory and mounted in the bootstrap directory
*once at startup*, validates that each satisfies the plugin contract (defines the required entry
function), and serves an **allow-list**. A request for an id that is not on the allow-list is
rejected before any import is attempted — closing the arbitrary-import / path-traversal surface.

Resilience: a single broken plugin never crashes the service. Modules that fail to import
(e.g. a missing optional dependency) or violate the contract are excluded from the allow-list and
reported via :meth:`errors`, so healthy plugins keep serving.

Distribution: plugins may arrive as loose ``.py`` files in the scanned directories **or** as
installed wheels advertising a Python entry point (``importlib.metadata``). The wheel route gives
plugin authors real dependency management and versioning; the directory route stays the zero-setup
option for a single file. Both produce identical registry entries.

Both Python services ship an identical copy of this file (mirrors the existing ``tracing.py``).
"""
import importlib
import logging
import os
import re
import types
from importlib import metadata

logger = logging.getLogger(__name__)

# Single source of truth for the id format — must stay identical to the backend Java validation.
VALID_ID = re.compile(r'^[a-zA-Z0-9_]+$')

# Discovery precedence, lowest to highest. Later sources win on id collision, so an operator
# can always shadow a shipped or installed plugin by mounting a file — the most local
# intervention beats the most packaged one.
KIND_INTERNAL = "internal"    # shipped with the image
KIND_PACKAGE = "package"      # pip-installed wheel, found via entry points
KIND_EXTERNAL = "external"    # mounted at runtime in the *_bootstrap directory


class PluginNotFoundError(Exception):
    """The requested plugin id is not on the discovered allow-list."""


class PluginRegistry:
    """Discovers, validates, and resolves plugin modules from directories and entry points."""

    def __init__(self, base_dir, dir_specs, entry_point, entry_point_group=None):
        """
        :param base_dir: absolute base directory of the service.
        :param dir_specs: ordered list of ``(subdir_name, kind)`` tuples, e.g.
                          ``[("transformers", "internal"), ("transformers_bootstrap", "external")]``.
                          Later entries override earlier ones when ids collide (bootstrap wins).
        :param entry_point: required entry-function name, e.g. ``"transform"`` or ``"process"``.
        :param entry_point_group: optional ``importlib.metadata`` entry-point group, e.g.
                                  ``"auditflow.sinks"``. Installed wheels advertising this group
                                  are discovered between the internal and external directories:
                                  a wheel overrides a shipped plugin, a mounted file overrides
                                  the wheel. ``None`` disables entry-point discovery entirely.
        """
        self.base_dir = base_dir
        self.dir_specs = dir_specs
        self.entry_point = entry_point
        self.entry_point_group = entry_point_group
        self._plugins = {}   # id -> {"callable", "kind", "path", "module"}
        self._errors = {}    # id -> {"kind", "error"}

    def discover(self):
        """(Re)scan every source and rebuild the allow-list. Returns self."""
        self._plugins = {}
        self._errors = {}

        internal = [spec for spec in self.dir_specs if spec[1] == KIND_INTERNAL]
        external = [spec for spec in self.dir_specs if spec[1] != KIND_INTERNAL]

        self._scan_directories(internal)
        self._scan_entry_points()
        self._scan_directories(external)

        logger.info(
            "Plugin registry ready: %d available, %d failed (entry point '%s'). Available: %s",
            len(self._plugins), len(self._errors), self.entry_point, sorted(self._plugins))
        if self._errors:
            logger.warning("Plugins excluded due to errors: %s", self._errors)
        return self

    def _scan_directories(self, dir_specs):
        for subdir, kind in dir_specs:
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

    def _scan_entry_points(self):
        """Register plugins advertised by installed distributions.

        A broken or hostile distribution must not be able to take the service down, so
        enumeration failures and per-entry-point load failures are both contained — exactly
        as for a broken file in the plugin directory.
        """
        if not self.entry_point_group:
            return
        try:
            entry_points = list(metadata.entry_points(group=self.entry_point_group))
        except Exception as exc:  # noqa: BLE001 - a broken environment must not crash startup
            logger.error("Entry-point discovery for group '%s' failed: %s",
                         self.entry_point_group, exc)
            return

        for entry in sorted(entry_points, key=lambda e: e.name):
            plugin_id = entry.name
            if not VALID_ID.fullmatch(plugin_id):
                logger.warning("Skipping entry-point plugin with invalid id '%s' in group %s",
                               plugin_id, self.entry_point_group)
                continue
            self._load_entry_point(plugin_id, entry)

    def _load_entry_point(self, plugin_id, entry):
        source = f"{self.entry_point_group}:{entry.value}"
        try:
            loaded = entry.load()
            # An entry point may point at the plugin module (``pkg.mod``) or straight at
            # its entry function (``pkg.mod:process``); accept both so authors are not
            # forced into one packaging style.
            #
            # Discriminate on module-ness, not on callability: classes are callable too,
            # and treating a class that happens to lack the entry function as "the entry
            # function" would register a plugin that fails only at delivery time.
            if isinstance(loaded, types.ModuleType):
                module = loaded
                callable_ = getattr(loaded, self.entry_point, None)
            else:
                module, callable_ = None, loaded
            if not callable(callable_):
                raise AttributeError(
                    f"entry point does not resolve to a callable '{self.entry_point}(...)'")

            doc = (module or callable_).__doc__ or ""
            self._plugins[plugin_id] = {
                "callable": callable_,
                "kind": KIND_PACKAGE,
                "path": source,
                "module": module,
                "version": self._distribution_version(entry),
                "description": doc.strip().splitlines()[0] if doc.strip() else "",
                "properties": getattr(module, "PROPERTIES", None) if module else None,
            }
            self._errors.pop(plugin_id, None)
        except Exception as exc:  # noqa: BLE001 - any failure excludes the plugin, never crashes
            logger.error("Plugin '%s' (%s) failed to load and is excluded: %s",
                         plugin_id, source, exc)
            self._errors[plugin_id] = {"kind": KIND_PACKAGE, "error": str(exc)}

    @staticmethod
    def _distribution_version(entry):
        """Version of the wheel providing the entry point — the real, packaged version."""
        try:
            dist = getattr(entry, "dist", None)
            if dist is not None and getattr(dist, "version", None):
                return str(dist.version)
        except Exception:  # noqa: BLE001 - metadata is advisory, never fatal
            pass
        return "0.0.0"

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
