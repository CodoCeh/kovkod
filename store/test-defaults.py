#!/usr/bin/env python3
import json
import os
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(REPO, "store"))
import backend

VAULT_DEFAULTS = os.path.join(REPO, "image", "obsidian-defaults")
CLAUDE_DEFAULTS = os.path.join(REPO, "image", "claude-defaults")


def registry_path(home):
    return os.path.join(home, ".config", "Obsidian", "obsidian.json")


with tempfile.TemporaryDirectory() as home:
    backend.setup_obsidian_defaults(VAULT_DEFAULTS, home)
    vault = os.path.join(home, "Obsidian")
    registered = json.load(open(registry_path(home), encoding="utf-8"))["vaults"]["kovkod00obsidian"]
    assert registered["path"] == vault and registered["open"] is True
    workspace = json.load(open(os.path.join(vault, ".obsidian", "workspace.json"), encoding="utf-8"))
    assert "Начните здесь.md" in json.dumps(workspace, ensure_ascii=False)
    assert os.path.isfile(os.path.join(vault, "Начните здесь.md"))
    assert os.path.isdir(os.path.join(vault, "Projects"))

    note = os.path.join(vault, "Начните здесь.md")
    open(note, "w", encoding="utf-8").write("своё")
    backend.setup_obsidian_defaults(VAULT_DEFAULTS, home)
    assert open(note, encoding="utf-8").read() == "своё"

    backend._copy_missing(CLAUDE_DEFAULTS, os.path.join(home, ".claude"))
    settings = json.load(open(os.path.join(home, ".claude", "settings.json"), encoding="utf-8"))
    assert settings["permissions"]["additionalDirectories"] == ["~/Obsidian"]
    assert settings["attribution"] == {"commit": "", "pr": ""}
    assert "defaultMode" not in settings["permissions"]
    skill = os.path.join(home, ".claude", "skills", "obsidian-docs")
    assert sorted(os.listdir(os.path.join(skill, "templates"))) == [
        "Architecture.md", "Changelog.md", "Decisions.md", "Overview.md", "Scenarios.md",
    ]

with tempfile.TemporaryDirectory() as home:
    registry = registry_path(home)
    os.makedirs(os.path.dirname(registry))
    open(registry, "w", encoding="utf-8").write('{"vaults":{"mine":{"path":"/root/Vault"}}}')
    backend.setup_obsidian_defaults(VAULT_DEFAULTS, home)
    assert "mine" in open(registry, encoding="utf-8").read()
    assert not os.path.exists(os.path.join(home, "Obsidian"))

print("ЗЕЛЁНО")
