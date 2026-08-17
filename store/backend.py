#!/usr/bin/env python3
"""
store/backend.py — логика управления пакетами для Магазина КодоЦех (R17).
"""

import json
import os
import shutil
import subprocess
import sys


def load_catalog(path=None):
    if path is None:
        path = os.path.join(os.path.dirname(__file__), "catalog.json")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def filter_catalog(catalog, query="", category="Все"):
    q = query.strip().lower()
    results = []
    for item in catalog:
        if category != "Все" and item.get("category") != category:
            continue
        if q:
            name = item.get("name", "").lower()
            pkg = item.get("package", "").lower()
            summary = item.get("summary", "").lower()
            if q not in name and q not in pkg and q not in summary:
                continue
        results.append(item)
    return results


def is_installed(package_name, runner=None):
    if runner is None:
        def runner(cmd):
            res = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return res.returncode, res.stdout.decode("utf-8")
    code, out = runner(f"dpkg-query -W -f='${{Status}}' {package_name} 2>/dev/null")
    return "install ok installed" in out


def install_command(package_name):
    return (
        "set -e\n"
        "export DEBIAN_FRONTEND=noninteractive\n"
        f"apt-get update -y || true\n"
        f"apt-get install -y --no-install-recommends {package_name} || (\n"
        "  apt-get -y --fix-broken install\n"
        "  exit 1\n"
        ")\n"
    )


def remove_command(package_name):
    return (
        "set -e\n"
        "export DEBIAN_FRONTEND=noninteractive\n"
        f"apt-get remove -y {package_name}\n"
    )


def check_health_command():
    return "apt-get check\n"
