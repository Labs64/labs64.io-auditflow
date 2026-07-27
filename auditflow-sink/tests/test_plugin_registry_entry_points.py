"""Entry-point plugin discovery — the wheel-shipped half of the plugin contract.

A plugin installed as a wheel must reach the registry exactly like a file dropped in a
plugin directory: same id validation, same allow-list, same containment when it is
broken. These tests pin that equivalence, and the precedence between the three sources.

Both Python services ship an identical copy of this file; keep them in step.
"""
import textwrap
import types
from pathlib import Path

import pytest

import plugin_registry
from plugin_registry import KIND_EXTERNAL, KIND_INTERNAL, KIND_PACKAGE, PluginRegistry

GROUP = "auditflow.test-plugins"


def fake_module(name="pkg.mod", doc="", **attrs):
    """A real module object — the registry keys on module-ness, so stand-ins must be modules."""
    module = types.ModuleType(name)
    module.__doc__ = doc
    for key, value in attrs.items():
        setattr(module, key, value)
    return module


class FakeDistribution:
    def __init__(self, version):
        self.version = version


class FakeEntryPoint:
    """Stands in for importlib.metadata.EntryPoint without installing a real wheel."""

    def __init__(self, name, value, target, version="1.2.3", error=None):
        self.name = name
        self.value = value
        self.dist = FakeDistribution(version)
        self._target = target
        self._error = error

    def load(self):
        if self._error:
            raise self._error
        return self._target


@pytest.fixture
def entry_points(monkeypatch):
    """Install a controllable set of entry points for the group under test."""
    registered = []

    def fake_entry_points(group=None):
        return list(registered) if group == GROUP else []

    monkeypatch.setattr(plugin_registry.metadata, "entry_points", fake_entry_points)
    return registered


@pytest.fixture
def plugin_dir(tmp_path, monkeypatch):
    d = tmp_path / "plugins"
    d.mkdir()
    monkeypatch.syspath_prepend(str(d))
    return d


def write_plugin(plugin_dir: Path, name: str, body: str):
    (plugin_dir / f"{name}.py").write_text(textwrap.dedent(body))


def registry_for(base_dir, dir_specs=(), group=GROUP):
    return PluginRegistry(str(base_dir), list(dir_specs), "run", entry_point_group=group).discover()


# --- discovery ----------------------------------------------------------------


def test_discovers_a_plugin_module_from_an_entry_point(tmp_path, entry_points):
    module = fake_module(doc="A wheel sink.", run=lambda payload: {"seen": payload})
    entry_points.append(FakeEntryPoint("wheel_plugin", "pkg.mod", module))

    registry = registry_for(tmp_path)

    assert registry.resolve("wheel_plugin")({"a": 1}) == {"seen": {"a": 1}}


def test_accepts_an_entry_point_pointing_straight_at_the_function(tmp_path, entry_points):
    # Authors should not be forced into one packaging style.
    entry_points.append(FakeEntryPoint("fn_plugin", "pkg.mod:run", lambda payload: "called"))

    registry = registry_for(tmp_path)

    assert registry.resolve("fn_plugin")({}) == "called"


def test_reports_wheel_version_and_description(tmp_path, entry_points):
    module = fake_module(doc="Ships events to Acme.\n\nMore.", run=lambda p: p)
    entry_points.append(FakeEntryPoint("acme", "acme.sink", module, version="4.5.6"))

    details = {d["id"]: d for d in registry_for(tmp_path).details()}

    assert details["acme"]["version"] == "4.5.6"
    assert details["acme"]["description"] == "Ships events to Acme."
    assert details["acme"]["type"] == KIND_PACKAGE
    assert details["acme"]["path"] == f"{GROUP}:acme.sink"


def test_surfaces_declared_properties_like_a_file_plugin(tmp_path, entry_points):
    module = fake_module(run=lambda p: p, PROPERTIES={"url": "required"})
    entry_points.append(FakeEntryPoint("props", "pkg.mod", module))

    details = {d["id"]: d for d in registry_for(tmp_path).details()}

    assert details["props"]["properties"] == {"url": "required"}


def test_no_group_configured_means_no_entry_point_discovery(tmp_path, entry_points):
    entry_points.append(FakeEntryPoint("wheel_plugin", "pkg.mod", fake_module(run=lambda p: p)))

    registry = registry_for(tmp_path, group=None)

    assert registry.list_available() == []


# --- validation and containment -----------------------------------------------


def test_rejects_an_invalid_entry_point_id(tmp_path, entry_points):
    # Same ^[a-zA-Z0-9_]+$ rule as the directory scan and the Java backend.
    entry_points.append(FakeEntryPoint("bad-id!", "pkg.mod", fake_module(run=lambda p: p)))

    registry = registry_for(tmp_path)

    assert registry.list_available() == []
    assert "bad-id!" not in registry.errors()


def test_a_broken_wheel_is_excluded_not_fatal(tmp_path, entry_points):
    good = fake_module(run=lambda p: "ok")
    entry_points.append(FakeEntryPoint("healthy", "pkg.good", good))
    entry_points.append(
        FakeEntryPoint("broken", "pkg.broken", None, error=ImportError("no module named acme_sdk")))

    registry = registry_for(tmp_path)

    assert registry.resolve("healthy")({}) == "ok"
    assert "broken" in registry.errors()
    assert "acme_sdk" in registry.errors()["broken"]["error"]


def test_an_entry_point_without_the_contract_function_is_excluded(tmp_path, entry_points):
    entry_points.append(FakeEntryPoint("no_contract", "pkg.mod", fake_module()))

    registry = registry_for(tmp_path)

    assert registry.list_available() == []
    assert "no_contract" in registry.errors()


def test_broken_metadata_does_not_crash_startup(tmp_path, monkeypatch):
    def exploding(group=None):
        raise RuntimeError("corrupt dist-info")

    monkeypatch.setattr(plugin_registry.metadata, "entry_points", exploding)

    registry = PluginRegistry(str(tmp_path), [], "run", entry_point_group=GROUP).discover()

    assert registry.list_available() == []


# --- precedence ---------------------------------------------------------------


def test_a_wheel_overrides_a_shipped_plugin(plugin_dir, entry_points):
    write_plugin(plugin_dir, "dup", "def run(payload):\n    return 'shipped'\n")
    module = fake_module(run=lambda p: "wheel")
    entry_points.append(FakeEntryPoint("dup", "pkg.mod", module))

    registry = registry_for(plugin_dir.parent, [("plugins", KIND_INTERNAL)])

    assert registry.resolve("dup")({}) == "wheel"


def test_a_mounted_file_overrides_a_wheel(plugin_dir, entry_points):
    # The most local intervention wins: an operator must always be able to shadow an
    # installed plugin without repackaging it.
    write_plugin(plugin_dir, "dup2", "def run(payload):\n    return 'mounted'\n")
    module = fake_module(run=lambda p: "wheel")
    entry_points.append(FakeEntryPoint("dup2", "pkg.mod", module))

    registry = registry_for(plugin_dir.parent, [("plugins", KIND_EXTERNAL)])

    assert registry.resolve("dup2")({}) == "mounted"


def test_reload_rediscovers_entry_points(tmp_path, entry_points):
    registry = registry_for(tmp_path)
    assert registry.list_available() == []

    entry_points.append(FakeEntryPoint("late", "pkg.mod", fake_module(run=lambda p: p)))
    registry.reload()

    assert [p["id"] for p in registry.list_available()] == ["late"]
