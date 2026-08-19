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

              Значки отделяются от обоев вычитанием самих обоев, а не контрастом
              (см. ``backdrop``).

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

# Насколько точка снимка обязана отличаться от того же места нарисованных обоев,
# чтобы считаться чужой. Мера снята по самому снимку: там, где значков нет вовсе,
# разница не превышает 24 тонов даже у одной точки из десяти тысяч (пересчёт обоев
# в размер стола идёт другим кодом, чем в GdkPixbuf, — отсюда кромка в несколько
# тонов на границах QR-кодов). Значки при этом отличаются на 200 с лишним.
FOREIGN = 40
# Обои обязаны совпасть: половина точек стола отличается от нарисованных обоев не
# больше чем на столько тонов. Не совпали — вычитать нечего, и точка ярлыка была бы
# выведена из шума. На живом снимке эта величина 0,7 тона.
BACKDROP_TOLERANCE = 16
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


def backdrop(path, width, height):
    """Стол без единого значка: обои, положенные так же, как их кладёт xfdesktop.

    Отделять значки по контрасту с обоями нельзя, и это снято живьём. Обои у нас
    брендовые: два белых QR-кода во всю ширину, надписи, наковальня. На столе в
    размер экрана телефона (1080×2274) они дают контраст ВЕЗДЕ, и по горизонтальной
    проекции весь стол опознавался одним столбцом значков. Местная мера контраста
    (окно 25×25) отказ не снимает по той же причине: у QR-кода контраст и местный.

    Но обои — НАШ файл: ``reference/wallpaper.png`` лежит в образе байт в байт
    (``tools/mkimage.sh`` сверяет его по ``reference/wallpaper.sha256``), а способ
    укладки назван дампом настроек: ``image-style 5`` в
    ``reference/xfconf/xfce4-desktop.txt`` — «увеличенные», то есть картинка
    растягивается с сохранением сторон до перекрытия стола и обрезается по центру.
    Значит стол без значков мы умеем нарисовать сами, и всё, чем снимок от него
    отличается, — это и есть нарисованное поверх обоев: значки, окна, указатель.
    Обои при этом уходят целиком, сколько бы их ни растянуло.
    """
    image = Image.open(path).convert("RGB")
    zoom = max(width / image.width, height / image.height)
    big = image.resize(
        (max(width, int(round(image.width * zoom))), max(height, int(round(image.height * zoom)))),
        Image.BILINEAR)
    left = (big.width - width) // 2
    top = (big.height - height) // 2
    return big.crop((left, top, left + width, top + height))


def match_tone(clean, shot):
    """Подогнать тона нарисованных обоев под тона снимка: ``a × обои + b``.

    Один и тот же стол на одном и том же устройстве снимается то темнее, то светлее:
    два снимка подряд разошлись на 29 тонов в тенях и на 15 в светах — картинку
    где-то по дороге подтягивают, и вычитать обои «как есть» после этого нельзя.
    Подгонка идёт по САМИМ обоям: значки занимают несколько процентов стола, их
    отбрасывает повторное взвешивание — на каждом заходе точки, ушедшие дальше трёх
    медиан остатка, из счёта выбывают. Остаётся то, что и должно совпасть.
    """
    x, y = clean.ravel(), shot.ravel()
    keep = np.ones(x.size, dtype=bool)
    line = (1.0, 0.0)
    for _ in range(4):
        line = np.polyfit(x[keep], y[keep], 1)
        far = np.abs(y - np.polyval(line, x))
        keep = far <= max(8.0, 3.0 * np.median(far))
    return np.polyval(line, clean)


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
    # Обои рисуем сами и в том же размере, в каком стол показан на экране: снимок
    # снят с экрана, а не со стола, и при масштабе, отличном от единицы, точки стола
    # на нём не свои.
    clean = backdrop(args.wallpaper, args.desk_width, args.desk_height)
    if clean.size != (right - left, bottom - top):
        clean = clean.resize((right - left, bottom - top), Image.BILINEAR)
    clean = np.asarray(clean, dtype=np.float64).mean(axis=2)

    # Панель — тоже яркая полоса во всю ширину; её отрезаем, иначе она станет строкой.
    cut = int(args.panel_top * args.scale)
    above_panel = desktop[:cut, :]
    if above_panel.size == 0:
        die("над панелью не осталось стола — снимок не про наш стол")

    difference = np.abs(above_panel - match_tone(clean[:cut, :], above_panel))
    # Прежде чем что-то мерить по разнице, проверяем, что вычли действительно обои
    # этого стола. Иначе разница — шум, а точка ярлыка была бы выведена из него.
    missed = float(np.median(difference))
    if missed > BACKDROP_TOLERANCE:
        die("снимок не совпал с обоями стола: половина точек разошлась на %.1f тона при "
            "допуске %d — либо на столе открыто окно, либо обои в образе не те, что в "
            "reference/wallpaper.png, либо стол вписан в вид не так, как посчитано"
            % (missed, BACKDROP_TOLERANCE))
    mask = difference > FOREIGN

    # Сначала строки, и только потом столбцы. Подпись под значком шире самого значка
    # и у соседних клеток почти смыкается: по всей высоте стола между столбцами
    # просвета нет, и все столбцы слипаются в один. А в строках, где лежат САМИ
    # значки, между клетками чистые обои — там столбцы расходятся начисто.
    # Строки значков от строк подписей отделяются по высоте: подпись ниже.
    rows = [b for b in bands(mask.sum(axis=1), 2) if b[1] > b[0]]
    if not rows:
        die("на столе не нашлось ни одной полосы значков — стол ещё не дорисован")
    tallest_row = max(b[1] - b[0] for b in rows)
    icon_rows = [b for b in rows if (b[1] - b[0]) >= tallest_row * LABEL_SHARE]
    only_icons = np.zeros(mask.shape[0], dtype=bool)
    for a, b in icon_rows:
        only_icons[a:b] = True

    # Столбцы значков — вертикальные полосы у левого края: xfdesktop заполняет стол
    # слева. Указатель мыши и прочая мелочь отсекаются по ширине, а нужный столбец
    # берётся по номеру из раскладки самого xfdesktop.
    columns = [b for b in bands(mask[only_icons, :].sum(axis=0), 3)
               if (b[1] - b[0]) / args.scale >= 20]
    if len(columns) <= want_col:
        die("ярлык стоит в %d-м столбце, а на снимке столбцов значков %d — "
            "стол либо ещё не дорисован, либо снимок не про него" % (want_col + 1, len(columns)))
    x0, x1 = columns[want_col]

    # Строки внутри столбца: у каждого ярлыка своя полоса значка и полоса подписи под
    # ним. Подписи ниже — их отсекаем по высоте: чистая подпись ниже 35 точек.
    # Там, где подпись легла на белый QR-код обоев, xfdesktop подкладывает под текст
    # тёмную плашку, и полоса значка смыкается с подписью (высота > 100).
    # В таких смыкающихся полосах значок лежит в верхней части (первые 80-96 точек),
    # а порог шума берётся пропорционально ширине столбца (отсекая одиночные пиксели теней).
    strip = mask[:, x0:x1]
    col_thresh = max(6, int((x1 - x0) * 0.06))
    found = bands(strip.sum(axis=1), col_thresh)
    if not found:
        die("в %d-м столбце на снимке нет ни одной полосы значка" % (want_col + 1))

    # Значки (чистые или смыкающиеся с плашкой) имеют высоту от 40 точек.
    icons = [b for b in found if (b[1] - b[0]) >= 40]
    if len(icons) < 2:
        heights = sorted(b[1] - b[0] for b in found)
        tallest = max(heights)
        icons = [b for b in found if (b[1] - b[0]) >= tallest * LABEL_SHARE]

    centers = []
    for a, b in icons:
        h = b - a
        if h >= 110:
            centers.append(a + 40)
        else:
            centers.append((a + b) / 2)

    # Объединяем куски одного и того же слота значка (подпись и значок), которые
    # распались на две близкие полосы: строки сетки отстоят друг от друга на 150-250 точек.
    min_row_gap = 120 * args.scale
    merged = []
    for c in sorted(centers):
        if not merged or c - merged[-1] >= min_row_gap:
            merged.append(c)
    centers = merged

    if len(centers) < 2:
        die("в %d-м столбце нашёлся только %d значок — шаг сетки мерить не по чему"
            % (want_col + 1, len(centers)))

    # Место ярлыка = верхний значок столбца плюс столько шагов, сколько строк до него
    # по раскладке xfdesktop. Число значков в столбце в расчёт не входит.
    step = grid_step(centers)
    expected = centers[0] + (want_row - top_row) * step
    nearest = min(centers, key=lambda c: abs(c - expected))
    target_y = nearest if abs(nearest - expected) <= step / 3 else expected

    print("DESK_X=%.1f" % (((x0 + x1) / 2) / args.scale))
    print("DESK_Y=%.1f" % (target_y / args.scale))
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
    icon.add_argument("--wallpaper", required=True,
                      help="обои стола (reference/wallpaper.png) — из них рисуется стол "
                           "без значков, и разница с ним и есть значки")

    button = sub.add_parser("button", help="точка кнопки в окне магазина")
    button.add_argument("--tree", required=True)
    button.add_argument("--index", type=int, required=True)
    button.add_argument("--rows", type=int, required=True)

    args = parser.parse_args()
    return {"icon": icon_point, "button": button_point, "frame": frame_point}[args.mode](args)


if __name__ == "__main__":
    sys.exit(main())
