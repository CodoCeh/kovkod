#!/usr/bin/env python3
"""Собирает замер потолка отрисовки в один артефакт: число и условия рядом.

Число без условий ничего не значит — по нему нельзя ни сравнить два прогона, ни
объяснить разницу. Поэтому в файл кладётся не только медиана кадров, но и всё, при
чём она снята: версия Android, размер стола и окна, отрисовщик и признак
аппаратного ускорения, список окон стола, загрузка машины.

Кадров в выводе нет — код возврата ненулевой: замер не состоялся, и делать вид, что
у нас есть ответ, нельзя.
"""

import argparse
import json
import re
import sys

FPS = re.compile(r"([0-9.]+)\s+FPS")


def read(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def median(values):
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return round((ordered[middle - 1] + ordered[middle]) / 2, 3)


def renderer_of(glxinfo):
    """Отрисовщик и признак ускорения — из ответа самого glxinfo, а не из догадки."""
    device = re.search(r"^\s*Device:\s*(.+?)\s*$", glxinfo, re.M)
    accel = re.search(r"^\s*Accelerated:\s*(\S+)", glxinfo, re.M)
    version = re.search(r"^\s*Version:\s*(\S+)", glxinfo, re.M)
    return (
        device.group(1) if device else "не назвался",
        accel.group(1) if accel else "не назвался",
        version.group(1) if version else "не назвалась",
    )


def candidates(policy):
    """«Есть ли в каталоге» — по ответу apt, а не по памяти пишущего отчёт."""
    found = {}
    package = None
    for line in policy.splitlines():
        name = re.match(r"^(\S+):\s*$", line)
        if name:
            package = name.group(1)
            continue
        value = re.match(r"^\s*Candidate:\s*(.+?)\s*$", line)
        if value and package:
            found[package] = value.group(1)
            package = None
    return found


def main():
    parser = argparse.ArgumentParser()
    for name in ("out", "gears", "glxinfo", "windows", "vulkan", "stack"):
        parser.add_argument("--" + name, required=True)
    for name in ("serial", "avd", "sdk", "release", "window", "seconds",
                 "desktop", "focus", "loadavg", "arch", "foreign"):
        parser.add_argument("--" + name, default="")
    args = parser.parse_args()

    gears = read(args.gears)
    samples = [float(value) for value in FPS.findall(gears)]
    if not samples:
        print("в выводе glxgears нет ни одной строки с кадрами", file=sys.stderr)
        return 1

    glxinfo = read(args.glxinfo)
    device, accelerated, gl_version = renderer_of(glxinfo)
    icds = [line for line in read(args.vulkan).splitlines() if line.strip()]
    windows = [line for line in read(args.windows).splitlines() if line.strip()]

    report = {
        "measured": "glxgears в окне {} на столе КодоЦеха".format(args.window),
        "fps": {
            "median": median(samples),
            "min": min(samples),
            "max": max(samples),
            "samples": samples,
        },
        "conditions": {
            "device": args.serial,
            "avd": args.avd,
            "android": "{} (API {})".format(args.release, args.sdk),
            "window": args.window,
            "desktop": args.desktop,
            "seconds": args.seconds,
            "renderer": device,
            "accelerated": accelerated,
            "gl_version": gl_version,
            "vulkan_icd": icds or "каталог /etc/vulkan/icd.d пуст",
            "windows_on_desktop": windows,
            "loadavg": args.loadavg,
            "foreground": args.focus,
        },
        # Ответ на вопрос «а собрать windows-стек можно?» — из ответов самой системы.
        "windows_stack": dict(
            {"arch": args.arch, "foreign_arch": args.foreign},
            **{"candidate_" + name: value for name, value in candidates(read(args.stack)).items()}
        ),
    }

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
