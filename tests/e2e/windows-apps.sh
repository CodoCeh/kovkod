#!/usr/bin/env bash
#
# tests/e2e/windows-apps.sh — стек windows-программ, доказанный настоящей игрой (R04).
#
# Владелец просил «нативно, по типу PortProton»: со стола, а не из терминала. Поэтому
# здесь ничего не ставится командой — стек ставится ТАПОМ по экрану живого Android:
# тап по ярлыку магазина, тап по кнопке «Поставить» в строке «Windows-программы».
# После установки на столе появляется свой ярлык, и он тоже открывается тапом.
#
# PortProton взять было нельзя: он собран только под x86_64 и требует мультилиба i386,
# которого на arm64 нет. Путь один — Hangover (wine-arm64 + FEX/box64), артефакт
# hangover_11.9_debian13_trixie_arm64 ровно под нашу Debian. Источник назван в
# store/catalog.json вместе с суммой sha256.
#
# Что меряется у каждой windows-программы (R04.3) — четыре числа, а не слова:
#   1. открылось ли окно и за сколько секунд;
#   2. сколько кадров из ста, снятых за десять секунд, отличаются от предыдущего.
#      Кадр снимается запросом GetImage прямо у X-сервера (окно стола 1600×1000,
#      область в середине окна программы), сравниваются суммы md5 — грубо, но
#      измеримо и одинаково для всех;
#   3. дошёл ли ввод: снимок до тапа по окну и после него, тем же сравнением;
#   4. вердикт: годна к игре / запускается, но не играбельна / не запускается.
#
# Потолок отрисовки на этом железе измерен раньше (tests/e2e/render-ceiling.sh):
# 4,4–5,4 кадра в секунду на голом glxgears, аппаратного ускорения нет вовсе. Игры
# берутся под этот потолок, и чуда от них не ждут.
#
# Программы (все скачивает сама проверка, каждая — из названного источника):
#   mines  — Simon Tatham's Puzzles, «Mines», x86-64, страница автора
#            https://www.chiark.greenend.org.uk/~sgtatham/puzzles/ (это ИГРА);
#   doom   — Chocolate Doom 3.1.1 (win64) с игрой Freedoom: Phase 1 0.13.0,
#            релизы GitHub chocolate-doom и freedoom (это ИГРА);
#   putty  — PuTTY 32-bit, официальный сайт https://the.earth.li/~sgtatham/putty/
#            (это НЕ игра: обычная программа, чтобы стек проверялся не только играми
#            и не только на 64 битах — putty идёт через wow64).
#
# ЧТО СНЯТО ЭТИМ ПРОГОНОМ И ГДЕ ОНО ВСТАЛО. Стек ставится и встаёт: тапом со стола,
# за 630 с на эмуляторе Android 15, wine отвечает «wine-11.9 (Hangover)», ярлык
# «Windows-программы» появляется и открывается тапом. Но ни одна windows-программа
# на Android окна не открывает — все три падают в одно и то же:
#
#   err:virtual:map_image_into_view failed to set 60000020 protection on
#   L"\\??\\C:\\windows\\system32\\ntdll.dll" section .text, noexec filesystem?
#
# Причина измерена, а не угадана. В образе под нашим APK:
#   отображение файла RW, страницу НЕ трогали → mprotect(RX)  — получилось;
#   отображение файла RW, в страницу ЗАПИСАЛИ → mprotect(RX)  — ОТКАЗ EACCES;
#   безымянная память RW, записали          → mprotect(RX)  — получилось.
# Это SELinux-право execmod: «сделать исполняемой страницу файла, в которую писали».
# Домену untrusted_app (а это targetSdk 29 и выше; у нас 36) оно запрещено и при этом
# заглушено правилом dontaudit — в logcat ни одной строки. Ровно это wine и делает,
# когда раскладывает PE-образ: пишет правки в секцию и делает её исполняемой.
# Термукс живёт с targetSdk 28 — там домен untrusted_app_25, где execmod разрешён.
#
# Тот же образ, тот же proot и тот же стек в обычной arm64-Linux (виртуалка lima)
# запускают и mines.exe, и Freedoom — значит дело не в стеке и не в сборке образа,
# а в правах приложения на Android. Следующий шаг лежит в app/ и стоит решения
# владельца: targetSdkVersion 28, как у Термукса, либо отказ от wine на Android.
#
# Граница отказа (R04.4, R04.6): хотя бы одна windows-ИГРА обязана открыть окно и
# прожить 15 секунд. Не открылась ни одна — проверка красная, и в выводе список
# проверенного с причинами. «Зато запустился winecfg» не засчитывается: программа
# обязана лежать в /root/Windows и не входить в состав самого wine.
#
# Проверка умеет краснеть (рычаги порчи, STOL_WIN_MUTATION):
#   no-stack   — по кнопке «Поставить» не тапаем: стек не встанет;
#   wine-tool  — вместо игры подсовывается winecfg из состава wine;
#   no-frames  — замер кадров подменяется заглушкой, числа не снимаются.
#
# Использование:
#   STOL_WIN_SERIAL=emulator-5560 bash tests/e2e/windows-apps.sh
#
# Переменные: STOL_WIN_SERIAL (устройство; без него берётся единственное со столом),
# STOL_E2E_ADB, STOL_WIN_ALIVE (15 с), STOL_WIN_STACK_LIMIT (сколько ждать установку
# стека, 4800 с), STOL_WIN_DESKTOP_LIMIT, STOL_WIN_APK (путь к APK для замера веса),
# STOL_WIN_KEEP (не гасить программы после прогона).

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"
CACHE="$ART/windows-cache"
PKG="ru.codoceh.stol"

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ALIVE="${STOL_WIN_ALIVE:-15}"
STACK_LIMIT="${STOL_WIN_STACK_LIMIT:-4800}"
DESKTOP_LIMIT="${STOL_WIN_DESKTOP_LIMIT:-600}"
APK="${STOL_WIN_APK:-$REPO/app/build/outputs/apk/debug/app-debug.apk}"
MUTATION="${STOL_WIN_MUTATION:-}"
APK_CEILING_MB=470

# Вес APK — ключевое число продукта: потолок 500 МБ жёсткий, а стек весит больше двух
# гигабайт. Поэтому стек ставится по требованию, и проверка обязана это подтвердить.

log()  { printf '\n\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { PASS=$((PASS + 1)); printf '    \033[32mОК\033[0m  %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

PASS=0
FAIL=0

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb (${ADB}) — до устройства не дотянуться"
command -v python3 >/dev/null 2>&1 || die "не найден python3 — разбирать замеры нечем"
[ -f "$HERE/store-points.py" ] || die "нет $HERE/store-points.py — координаты тапа выводить нечем"
python3 -c "import PIL, numpy" >/dev/null 2>&1 \
    || die "у python3 нет Pillow или numpy — место ярлыка на снимке вывести нечем"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/windows-apps.XXXXXX")"
mkdir -p "$ART" "$CACHE"
trap 'finish' EXIT

# ------------------------------------------------------------------ устройство

SERIAL="${STOL_WIN_SERIAL:-}"
if [ -z "$SERIAL" ]; then
    declare -a READY=()
    while read -r serial state _; do
        [ "$state" = "device" ] || continue
        case "$("$ADB" -s "$serial" shell run-as "$PKG" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')" in
            *".stol-ready") READY+=("$serial") ;;
        esac
    done < <("$ADB" devices | tail -n +2)
    [ "${#READY[@]}" -eq 1 ] \
        || die "устройство не выбрано: со столом их ${#READY[@]}. Назовите его в STOL_WIN_SERIAL —
чужое устройство трогать нельзя, на нём может идти другой прогон."
    SERIAL="${READY[0]}"
fi
"$ADB" -s "$SERIAL" shell true >/dev/null 2>&1 || die "устройства ${SERIAL} нет среди живых"
AVD="$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
[ -n "$AVD" ] || AVD="$SERIAL"
SDK="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
REL="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
TAG="${SDK}-${AVD}"

# ------------------------------------------------------------------ вход в образ

IMAGE_ENTER='#!/system/bin/sh
NAT="$1"; CMD="$2"
FILES=/data/user/0/ru.codoceh.stol/files
mkdir -p "$FILES/tmp/shm" "$FILES/soname"
ln -sf "$NAT/libtalloc.so" "$FILES/soname/libtalloc.so.2" 2>/dev/null
export LD_LIBRARY_PATH="$FILES/soname:$NAT"
export PROOT_LOADER="$NAT/libproot-loader.so"
export PROOT_TMP_DIR="$FILES/tmp"
exec /system/bin/linker64 "$NAT/libproot.so" --link2symlink --sysvipc -0 -r "$FILES/rootfs" -w /root \
  -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random \
  -b /proc/self/fd:/dev/fd -b /proc/self/fd/0:/dev/stdin -b /proc/self/fd/1:/dev/stdout \
  -b /proc/self/fd/2:/dev/stderr -b "$FILES/tmp/shm:/dev/shm" \
  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  TERM=xterm-256color LANG=C.UTF-8 DISPLAY=:1 \
  DBUS_SESSION_BUS_ADDRESS=unix:path=/run/stol/bus /bin/sh -l "/tmp/$CMD"'

# Файл в каталог данных приложения — через base64: кавычки и переводы строк
# команда через adb не переживает.
put_file() {
    local path="$1" body="$2" b64
    b64="$(printf '%s\n' "$body" | base64 | tr -d '\n')"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'echo $b64 | base64 -d > $path'" >/dev/null 2>&1
}

# Файл побольше строки командной строки: adb склеивает команду в одну строку и на
# паре десятков килобайт молча её обрезает. Такие файлы едут через стандартный ввод.
put_big() {
    local path="$1" source="$2"
    base64 < "$source" | "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'base64 -d > $path'" >/dev/null 2>&1
}

# Команда внутри образа. Едет файлом, а не строкой: adb склеивает аргументы обратно
# в строку и теряет вложенные кавычки.
in_image() {
    put_file "files/rootfs/tmp/win-cmd.sh" "$1"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/store-enter.sh '$NATIVE' win-cmd.sh" 2>&1 | tr -d '\r'
}

# Долгая команда внутри образа: пускается в стороне и не держит проверку.
in_image_bg() {
    put_file "files/rootfs/tmp/win-hold.sh" "$1"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/store-enter.sh '$NATIVE' win-hold.sh" >/dev/null 2>&1 &
    HOLDERS+=("$!")
}

declare -a HOLDERS=()

finish() {
    if [ -z "${STOL_WIN_KEEP:-}" ] && [ -n "${NATIVE:-}" ]; then
        in_image 'pkill -f "codoceh-store.py"
pkill -f "/opt/stol/windows/launcher.py"
pkill -f "\.exe"
exit 0' >/dev/null 2>&1
    fi
    for pid in ${HOLDERS[@]+"${HOLDERS[@]}"}; do kill "$pid" 2>/dev/null; done
    rm -rf "$TMP"
}

beacon_open() {
    "$ADB" -s "$SERIAL" shell 'echo x | nc -w 1 127.0.0.1 5910 >/dev/null 2>&1 && echo OPEN || echo SHUT' \
        2>/dev/null | tr -d '\r' | grep -q OPEN
}

# ------------------------------------------------------------------ замер кадров
#
# Кадры снимаются у самого X-сервера: xwd и ImageMagick в образе нет и класть их
# ради проверки незачем, а «import» из оснастки берёт секунду на кадр — сотни
# кадров за десять секунд им не снять. GetImage по сокету снимает область 240×180
# в середине окна за десяток миллисекунд.
FRAMES_PY='#!/usr/bin/env python3
"""Сколько кадров окна отличаются от предыдущего (R04.3.2).

    frames.py ОКНО КАДРОВ СЕКУНД   — снять КАДРОВ штук за СЕКУНД и посчитать отличия
    frames.py ОКНО hash            — один кадр, одна сумма (ею меряют, дошёл ли ввод)
"""

import hashlib
import os
import socket
import struct
import sys
import time

SIDE_W, SIDE_H = 240, 180


def die(message):
    print("ОШИБКА=%s" % message)
    sys.exit(3)


def cookie(display):
    path = os.path.expanduser("~/.Xauthority")
    if not os.path.exists(path):
        return b"", b""
    blob = open(path, "rb").read()
    pos = 0

    def chunk():
        nonlocal pos
        size, = struct.unpack_from(">H", blob, pos)
        pos += 2
        value = blob[pos:pos + size]
        pos += size
        return value

    while pos < len(blob):
        pos += 2
        chunk()
        number = chunk()
        name = chunk()
        data = chunk()
        if name == b"MIT-MAGIC-COOKIE-1" and number in (display, b""):
            return name, data
    return b"", b""


class Server:
    def __init__(self, display=1):
        path = "/tmp/.X11-unix/X%d" % display
        if not os.path.exists(path):
            die("сокета X-сервера %s нет — стол не поднят" % path)
        self.sock = socket.socket(socket.AF_UNIX)
        self.sock.connect(path)
        name, data = cookie(str(display).encode())
        pad = lambda blob: blob + b"\0" * ((4 - len(blob) % 4) % 4)
        self.sock.sendall(struct.pack("<BBHHHH", 0x6C, 0, 11, 0, len(name), len(data))
                          + b"\0\0" + pad(name) + pad(data))
        head = self.read(8)
        extra, = struct.unpack_from("<H", head, 6)
        body = self.read(extra * 4)
        if head[0] != 1:
            die("X-сервер отказал в подключении")

    def read(self, size):
        blob = b""
        while len(blob) < size:
            piece = self.sock.recv(size - len(blob))
            if not piece:
                die("X-сервер закрыл связь посреди ответа")
            blob += piece
        return blob

    def ask(self, request):
        self.sock.sendall(request)
        answer = self.read(32)
        if answer[0] == 0:
            die("X-сервер вернул ошибку %d" % answer[1])
        extra, = struct.unpack_from("<I", answer, 4)
        return answer + (self.read(extra * 4) if extra else b"")

    def geometry(self, window):
        answer = self.ask(struct.pack("<BBHI", 14, 0, 2, window))
        x, y, width, height = struct.unpack_from("<hhHH", answer, 12)
        return x, y, width, height

    def image(self, window, x, y, width, height):
        answer = self.ask(struct.pack("<BBHIhhHHI", 73, 2, 5, window, x, y, width, height, 0xFFFFFFFF))
        return answer[32:]


def main(argv):
    if len(argv) < 3:
        die("нужно: ОКНО КАДРОВ СЕКУНД либо ОКНО hash")
    window = int(argv[1], 16) if argv[1].startswith("0x") else int(argv[1])
    x11 = Server()
    _, _, width, height = x11.geometry(window)
    side_w, side_h = min(SIDE_W, width), min(SIDE_H, height)
    if side_w < 8 or side_h < 8:
        die("окно %dx%d — снимать нечего" % (width, height))
    left, top = (width - side_w) // 2, (height - side_h) // 2

    if argv[2] == "hash":
        print("ХЕШ=%s" % hashlib.md5(x11.image(window, left, top, side_w, side_h)).hexdigest())
        return 0

    want = int(argv[2])
    seconds = float(argv[3]) if len(argv) > 3 else 10.0
    pace = seconds / want if want else 0.1
    started = time.time()
    previous = None
    taken = differed = 0
    for number in range(want):
        blob = x11.image(window, left, top, side_w, side_h)
        digest = hashlib.md5(blob).hexdigest()
        taken += 1
        if previous is not None and digest != previous:
            differed += 1
        previous = digest
        rest = started + (number + 1) * pace - time.time()
        if rest > 0:
            time.sleep(rest)
    print("КАДРОВ=%d" % taken)
    print("ОТЛИЧИЙ=%d" % differed)
    print("СЕКУНД=%.1f" % (time.time() - started))
    print("ОБЛАСТЬ=%dx%d" % (side_w, side_h))
    print("ОКНО_РАЗМЕР=%dx%d" % (width, height))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
'

# ------------------------------------------------------------------ 0. стол

log "0. Устройство ${AVD} (${SERIAL}), Android ${REL} (API ${SDK})"

APK_PATH="$("$ADB" -s "$SERIAL" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
[ -n "$APK_PATH" ] || die "на устройстве ${SERIAL} нет приложения ${PKG} — ставить стек некуда.
Поставьте APK: adb -s ${SERIAL} install -r -t ${APK}"
NATIVE="$(dirname "$APK_PATH")/lib/arm64"
put_file "files/store-enter.sh" "$IMAGE_ENTER"

if beacon_open; then
    step "стол уже поднят"
else
    step "поднимаем стол начисто"
    "$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
    sleep 5
    "$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    waited=0
    while [ "$waited" -lt "$DESKTOP_LIMIT" ]; do
        sleep 5
        waited=$((waited + 5))
        beacon_open && break
    done
    beacon_open || die "стол не поднялся за ${DESKTOP_LIMIT} с — ставить стек негде"
    step "стол поднялся за ${waited} с"
fi
in_image 'uname -m' | grep -q aarch64 || die "в образ не войти — proot из каталога библиотек APK не запустился"
ok "стол на устройстве живой"

# Магазин внутри образа подменяем на текущий из репозитория: проверяем тот код,
# который лежит здесь, а не тот, что вмёрз в образ при прошлой сборке. Так же
# поступает store/test-store.sh — иначе доказывался бы позапрошлый магазин.
for file in backend.py codoceh-store.py catalog.json; do
    put_big "files/rootfs/opt/stol/store/$file" "$REPO/store/$file" \
        || die "не удалось положить store/$file внутрь образа"
done
in_image 'cd /opt/stol/store && python3 -c "import backend; print(len(backend.load_catalog()))"' > "$TMP/count.txt"
grep -qE '^[0-9]+$' "$TMP/count.txt" || die "магазин в образе не читает свой каталог: $(cat "$TMP/count.txt")"
ok "внутрь образа положен магазин из репозитория, позиций в каталоге: $(cat "$TMP/count.txt")"

printf '%s' "$FRAMES_PY" > "$TMP/frames.py"
put_big "files/rootfs/tmp/frames.py" "$TMP/frames.py"
in_image 'python3 -c "import ast; ast.parse(open(\"/tmp/frames.py\").read())" && echo РАЗБИРАЕТСЯ' \
    | grep -q РАЗБИРАЕТСЯ || die "замер кадров в образ не доехал"

# ------------------------------------------------------------------ 1. до установки

log "1. До установки стека в образе нет (R04.5, R08i)"

# Стек от прошлого прогона сносим, иначе установка была бы мнимой — ровно как
# store/test-store.sh сносит пакет перед тем, как ставить его магазином. Сносит
# проверка, а не магазин: убирать стек магазин пока не умеет, dpkg о нём не знает.
step "сносим стек, если он остался от прошлого прогона"
in_image 'rm -rf /usr/lib/wine /usr/share/wine /root/.wine /opt/stol/windows /tmp/stol-windows
rm -f /usr/bin/wine /usr/bin/wine64 /usr/bin/wineserver /usr/bin/winedbg /usr/bin/msidb
rm -f /usr/bin/wineboot /usr/bin/winecfg /usr/bin/wmc /usr/bin/wrc /usr/bin/widl /usr/bin/winegcc
rm -f /root/Desktop/windows-programs.desktop
exit 0' >/dev/null 2>&1

BEFORE="$(in_image 'ls /usr/bin/wine 2>/dev/null || echo НЕТ-WINE
ls /root/Desktop/windows-programs.desktop 2>/dev/null || echo НЕТ-ЯРЛЫКА')"
case "$BEFORE" in
    *НЕТ-WINE*) ok "wine в образе нет — стек не раздаётся вместе с APK" ;;
    *) die "wine уже лежит в образе — стек попал в раздачу, а этого быть не должно (R04.5).
Снесите его и повторите: стек ставится по требованию." ;;
esac
case "$BEFORE" in
    *НЕТ-ЯРЛЫКА*) ok "ярлыка стека на столе нет — его должна создать установка" ;;
    *) die "ярлык стека уже на столе до установки — доказывать нечего" ;;
esac

APK_BYTES=""
if [ -f "$APK" ]; then
    APK_BYTES="$(stat -f %z "$APK" 2>/dev/null || stat -c %s "$APK" 2>/dev/null)"
    APK_FROM="$APK"
else
    APK_BYTES="$("$ADB" -s "$SERIAL" shell stat -c %s "$APK_PATH" 2>/dev/null | tr -d '\r')"
    APK_FROM="$APK_PATH (с устройства)"
fi
[[ "$APK_BYTES" =~ ^[0-9]+$ ]] || die "вес APK не измерен — файла ${APK} нет и устройство его не назвало"
APK_MB="$(python3 -c "print('%.1f' % ($APK_BYTES / 1000000.0))")"
APK_MIB="$(python3 -c "print('%.1f' % ($APK_BYTES / 1048576.0))")"
if python3 -c "import sys; sys.exit(0 if $APK_BYTES / 1000000.0 <= $APK_CEILING_MB else 1)"; then
    ok "APK весит ${APK_MB} МБ (${APK_MIB} МиБ), потолок этой задачи ${APK_CEILING_MB} МБ — ${APK_FROM}"
else
    bad "APK весит ${APK_MB} МБ — больше потолка ${APK_CEILING_MB} МБ (R04.5)"
fi

# ------------------------------------------------------------------ 2. установка тапом

log "2. Стек ставится тапом со стола (R04.1)"

INDEX_ROWS="$(in_image 'cd /opt/stol/store && python3 -c "
import backend
cat = backend.load_catalog()
for n, i in enumerate(cat):
    if i[\"id\"] == \"windows\":
        print(\"%d %d\" % (n, len(cat)))
        break
"')"
read -r WIN_INDEX WIN_ROWS <<<"$INDEX_ROWS"
[[ "${WIN_INDEX:-}" =~ ^[0-9]+$ ]] || die "в каталоге образа нет позиции «windows» — ставить нечего"
step "позиция стека: строка $((WIN_INDEX + 1)) из ${WIN_ROWS}"

in_image 'pkill -f "codoceh-store.py"' >/dev/null 2>&1
sleep 2

DESK_W="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\1/p"' | head -1)"
DESK_H="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\2/p"' | head -1)"
[[ "$DESK_W" =~ ^[0-9]+$ ]] && [[ "$DESK_H" =~ ^[0-9]+$ ]] \
    || die "стол не назвал свой размер — координаты выводить не из чего"

"$ADB" -s "$SERIAL" shell rm -f /sdcard/stol-frame.xml >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/stol-frame.xml >/dev/null 2>&1
"$ADB" -s "$SERIAL" shell cat /sdcard/stol-frame.xml > "$TMP/frame.xml" 2>/dev/null
[ -s "$TMP/frame.xml" ] || die "дерево окон Android не снялось — куда вписан стол, узнать нечем"
MAP="$(python3 "$HERE/store-points.py" frame --dump "$TMP/frame.xml" \
    --desk-width "$DESK_W" --desk-height "$DESK_H" --package "$PKG" 2>&1)" \
    || die "прямоугольник стола на экране не выведен: $(sed -n 's/^ОШИБКА=//p' <<<"$MAP")"
SCALE="$(sed -n 's/^SCALE=//p' <<<"$MAP")"
OFF_X="$(sed -n 's/^OFFSET_X=//p' <<<"$MAP")"
OFF_Y="$(sed -n 's/^OFFSET_Y=//p' <<<"$MAP")"
PANEL_TOP="$(in_image 'wmctrl -lG -x' | grep -i 'xfce4-panel' | sort -k6 -n -r | head -1 | awk '{print $4}')"
[[ "$PANEL_TOP" =~ ^[0-9]+$ ]] || PANEL_TOP="$DESK_H"
step "стол ${DESK_W}×${DESK_H}, масштаб ${SCALE}, панель с ${PANEL_TOP}"

dev_x() { python3 -c "print(int(round($OFF_X + $1 * $SCALE)))"; }
dev_y() { python3 -c "print(int(round($OFF_Y + $1 * $SCALE)))"; }

# Место ярлыка на столе — из раскладки xfdesktop и снимка экрана, а не из головы.
icon_point() { # $1 — путь к .desktop внутри образа
    local entry="$1" shot="$2" point="" try
    for try in 1 2 3 4 5 6; do
        "$ADB" -s "$SERIAL" exec-out screencap -p > "$shot" 2>/dev/null
        [ -s "$shot" ] || { sleep 3; continue; }
        in_image 'cat /root/.config/xfce4/desktop/icons.screen0.yaml' > "$TMP/icons.yaml"
        [ -s "$TMP/icons.yaml" ] || { sleep 3; continue; }
        point="$(python3 "$HERE/store-points.py" icon \
            --shot "$shot" --icons "$TMP/icons.yaml" --entry "$entry" \
            --scale "$SCALE" --offset-x "$OFF_X" --offset-y "$OFF_Y" \
            --desk-width "$DESK_W" --desk-height "$DESK_H" --panel-top "$PANEL_TOP")" && break
        point=""
        sleep 5
    done
    printf '%s' "$point"
}

# Двойной тап собирается вручную: два «input tap» подряд идут больше секунды, а окно
# двойного щелчка XFCE — 400 мс.
double_tap() {
    "$ADB" -s "$SERIAL" shell "input tap $1 $2 & sleep 0.25; input tap $1 $2; wait" >/dev/null 2>&1
}

window_by_title() {
    local title="$1" found count
    found="$(in_image 'wmctrl -l' | awk -v t="$title" 'index($0, t) { print $1 }')"
    count="$(printf '%s' "$found" | grep -c .)"
    [ "$count" = "1" ] || return 1
    printf '%s' "$found"
}

POINT="$(icon_point /root/Desktop/codoceh-store.desktop "$ART/windows-before-${TAG}.png")"
[ -n "$POINT" ] || die "место ярлыка магазина на столе не выведено ни по одному из шести снимков.
Снимок: $ART/windows-before-${TAG}.png"
ICON_X="$(sed -n 's/^DESK_X=//p' <<<"$POINT")"
ICON_Y="$(sed -n 's/^DESK_Y=//p' <<<"$POINT")"
TAP_X="$(dev_x "$ICON_X")"; TAP_Y="$(dev_y "$ICON_Y")"

step "разогрев: тап по пустому углу стола"
"$ADB" -s "$SERIAL" shell "input tap $(dev_x $((DESK_W - 40))) $(dev_y $((PANEL_TOP - 40)))" >/dev/null 2>&1
sleep 3

step "тапаем дважды по ярлыку магазина в точке экрана (${TAP_X}, ${TAP_Y})"
STORE_WIN=""
for try in 1 2 3 4 5 6 7 8; do
    double_tap "$TAP_X" "$TAP_Y"
    sleep 10
    STORE_WIN="$(window_by_title "Магазин КодоЦех")" && break
    step "с ${try}-го раза магазин не открылся, повторяем"
done
[ -n "$STORE_WIN" ] || die "после восьми двойных тапов окна магазина нет — ярлык магазина с телефона не открывается (R03.2)"
ok "ярлык магазина открылся тапом: окно ${STORE_WIN} (R03.2 — доказано тапом, а не наличием файла)"

in_image "wmctrl -i -r $STORE_WIN -b add,maximized_vert,maximized_horz" >/dev/null 2>&1
sleep 3
in_image "xwininfo -id $STORE_WIN -tree" > "$TMP/tree.txt"
BUTTON="$(python3 "$HERE/store-points.py" button --tree "$TMP/tree.txt" --index "$WIN_INDEX" --rows "$WIN_ROWS")" \
    || die "кнопку «Поставить» в строке стека не найти: $(sed -n 's/^ОШИБКА=//p' <<<"$BUTTON")"
BTN_X="$(dev_x "$(sed -n 's/^DESK_X=//p' <<<"$BUTTON")")"
BTN_Y="$(dev_y "$(sed -n 's/^DESK_Y=//p' <<<"$BUTTON")")"

if [ "$MUTATION" = "no-stack" ]; then
    step "рычаг no-stack: по кнопке «Поставить» не тапаем — проверка обязана покраснеть"
else
    step "тапаем по «Поставить» в точке экрана (${BTN_X}, ${BTN_Y})"
    "$ADB" -s "$SERIAL" shell "input tap $BTN_X $BTN_Y" >/dev/null 2>&1
fi

step "ждём установку стека: спрашиваем у образа каждые 30 с (потолок ${STACK_LIMIT} с)"
waited=0
STACK=""
while [ "$waited" -lt "$STACK_LIMIT" ]; do
    sleep 30
    waited=$((waited + 30))
    if in_image 'ls /usr/bin/wine >/dev/null 2>&1 && ls /root/Desktop/windows-programs.desktop >/dev/null 2>&1 && echo ЕСТЬ' \
        | grep -q ЕСТЬ; then
        STACK="да"
        break
    fi
    [ $((waited % 120)) -eq 0 ] && step "идёт ${waited} с: $(in_image 'tail -1 /tmp/codoceh-store.log 2>/dev/null; tail -1 /tmp/stol-windows-progress.txt 2>/dev/null' | tail -1)"
done
if [ -z "$STACK" ]; then
    printf '\n\033[31mКРАСНО\033[0m  стек не встал за %s с — задача провалена (R04.6).\n' "$waited" >&2
    printf 'Что говорит магазин:\n%s\n' "$(in_image 'tail -20 /tmp/codoceh-store.log 2>/dev/null')" >&2
    exit 1
fi
ok "стек встал за ${waited} с — тапом со стола, ни одной команды в терминале (R04.1)"
step "версия wine: $(in_image 'wine --version 2>/dev/null' | tail -1)"

# ------------------------------------------------------------------ 3. точка входа

log "3. Точка входа на столе (R04.1a)"

# Окно магазина закрываем: развёрнутое на весь стол, оно закрывает собой значки, и
# ярлык стека искался бы на снимке окна магазина, а не стола.
in_image "wmctrl -i -c $STORE_WIN
pkill -f \"/opt/stol/windows/launcher.py\"
exit 0" >/dev/null 2>&1
sleep 5
in_image 'wmctrl -l' | grep -q "Магазин КодоЦех" \
    && step "окно магазина не закрылось — ярлык стека может не найтись на снимке"
POINT="$(icon_point /root/Desktop/windows-programs.desktop "$ART/windows-icon-${TAG}.png")"
[ -n "$POINT" ] || die "ярлык стека на столе не нашёлся на снимке — точки входа нет (R04.1a).
Снимок: $ART/windows-icon-${TAG}.png"
ICON_X="$(sed -n 's/^DESK_X=//p' <<<"$POINT")"
ICON_Y="$(sed -n 's/^DESK_Y=//p' <<<"$POINT")"
TAP_X="$(dev_x "$ICON_X")"; TAP_Y="$(dev_y "$ICON_Y")"
step "ярлык «Windows-программы»: точка стола (${ICON_X}, ${ICON_Y}) → точка экрана (${TAP_X}, ${TAP_Y})"

LAUNCHER_WIN=""
for try in 1 2 3 4 5 6; do
    double_tap "$TAP_X" "$TAP_Y"
    sleep 8
    LAUNCHER_WIN="$(window_by_title "Windows-программы")" && break
    step "с ${try}-го раза окно стека не открылось, повторяем"
done
if [ -n "$LAUNCHER_WIN" ]; then
    ok "ярлык стека открылся тапом: окно ${LAUNCHER_WIN} (R04.1a)"
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$ART/windows-launcher-${TAG}.png" 2>/dev/null
else
    bad "ярлык стека тапом не открылся — точки входа на столе нет (R04.1a)"
fi

# ------------------------------------------------------------------ 4. программы

log "4. Программы Windows: скачивание из названных источников"

# id | вид | источник | что запускать | доводы
PROGRAMS=(
"mines|игра|https://www.chiark.greenend.org.uk/~sgtatham/puzzles/mines.exe|mines.exe|Simon Tatham's Puzzles, «Mines» — страница автора зовёт это игрой"
"doom|игра|https://github.com/chocolate-doom/chocolate-doom/releases/download/chocolate-doom-3.1.1/chocolate-doom-3.1.1-win64.zip https://github.com/freedoom/freedoom/releases/download/v0.13.0/freedoom-0.13.0.zip|chocolate-doom.exe -iwad /root/Windows/doom/freedoom-0.13.0/freedoom1.wad -nosound|Freedoom: Phase 1 — свободная игра, движок Chocolate Doom"
"putty|программа|https://the.earth.li/~sgtatham/putty/latest/w32/putty.exe|putty.exe|PuTTY 32-bit — не игра: проверяем, что стек тянет и обычную программу, и 32 бита"
)

fetch_body() {
    # Скачивание идёт ВНУТРИ образа: пропихнуть 34 МБ через run-as нечем, а сеть у
    # стола та же. Архивы распаковываются питоном — unzip в образе нет.
    local id="$1" urls="$2"
    printf '#!/bin/bash\nset -e\nmkdir -p /root/Windows/%s\ncd /root/Windows/%s\n' "$id" "$id"
    for url in $urls; do
        printf 'python3 - <<PY\nimport urllib.request, os, zipfile, hashlib\nurl = "%s"\nname = url.rsplit("/", 1)[-1]\nif not os.path.exists(name):\n    urllib.request.urlretrieve(url, name)\nblob = open(name, "rb").read()\nprint("ФАЙЛ=%%s РАЗМЕР=%%d SHA256=%%s" %% (name, len(blob), hashlib.sha256(blob).hexdigest()))\nif name.endswith(".zip"):\n    zipfile.ZipFile(name).extractall(".")\n    os.remove(name)\nPY\n' "$url"
    done
    printf 'find /root/Windows/%s -name "*.exe" -o -name "*.wad" | head -20\n' "$id"
}

for row in "${PROGRAMS[@]}"; do
    IFS='|' read -r id kind urls run why <<<"$row"
    step "${id}: качаем из ${urls%% *}"
    out="$(in_image "$(fetch_body "$id" "$urls")")"
    printf '%s\n' "$out" | sed -n 's/^ФАЙЛ=/        /p'
    printf '%s\n' "$out" > "$ART/windows-fetch-${id}.txt"
    if printf '%s' "$out" | grep -q "$(printf '%s' "$run" | awk '{print $1}')"; then
        ok "${id}: программа на устройстве (${why})"
    else
        bad "${id}: не скачалась или не распаковалась — смотрите $ART/windows-fetch-${id}.txt"
    fi
done

# ------------------------------------------------------------------ 5. замеры

log "5. Что реально шевелится: числа по каждой программе (R04.2, R04.3)"

REPORT="$ART/windows-apps-${TAG}.txt"
: > "$REPORT"
GAMES_WITH_WINDOW=0
GAMES_TOTAL=0
CHECKED=0

measure() { # id вид exe-строка доводы
    local id="$1" kind="$2" run="$3" why="$4"
    local exe="/root/Windows/$id/${run%% *}"
    local args="${run#* }"
    [ "$args" = "$run" ] && args=""

    printf '\n  \033[1m%s\033[0m (%s)\n' "$id" "$kind"
    CHECKED=$((CHECKED + 1))
    [ "$kind" = "игра" ] && GAMES_TOTAL=$((GAMES_TOTAL + 1))

    # Рычаг wine-tool: подсовываем утилиту из состава самого wine. Правило
    # происхождения обязано её отвергнуть — «зато запустился winecfg» не в счёт.
    if [ "$MUTATION" = "wine-tool" ] && [ "$kind" = "игра" ]; then
        exe="/usr/lib/wine/aarch64-windows/winecfg.exe"
        args=""
        step "рычаг wine-tool: вместо игры подсунут ${exe}"
    fi

    # Происхождение: программа обязана лежать там, куда её принесли, и не быть
    # частью стека. Иначе доказывалась бы работа wine своими же утилитами.
    local origin
    origin="$(in_image "if [ -f '$exe' ]; then echo ЕСТЬ; else echo НЕТ; fi
case '$exe' in /root/Windows/*) echo ПРИНЕСЕНА;; *) echo ИЗ-СТЕКА;; esac
python3 -c \"import hashlib,sys
try:
    print('SHA=' + hashlib.sha256(open('$exe','rb').read()).hexdigest()[:16])
except OSError as err:
    print('SHA=нет')\"")"
    printf '%s' "$origin" | grep -q ЕСТЬ || { bad "${id}: файла ${exe} на устройстве нет"; return; }
    if ! printf '%s' "$origin" | grep -q ПРИНЕСЕНА; then
        bad "${id}: ${exe} входит в состав самого wine — такой запуск требование не закрывает (R04.2)"
        printf '%s\t%s\tотвергнута: часть самого wine\n' "$id" "$kind" >> "$REPORT"
        return
    fi
    step "происхождение: ${exe}, $(sed -n 's/^SHA=/sha256 /p' <<<"$origin"), источник — ${why}"

    # Запуск в стороне: держатель живёт своей жизнью, проверка тем временем смотрит.
    in_image 'rm -f /tmp/win-run.pid /tmp/win-run.log; exit 0' >/dev/null 2>&1
    in_image_bg "cd /root/Windows/$id
export WINEPREFIX=/root/.wine WINEDEBUG=-all
wine '$exe' $args > /tmp/win-run.log 2>&1 &
echo \$! > /tmp/win-run.pid
sleep 400"

    local started window="" pid="" waited=0 seconds=""
    started="$(date +%s)"
    while [ "$waited" -lt 180 ]; do
        sleep 5
        waited=$((waited + 5))
        pid="$(in_image 'cat /tmp/win-run.pid 2>/dev/null' | tr -dc '0-9')"
        [ -n "$pid" ] || continue
        # Окно ищем по классу: заголовок у windows-программ свой, а класс wine
        # ставит по имени файла программы.
        window="$(in_image 'wmctrl -lx' | awk -v c="${run%%.exe*}.exe" 'index($3, c) { print $1 }' | head -1)"
        [ -n "$window" ] && break
    done
    seconds=$(( $(date +%s) - started ))

    if [ -z "$window" ]; then
        bad "${id}: окна нет за ${waited} с — вердикт «не запускается»"
        printf '%s\t%s\tокно: нет за %d с\tкадры: —\tввод: —\tвердикт: не запускается\n' \
            "$id" "$kind" "$waited" >> "$REPORT"
        step "что сказала программа: $(in_image 'tail -3 /tmp/win-run.log 2>/dev/null' | tr '\n' ' ')"
        in_image 'pkill -f "\.exe"; exit 0' >/dev/null 2>&1
        return
    fi
    ok "${id}: окно ${window} открылось за ${seconds} с"

    in_image "wmctrl -i -a $window" >/dev/null 2>&1
    sleep 2

    local frames diff shots
    if [ "$MUTATION" = "no-frames" ]; then
        step "рычаг no-frames: замер подменён заглушкой"
        frames="КАДРОВ=0
ОТЛИЧИЙ=0
СЕКУНД=0.0"
    else
        frames="$(in_image "python3 /tmp/frames.py $window 100 10")"
    fi
    shots="$(sed -n 's/^КАДРОВ=//p' <<<"$frames")"
    diff="$(sed -n 's/^ОТЛИЧИЙ=//p' <<<"$frames")"
    local took="$(sed -n 's/^СЕКУНД=//p' <<<"$frames")"
    local area="$(sed -n 's/^ОБЛАСТЬ=//p' <<<"$frames")"
    if [ "${shots:-0}" -lt 100 ]; then
        bad "${id}: замер кадров не состоялся (снято ${shots:-0} из 100): $(printf '%s' "$frames" | tr '\n' ' ')"
        printf '%s\t%s\tокно: %d с\tкадры: замер не состоялся\tввод: —\tвердикт: замер не состоялся\n' \
            "$id" "$kind" "$seconds" >> "$REPORT"
        in_image 'pkill -f "\.exe"; exit 0' >/dev/null 2>&1
        return
    fi
    ok "${id}: из ${shots} кадров за ${took} с отличаются от предыдущего ${diff} (область ${area}, GetImage у X-сервера)"

    # Ввод: снимок до тапа по середине окна и после него.
    local geom before after input="нет"
    geom="$(in_image 'wmctrl -lG' | awk -v w="$window" '$1 == w { print $3, $4, $5, $6 }')"
    read -r gx gy gw gh <<<"$geom"
    if [[ "${gx:-}" =~ ^[0-9-]+$ ]]; then
        before="$(in_image "python3 /tmp/frames.py $window hash" | sed -n 's/^ХЕШ=//p')"
        local cx=$((gx + gw / 2)) cy=$((gy + gh / 2))
        "$ADB" -s "$SERIAL" shell "input tap $(dev_x $cx) $(dev_y $cy)" >/dev/null 2>&1
        sleep 1
        "$ADB" -s "$SERIAL" shell "input keyevent 62" >/dev/null 2>&1
        sleep 2
        after="$(in_image "python3 /tmp/frames.py $window hash" | sed -n 's/^ХЕШ=//p')"
        [ -n "$before" ] && [ -n "$after" ] && [ "$before" != "$after" ] && input="да"
        local said="не изменилась"
        [ "$input" = "да" ] && said="изменилась"
        step "${id}: ввод — тап в точку стола (${cx}, ${cy}) и клавиша; картинка ${said}"
    else
        step "${id}: геометрия окна не прочиталась — ввод не проверить"
    fi

    sleep "$ALIVE"
    local alive
    alive="$(in_image 'wmctrl -l' | awk -v w="$window" '$1 == w { print $1 }')"
    local verdict
    if [ -z "$alive" ]; then
        bad "${id}: окно не прожило ${ALIVE} с"
        verdict="не запускается: окно пропало за ${ALIVE} с"
    else
        ok "${id}: окно живо через ${ALIVE} с"
        [ "$kind" = "игра" ] && GAMES_WITH_WINDOW=$((GAMES_WITH_WINDOW + 1))
        if [ "$diff" -ge 30 ]; then
            verdict="годна к игре: картинка обновляется сама"
        elif [ "$input" = "да" ]; then
            verdict="запускается, ввод доходит, картинка сама не движется"
        else
            verdict="запускается, но не играбельна: картинка не меняется"
        fi
    fi
    printf '%s\t%s\tокно: %d с\tкадров %s, отличий %s за %s с\tввод: %s\tвердикт: %s\n' \
        "$id" "$kind" "$seconds" "$shots" "$diff" "$took" "$input" "$verdict" >> "$REPORT"
    step "${id}: вердикт — ${verdict}"

    "$ADB" -s "$SERIAL" exec-out screencap -p > "$ART/windows-${id}-${TAG}.png" 2>/dev/null
    [ -s "$ART/windows-${id}-${TAG}.png" ] && step "снимок экрана: $ART/windows-${id}-${TAG}.png"
    in_image 'pkill -f "\.exe"; exit 0' >/dev/null 2>&1
    sleep 3
}

for row in "${PROGRAMS[@]}"; do
    IFS='|' read -r id kind urls run why <<<"$row"
    measure "$id" "$kind" "$run" "$why"
done

# ------------------------------------------------------------------ итог

log "Итог"
printf '    проверено программ: %d, из них игр: %d, игр с живым окном: %d\n' \
    "$CHECKED" "$GAMES_TOTAL" "$GAMES_WITH_WINDOW"
sed 's/^/    /' "$REPORT"
printf '    таблица: %s\n' "$REPORT"

[ "$CHECKED" -ge 3 ] || bad "проверено меньше трёх windows-программ (R04.2ai)"
[ "$GAMES_TOTAL" -ge 1 ] || bad "в списке нет ни одной игры — пустой список игр не засчитывается (R04.2ai)"
[ "$GAMES_WITH_WINDOW" -ge 1 ] \
    || bad "ни одна windows-игра не открыла окно — задача проваливается, и это результат, а не сбой прогона (R04.4)"

printf '    пройдено: %d, провалено: %d\n' "$PASS" "$FAIL"
if [ "$FAIL" -eq 0 ]; then
    printf '\n\033[32mЗЕЛЁНО\033[0m  стек windows-программ поставлен тапом со стола, игра открыла окно и прожила %s с (R04)\n' "$ALIVE"
    exit 0
fi
printf '\n\033[31mКРАСНО\033[0m  провалов: %d — смотрите строки НЕТ выше\n' "$FAIL"
exit 1
