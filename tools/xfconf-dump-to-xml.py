#!/usr/bin/env python3
"""Превращает дамп `xfconf-query -c <канал> -l -v` в канальный XML xfconf.

Вход — файл вида `reference/xfconf/<канал>.txt`: ключ, отбивка пробелами, значение.
Выход — `<канал>.xml`, тот самый формат, который xfconfd читает из
`$HOME/.config/xfce4/xfconf/xfce-perchannel-xml/`.

Типы выводятся из записи значения: `true|false` → bool, целое → int, дробное → double,
`[a,b,c]` → array с поэлементным выводом типа, пустое → empty, остальное → string.
Обратная сверка (записали XML → прочитали `xfconf-query -l -v` → сравнили с дампом)
делается в mkimage.sh: она и есть доказательство по R08.1.

Использование: xfconf-dump-to-xml.py <канал> <dump.txt> <out.xml>
"""

import re
import sys
from xml.sax.saxutils import quoteattr

INT_RE = re.compile(r"^-?\d+$")
DOUBLE_RE = re.compile(r"^-?\d+\.\d+$")


def scalar_type(value):
    if value in ("true", "false"):
        return "bool"
    if INT_RE.match(value):
        return "int"
    if DOUBLE_RE.match(value):
        return "double"
    return "string"


def parse_dump(text):
    """[(['general','theme'], 'Yaru'), ...] в порядке появления."""
    entries = []
    for raw in text.splitlines():
        line = raw.rstrip("\n")
        if not line.strip() or not line.startswith("/"):
            continue
        parts = re.split(r"\s{2,}", line, maxsplit=1)
        key = parts[0].strip()
        value = parts[1].strip() if len(parts) > 1 else ""
        segments = [s for s in key.split("/") if s]
        if not segments:
            continue
        entries.append((segments, value))
    return entries


def build_tree(entries):
    """Вложенные словари: имя → {'children': {...}, 'value': str|None}."""
    root = {}
    for segments, value in entries:
        node = root
        for seg in segments[:-1]:
            node = node.setdefault(seg, {"children": {}, "value": None})["children"]
        leaf = node.setdefault(segments[-1], {"children": {}, "value": None})
        leaf["value"] = value
    return root


def emit(node, name, depth, out):
    pad = "  " * depth
    value = node["value"]
    children = node["children"]

    if value is None:
        out.append(f"{pad}<property name={quoteattr(name)} type=\"empty\">")
        emit_children(children, depth + 1, out)
        out.append(f"{pad}</property>")
        return

    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1]
        items = [i for i in inner.split(",")] if inner else []
        out.append(f"{pad}<property name={quoteattr(name)} type=\"array\">")
        for item in items:
            out.append(
                f"{pad}  <value type=\"{scalar_type(item)}\" value={quoteattr(item)}/>"
            )
        emit_children(children, depth + 1, out)
        out.append(f"{pad}</property>")
        return

    if value == "" and not children:
        # Пустая строка, а не type="empty": `xfconf-query -l` показывает пустые узлы как
        # ветки и в перечень их не берёт, а в эталонном дампе эти ключи есть.
        out.append(f"{pad}<property name={quoteattr(name)} type=\"string\" value=\"\"/>")
        return

    attrs = f"name={quoteattr(name)} type=\"{scalar_type(value)}\" value={quoteattr(value)}"
    if children:
        out.append(f"{pad}<property {attrs}>")
        emit_children(children, depth + 1, out)
        out.append(f"{pad}</property>")
    else:
        out.append(f"{pad}<property {attrs}/>")


def emit_children(children, depth, out):
    for name in sorted(children):
        emit(children[name], name, depth, out)


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    channel, dump_path, out_path = sys.argv[1:4]

    with open(dump_path, encoding="utf-8") as fh:
        entries = parse_dump(fh.read())
    if not entries:
        sys.exit(f"mkimage: дамп {dump_path} пуст — сверять нечего")

    tree = build_tree(entries)
    lines = ['<?xml version="1.0" encoding="UTF-8"?>', "", f'<channel name={quoteattr(channel)} version="1.0">']
    emit_children(tree, 1, lines)
    lines.append("</channel>")
    lines.append("")

    with open(out_path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    print(f"  {channel}: {len(entries)} ключей → {out_path}")


if __name__ == "__main__":
    main()
