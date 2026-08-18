#!/usr/bin/env python3
"""Куда ткнуть на столе: точки для тапа, выведенные из состояния экрана (R05.4i).

Слепых координат в проверке магазина быть не может: вписанное число доказывает
случайность, а не работу ярлыка. Здесь два способа получить точку, и оба читают
то, что на экране прямо сейчас.

``icon``    — ярлык на столе. Позиции значков xfdesktop держит сам: строку и
              столбец каждого ярлыка он пишет в ``icons.screen0.yaml``. Размер
              клетки в этом файле не назван, поэтому шаг сетки меряется по снимку
              телефона: столбцы — по горизонтальной проекции, строки внутри
              столбца — по вертикальной. Сколько всего ярлыков на столе, не важно:
              место считается от верхнего значка столбца шагами сетки, и завтра,
              когда ярлыков станет больше, проверка этого не заметит.

``frame``   — прямоугольник, в который приложение вписало стол на экране телефона:
              берётся из дерева окон Android, а не из врезок системных полос.

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


def grid_step(centers):
    """Шаг сетки значков: расстояние между соседними клетками.

    Считается по самым коротким промежуткам, а не по среднему: в столбце может не
    оказаться значка (ярлык убрали, стол ещё не дорисовал), и такой пропуск дал бы
    двойной шаг. Числа шага здесь нет и быть не может — он меряется по снимку.
    """
    gaps = sorted(b - a for a, b in zip(centers, centers[1:]))
    single = [g for g in gaps if g <= gaps[0] * 1.6]
    return single[len(single) // 2]


def icon_point(args):
    positions = read_icon_positions(args.icons)
    if not positions:
        die("в %s нет ни одного ярлыка — xfdesktop не назвал раскладку стола" % args.icons)
    if args.entry not in positions:
        die("ярлыка %s нет в раскладке xfdesktop (%s)" % (args.entry, args.icons))

    want_row = positions[args.entry]["row"]
    want_col = positions[args.entry]["col"]
    # Верхняя занятая строка столбца: от неё считаются остальные. Сколько всего
    # ярлыков на столе — не важно: завтра их станет больше, а шаг сетки тот же.
    column = sorted(v["row"] for v in positions.values() if v["col"] == want_col)
    top_row = column[0]

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

    # Столбцы значков — вертикальные полосы у левого края: xfdesktop заполняет стол
    # слева. Полосы обоев в середине стола отсекаются по ширине, а нужный столбец
    # берётся по номеру из раскладки самого xfdesktop.
    columns = [b for b in bands(mask.sum(axis=0), 3) if (b[1] - b[0]) / args.scale >= 20]
    if len(columns) <= want_col:
        die("ярлык стоит в %d-м столбце, а на снимке столбцов значков %d — "
            "стол либо ещё не дорисован, либо снимок не про него" % (want_col + 1, len(columns)))
    x0, x1 = columns[want_col]

    # Строки внутри столбца: у каждого ярлыка своя полоса значка и полоса подписи под
    # ним. Подписи ниже — их отсекаем по высоте.
    strip = mask[:, x0:x1]
    found = bands(strip.sum(axis=1), 2)
    if not found:
        die("в %d-м столбце на снимке нет ни одной полосы значка" % (want_col + 1))
    tallest = max(b[1] - b[0] for b in found)
    icons = [b for b in found if (b[1] - b[0]) >= tallest * LABEL_SHARE]
    centers = [(a + b) / 2 for a, b in icons]
    if len(centers) < 2:
        die("в %d-м столбце нашёлся только %d значок — шаг сетки мерить не по чему"
            % (want_col + 1, len(centers)))

    # Место ярлыка = верхний значок столбца плюс столько шагов, сколько строк до него
    # по раскладке xfdesktop. Число значков в столбце в расчёт не входит.
    step = grid_step(centers)
    expected = centers[0] + (want_row - top_row) * step
    nearest = min(centers, key=lambda c: abs(c - expected))
    if abs(nearest - expected) > step / 3:
        die("в строке %d столбца %d значка на снимке нет: ждали его около y=%.0f, ближайший на "
            "y=%.0f при шаге сетки %.0f — стол ещё не дорисовал ярлыки"
            % (want_row, want_col, expected / args.scale, nearest / args.scale, step / args.scale))

    print("DESK_X=%.1f" % (((x0 + x1) / 2) / args.scale))
    print("DESK_Y=%.1f" % (nearest / args.scale))
    print("COLUMN=%d" % want_col)
    print("ROW=%d" % want_row)
    print("ICONS=%d" % len(centers))
    print("STEP=%.1f" % (step / args.scale))
    return 0


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


BOUNDS = re.compile(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')


def frame_point(args):
    """Куда приложение вписало стол: прямоугольник вида, принимающего касания.

    Врезки системных полос из ``dumpsys window`` для этого не годятся: на Android 11
    строки ``InsetsSource`` устроены иначе, чем на 15, читаются нулями, и стол
    «съезжает» на десятки точек. Дерево окон Android отдаёт готовый прямоугольник и
    одинаково на обеих версиях.
    """
    best = None
    text = open(args.dump, encoding="utf-8", errors="replace").read()
    for node in re.findall(r"<node[^>]*/?>", text):
        if 'package="%s"' % args.package not in node:
            continue
        if 'class="android.view.View"' not in node or 'focusable="true"' not in node:
            continue
        box = BOUNDS.search(node)
        if not box:
            continue
        x1, y1, x2, y2 = (int(v) for v in box.groups())
        area = (x2 - x1) * (y2 - y1)
        if area > 0 and (best is None or area > best[0]):
            best = (area, x1, y1, x2, y2)

    if best is None:
        die("в дереве окон нет вида приложения, в который вписан стол — "
            "на экране сейчас не стол")

    _, x1, y1, x2, y2 = best
    view_w, view_h = x2 - x1, y2 - y1
    scale = min(view_w / args.desk_width, view_h / args.desk_height)
    print("SCALE=%.6f" % scale)
    print("OFFSET_X=%.3f" % (x1 + (view_w - args.desk_width * scale) / 2))
    print("OFFSET_Y=%.3f" % (y1 + (view_h - args.desk_height * scale) / 2))
    print("VIEW=%dx%d+%d+%d" % (view_w, view_h, x1, y1))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)

    frame = sub.add_parser("frame", help="прямоугольник, в который вписан стол")
    frame.add_argument("--dump", required=True)
    frame.add_argument("--desk-width", type=int, required=True)
    frame.add_argument("--desk-height", type=int, required=True)
    frame.add_argument("--package", required=True)

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
    return {"icon": icon_point, "button": button_point, "frame": frame_point}[args.mode](args)


if __name__ == "__main__":
    sys.exit(main())
