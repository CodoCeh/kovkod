#!/usr/bin/env python3
"""Что сейчас на экране устройства: разбирает снимок и дерево окон (R01.1).

Вход — файл `uiautomator dump` и снимок экрана, снятые с одного и того же
устройства. Выход — строки `КЛЮЧ=значение` для оболочки и вердикт.

Экраны приложения различаются надёжно и без распознавания текста:

* мастер и экраны отказа — это обычные вью Android, у них есть текстовые узлы;
  как только на экране стол, текстовых узлов у нашего пакета не остаётся;
* нарисованный стол — это обои с ярлыками: тысячи разных цветов и ни одного,
  который занимает почти весь экран. Чёрный экран и заливка фоном приложения
  проваливают ровно эту проверку.

Строка состояния Android и полоса жестов к столу отношения не имеют — их
отрезаем, иначе часы и значки батареи дают «цвета» на пустом экране.
"""

import sys
import xml.etree.ElementTree as ET
from collections import Counter

from PIL import Image

# Полоса сверху — часы и значки, полоса снизу — жест «домой».
CROP_TOP = 0.08
CROP_BOTTOM = 0.96

# Ниже этих значений картинка — не стол.
MIN_DISTINCT_COLORS = 1000
MAX_TOP_COLOR_SHARE = 0.90


def texts(dump_path, package):
    """Тексты вью: пока они есть, на экране мастер/сборка или системный диалог."""
    tree = ET.parse(dump_path)
    found = []
    for node in tree.iter("node"):
        pkg = node.get("package") or ""
        text = (node.get("text") or node.get("content-desc") or "").strip()
        if not text:
            continue
        if pkg == package:
            found.append(text)
        elif pkg in ("android", "com.google.android.packageinstaller", "com.android.systemui"):
            if text in ("Battery", "Wi-Fi", "Cellular", "Clock") or ":" in text and len(text) <= 5:
                continue
            found.append(f"[{pkg}] {text}")
    return found


def picture(png_path):
    image = Image.open(png_path).convert("RGB")
    width, height = image.size
    image = image.crop((0, int(height * CROP_TOP), width, int(height * CROP_BOTTOM)))
    pixels = list(image.get_flattened_data())
    counts = Counter(pixels)
    return len(counts), counts.most_common(1)[0][1] / len(pixels)


def main():
    if len(sys.argv) != 4:
        print("использование: screen-state.py <дамп-окон.xml> <снимок.png> <пакет>",
              file=sys.stderr)
        return 2

    dump_path, png_path, package = sys.argv[1:4]

    try:
        visible = texts(dump_path, package)
    except ET.ParseError as broken:
        print("ERROR=дамп окон не разбирается: %s" % broken)
        return 3

    distinct, top_share = picture(png_path)

    print("TEXT=%s" % " | ".join(visible))
    print("DISTINCT=%d" % distinct)
    print("TOPSHARE=%.4f" % top_share)

    if visible:
        print("VERDICT=WAITING")
    elif distinct < MIN_DISTINCT_COLORS or top_share > MAX_TOP_COLOR_SHARE:
        print("VERDICT=BLANK")
    else:
        print("VERDICT=DRAWN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
