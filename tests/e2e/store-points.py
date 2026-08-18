#!/usr/bin/env python3
"""Куда ткнуть на столе: точки для тапа, выведенные из состояния экрана (R05.4i).

Слепых координат в проверке магазина быть не может: вписанное число доказывает
случайность, а не работу ярлыка. Здесь два способа получить точку, и оба читают
то, что на экране прямо сейчас.

``icon``    — ярлык на столе. Позиции значков xfdesktop держит сам: строку и
              столбец каждого ярлыка он пишет в ``icons.screen0.yaml``. Размер
              клетки в этом файле не назван, поэтому полосы значков находятся
              на снимке телефона: столбцы — по горизонтальной проекции, строки
              внутри столбца — по вертикальной. Числа сходятся с раскладкой
              xfdesktop, иначе проверка краснеет и говорит, что именно не сошлось.

``button``  — кнопка внутри окна магазина. Tk даёт каждому виджету собственное
              окно X, поэтому ``xwininfo -tree`` перечисляет строки списка и
              кнопки рядом с ними вместе с их геометрией. Строку выбираем по
              месту программы в каталоге, кнопку в строке — по краю справа.

Выход — строки ``КЛЮЧ=значение`` для оболочки: ``DESK_X``/``DESK_Y`` в точках
стола (пересчёт в точки экрана делает сама проверка), плюс то, из чего точка
выведена. Ненулевой код — когда выводить не из чего.
"""

import argparse
import re
import sys

import numpy as np
from PIL import Image

# Значок отличается от обоев заметно; порог в тонах взят с запасом к шуму сжатия.
CONTRAST = 25
# Полоса считается значком, а не подписью, если она не ниже половины самой высокой.
LABEL_SHARE = 0.5


def die(message):
    print("ОШИБКА=%s" % message)
    sys.exit(3)


# ------------------------------------------------------------------ ярлык на столе


def read_icon_positions(path):
    """Строка и столбец каждого ярлыка из ``icons.screen0.yaml`` xfdesktop."""
    positions = {}
    current = None
    for line in open(path, encoding="utf-8"):
        entry = re.match(r'\s*"(/[^"]*)":\s*$', line)
        if entry:
            current = entry.group(1)
            positions.setdefault(current, {})
            continue
        pair = re.match(r"\s*(row|col):\s*(\d+)\s*$", line)
        if pair and current:
            positions[current][pair.group(1)] = int(pair.group(2))
    return {k: v for k, v in positions.items() if "row" in v and "col" in v}


def bands(profile, threshold):
    """Отрезки, где профиль не ниже порога: подряд идущие непустые столбцы или строки."""
    out = []
    start = None
    for i, value in enumerate(profile):
        if value >= threshold and start is None:
            start = i
        elif value < threshold and start is not None:
            out.append((start, i))
            start = None
    if start is not None:
        out.append((start, len(profile)))
    return out


def icon_point(args):
    positions = read_icon_positions(args.icons)
    if not positions:
        die("в %s нет ни одного ярлыка — xfdesktop не назвал раскладку стола" % args.icons)
    if args.entry not in positions:
        die("ярлыка %s нет в раскладке xfdesktop (%s)" % (args.entry, args.icons))

    want_row = positions[args.entry]["row"]
    want_col = positions[args.entry]["col"]
    # Сколько значков стоит в нужном столбце и каким по счёту сверху идёт наш.
    column = sorted(v["row"] for v in positions.values() if v["col"] == want_col)
    rank = column.index(want_row)

    image = Image.open(args.shot).convert("RGB")
    left, top = int(round(args.offset_x)), int(round(args.offset_y))
    right = int(round(args.offset_x + args.desk_width * args.scale))
    bottom = int(round(args.offset_y + args.desk_height * args.scale))
    desktop = np.asarray(image.crop((left, top, right, bottom)), dtype=np.float64).mean(axis=2)
    # Панель — тоже яркая полоса во всю ширину; её отрезаем, иначе она станет строкой.
    above_panel = desktop[: int(args.panel_top * args.scale), :]
    if above_panel.size == 0:
        die("над панелью не осталось стола — снимок не про наш стол")
    mask = np.abs(above_panel - np.median(above_panel)) > CONTRAST

    # Столбцы значков: вертикальные полосы. Обои дают свои полосы в середине стола,
    # поэтому столбец опознаём не по месту, а по числу значков в нём.
    for x0, x1 in bands(mask.sum(axis=0), 3):
        if (x1 - x0) / args.scale < 20:
            continue
        strip = mask[:, x0:x1]
        rows = bands(strip.sum(axis=1), 2)
        if not rows:
            continue
        tallest = max(r[1] - r[0] for r in rows)
        icons = [r for r in rows if (r[1] - r[0]) >= tallest * LABEL_SHARE]
        if len(icons) != len(column):
            continue
        y0, y1 = icons[rank]
        print("DESK_X=%.1f" % (((x0 + x1) / 2) / args.scale))
        print("DESK_Y=%.1f" % (((y0 + y1) / 2) / args.scale))
        print("COLUMN=%d" % want_col)
        print("ROW=%d" % want_row)
        print("ICONS=%d" % len(icons))
        return 0

    die("на снимке не нашлось столбца ровно с %d значками, а столько их стоит в столбце %d "
        "по раскладке xfdesktop — снимок не про этот стол" % (len(column), want_col))


# ------------------------------------------------------------------ кнопка в окне


NODE = re.compile(
    r"^(\s*)(0x[0-9a-f]+)[^:]*:\s*\([^)]*\)\s+(\d+)x(\d+)\+(-?\d+)\+(-?\d+)\s+\+(-?\d+)\+(-?\d+)"
)


def read_tree(path):
    """Узлы дерева окон с родителем: отступ строки и есть вложенность."""
    nodes = []
    stack = []
    for line in open(path, encoding="utf-8", errors="replace"):
        hit = NODE.match(line.rstrip("\n"))
        if not hit:
            continue
        indent = len(hit.group(1))
        while stack and nodes[stack[-1]]["indent"] >= indent:
            stack.pop()
        nodes.append({
            "id": hit.group(2), "indent": indent,
            "w": int(hit.group(3)), "h": int(hit.group(4)),
            "x": int(hit.group(7)), "y": int(hit.group(8)),
            "parent": stack[-1] if stack else None,
        })
        stack.append(len(nodes) - 1)
    return nodes


def button_point(args):
    nodes = read_tree(args.tree)
    if not nodes:
        die("в дереве окон нет ни одного узла — окно магазина не отдало своё устройство")

    kids = {}
    for i, node in enumerate(nodes):
        kids.setdefault(node["parent"], []).append(i)

    # Список программ — узел с наибольшим числом детей: у остальных частей окна их
    # единицы. Число детей обязано совпасть с числом строк каталога, иначе смотрим
    # не на список, и выбирать строку наугад нельзя.
    holder = max((i for i in kids if i is not None), key=lambda i: len(kids[i]))
    rows = sorted(kids[holder], key=lambda i: nodes[i]["y"])
    if len(rows) != args.rows:
        die("в списке магазина %d строк, а в каталоге %d программ — окно показывает не тот список"
            % (len(rows), args.rows))
    if not 0 <= args.index < len(rows):
        die("программа идёт %d-й в каталоге, а строк в списке %d" % (args.index + 1, len(rows)))

    inside = [nodes[i] for i in kids.get(rows[args.index], [])]
    if not inside:
        die("в строке списка нет ни одной кнопки — магазин нарисовал строку без действия")
    # Кнопка стоит у правого края строки, подписи — слева.
    button = max(inside, key=lambda n: n["x"])

    # Список прокручивается, и строка ниже видимой части окна тапом не берётся:
    # видимая часть — это то окно, внутри которого список ездит.
    viewport = nodes[nodes[holder]["parent"]] if nodes[holder]["parent"] is not None else None
    if viewport is None:
        die("список программ не лежит ни в каком окне — прокрутку проверить нечем")
    center_y = button["y"] + button["h"] / 2
    if not viewport["y"] <= center_y <= viewport["y"] + viewport["h"]:
        die("кнопка строки %d лежит за видимой частью списка (y=%d при окне %d..%d) — "
            "список надо прокрутить, а вслепую тапать нельзя"
            % (args.index + 1, center_y, viewport["y"], viewport["y"] + viewport["h"]))

    print("DESK_X=%.1f" % (button["x"] + button["w"] / 2))
    print("DESK_Y=%.1f" % center_y)
    print("BUTTON=%s" % button["id"])
    print("ROWS=%d" % len(rows))
    return 0


# ------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)

    icon = sub.add_parser("icon", help="точка ярлыка на столе")
    icon.add_argument("--shot", required=True)
    icon.add_argument("--icons", required=True)
    icon.add_argument("--entry", required=True)
    icon.add_argument("--scale", type=float, required=True)
    icon.add_argument("--offset-x", type=float, required=True)
    icon.add_argument("--offset-y", type=float, required=True)
    icon.add_argument("--desk-width", type=int, required=True)
    icon.add_argument("--desk-height", type=int, required=True)
    icon.add_argument("--panel-top", type=int, required=True)

    button = sub.add_parser("button", help="точка кнопки в окне магазина")
    button.add_argument("--tree", required=True)
    button.add_argument("--index", type=int, required=True)
    button.add_argument("--rows", type=int, required=True)

    args = parser.parse_args()
    return icon_point(args) if args.mode == "icon" else button_point(args)


if __name__ == "__main__":
    sys.exit(main())
