#!/usr/bin/env python3
import os
import sys
import xml.etree.ElementTree as ET

HOME = os.environ.get("HOME", "/root")
KEYS = {"<Super>i": "python3 /opt/stol/parametry.py", "<Super>z": "python3 /opt/stol/snap.py"}
UCA_DEFAULT = "/opt/stol/uca-default.xml"
SHORTCUTS = os.path.join(HOME, ".config/xfce4/xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml")
UCA = os.path.join(HOME, ".config/Thunar/uca.xml")


def write(tree, path):
    ET.indent(tree)
    tree.write(path, encoding="UTF-8", xml_declaration=True)


def shortcuts():
    if not os.path.isfile(SHORTCUTS):
        return
    tree = ET.parse(SHORTCUTS)
    custom = None
    for commands in tree.getroot().iter("property"):
        if commands.get("name") == "commands":
            for child in commands:
                if child.get("name") == "custom":
                    custom = child
    if custom is None:
        return
    known = {p.get("name") for p in custom}
    changed = False
    for key, command in KEYS.items():
        if key not in known:
            ET.SubElement(custom, "property", name=key, type="string", value=command)
            changed = True
    if changed:
        write(tree, SHORTCUTS)


def uca():
    if not os.path.isfile(UCA_DEFAULT):
        return
    wanted = ET.parse(UCA_DEFAULT).getroot()
    if not os.path.isfile(UCA):
        os.makedirs(os.path.dirname(UCA), exist_ok=True)
        write(ET.ElementTree(wanted), UCA)
        return
    tree = ET.parse(UCA)
    root = tree.getroot()
    known = {a.findtext("unique-id") for a in root.findall("action")}
    changed = False
    for action in wanted.findall("action"):
        if action.findtext("unique-id") not in known:
            root.append(action)
            changed = True
    if changed:
        write(tree, UCA)


for step in (shortcuts, uca):
    try:
        step()
    except (ET.ParseError, OSError) as failure:
        print("migrate-config:", step.__name__, failure, file=sys.stderr)
