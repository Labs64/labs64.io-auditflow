"""Unit tests for the plugin registry allow-list / hardening."""
import sys
import textwrap
from pathlib import Path

import pytest

from plugin_registry import PluginRegistry, PluginNotFoundError


def _write(plugin_dir: Path, name: str, body: str):
    (plugin_dir / f"{name}.py").write_text(textwrap.dedent(body))


@pytest.fixture
def plugin_dir(tmp_path, monkeypatch):
    d = tmp_path / "plugins"
    d.mkdir()
    monkeypatch.syspath_prepend(str(d))
    for module_name in ("good_plugin", "no_entry_plugin", "broken_plugin"):
        sys.modules.pop(module_name, None)
    return d


def _registry(plugin_dir: Path):
    return PluginRegistry(str(plugin_dir.parent), [("plugins", "internal")], "run").discover()


def test_discovers_and_resolves_valid_plugin(plugin_dir):
    _write(plugin_dir, "good_plugin", "def run(x):\n    return x\n")
    registry = _registry(plugin_dir)

    assert "good_plugin" in [p["id"] for p in registry.list_available()]
    assert registry.resolve("good_plugin")({"a": 1}) == {"a": 1}


def test_unknown_id_raises_not_found(plugin_dir):
    registry = _registry(plugin_dir)
    with pytest.raises(PluginNotFoundError):
        registry.resolve("does_not_exist")


def test_malformed_id_raises_not_found(plugin_dir):
    registry = _registry(plugin_dir)
    with pytest.raises(PluginNotFoundError):
        registry.resolve("../etc/passwd")


def test_missing_entry_point_is_excluded(plugin_dir):
    _write(plugin_dir, "no_entry_plugin", "VALUE = 1\n")  # defines no run()
    registry = _registry(plugin_dir)

    assert "no_entry_plugin" not in [p["id"] for p in registry.list_available()]
    assert "no_entry_plugin" in registry.errors()
    with pytest.raises(PluginNotFoundError):
        registry.resolve("no_entry_plugin")


def test_import_error_is_excluded_without_crashing(plugin_dir):
    _write(plugin_dir, "broken_plugin", "import a_module_that_truly_does_not_exist\n")
    registry = _registry(plugin_dir)  # must not raise

    assert "broken_plugin" in registry.errors()
    assert "broken_plugin" not in [p["id"] for p in registry.list_available()]


def test_details_captures_optional_metadata(plugin_dir):
    _write(plugin_dir, "good_plugin", textwrap.dedent('''
        """A demo plugin."""
        __version__ = "2.3.4"
        PROPERTIES = {"key": "a documented property"}
        def run(x):
            return x
    '''))
    registry = _registry(plugin_dir)
    detail = next(d for d in registry.details() if d["id"] == "good_plugin")

    assert detail["version"] == "2.3.4"
    assert detail["description"] == "A demo plugin."
    assert detail["properties"] == {"key": "a documented property"}


def test_reload_picks_up_new_plugin(plugin_dir):
    registry = _registry(plugin_dir)
    assert "late_plugin" not in [p["id"] for p in registry.list_available()]

    _write(plugin_dir, "late_plugin", "def run(x):\n    return x\n")
    registry.reload()
    assert "late_plugin" in [p["id"] for p in registry.list_available()]
