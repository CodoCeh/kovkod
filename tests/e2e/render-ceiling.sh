#!/usr/bin/env bash
#
# tests/e2e/render-ceiling.sh — потолок отрисовки нашего стола, снятый числом (R09i.1).
#
# Владелец просил windows-игры через PortProton. Стек на этой системе невозможен:
# PortProton только x86_64 и тянет мультилиб i386, которого на arm64 нет, а
# аппаратной отрисовки в образе нет вовсе — каталог /etc/vulkan/icd.d пуст. Спорить
# об этом словами бессмысленно, поэтому вместо реализации снимается число: сколько
# кадров в секунду выдаёт на нашем столе самый простой примитив в окне 1280x720.
#
# Меряет glxgears из mesa-utils. Пакет ставится в образ теми же командами, какими
# магазин ставит любую другую программу (store/backend.py), — иначе замер шёл бы по
# пути, которого у человека нет.
#
# ЧТО ЗДЕСЬ КРАСНОЕ. Красное — это «замер не состоялся»: нет устройства, стол не
# поднялся, пакет не встал, glxgears не запустился, кадров никто не насчитал. Само
# число низким быть ИМЕЕТ ПРАВО: проверка меряет потолок, а не сторожит его. Порога
# на кадры здесь нет и быть не должно — вывод из числа делает отчёт, а не проверка.
#
# Число без условий — не число. Рядом с ним в артефакт кладутся версия Android,
# размер стола и окна, отрисовщик (llvmpipe и признак ускорения), список окон стола
# и загрузка машины на момент замера.
#
# Запускать при уже установленном APK и развёрнутом образе:
#   bash tests/e2e/render-ceiling.sh
#
# Переменные: STOL_E2E_ADB, STOL_RENDER_GEOMETRY (1280x720), STOL_RENDER_SECONDS (30),
# STOL_RENDER_LIMIT (сколько ждать стола, 600 с).

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"
PKG="ru.codoceh.stol"

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
GEOMETRY="${STOL_RENDER_GEOMETRY:-1280x720}"
SECONDS_RUN="${STOL_RENDER_SECONDS:-30}"
DESKTOP_LIMIT="${STOL_RENDER_LIMIT:-600}"
PACKAGE_TO_INSTALL="${STOL_RENDER_PACKAGE:-mesa-utils}"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------------ инструменты

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb ($ADB).
Поставьте platform-tools из Android SDK или укажите путь в STOL_E2E_ADB."
command -v python3 >/dev/null 2>&1 || die "не найден python3 — команду установки и артефакт собирать нечем."
[ -f "$REPO/store/backend.py" ] || die "нет store/backend.py — ставить пакет магазинными командами нечем."

mkdir -p "$ART"

# Команда установки берётся у самого магазина, а не пишется здесь заново: иначе
# замер прошёл бы по пути, которого у человека на телефоне нет.
INSTALL_BODY="$(python3 - "$REPO" "$PACKAGE_TO_INSTALL" <<'PY'
import sys
sys.path.insert(0, sys.argv[1] + "/store")
import backend
sys.stdout.write(backend.install_command(sys.argv[2]))
PY
)" || die "магазин не отдал команду установки — сломан store/backend.py"
[ -n "$INSTALL_BODY" ] || die "магазин отдал пустую команду установки"

# ------------------------------------------------------------------ устройства

# Список собирается ЦЕЛИКОМ и только потом обходится: adb, вызванный внутри цикла
# по потоку, дочитывает этот же поток за себя, и все устройства кроме первого молча
# пропадают.
log "1. Ищем устройства с развёрнутым столом"

declare -a FOUND=()
while read -r serial state _; do
    [ "$state" = "device" ] && FOUND+=("$serial")
done < <("$ADB" devices | tail -n +2)

declare -a SERIALS=() SDKS=() RELS=() NAMES=() NATIVES=()
# Пустой массив в bash 3.2 (тот, что в macOS) под `set -u` — «unbound variable»:
# обход обязателен, иначе проверка ругается на себя вместо того, чтобы сказать
# человеку, что устройств нет.
for serial in ${FOUND[@]+"${FOUND[@]}"}; do
    if [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
        bad "$serial: система ещё не загрузилась"
        continue
    fi
    apk="$("$ADB" -s "$serial" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
    if [ -z "$apk" ]; then
        bad "$serial: приложение не установлено — мерить нечего"
        continue
    fi
    ready="$("$ADB" -s "$serial" shell run-as "$PKG" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')"
    case "$ready" in
        *".stol-ready") ;;
        *) bad "$serial: образ не развёрнут ($ready) — пройдите первую установку: bash tests/e2e/two-devices.sh"
           continue ;;
    esac
    sdk="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    rel="$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
    avd="$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
    [ -n "$avd" ] || avd="$serial"
    native="$(dirname "$apk")/lib/arm64"
    ok "$serial: $avd, Android $rel (API $sdk), образ развёрнут"
    SERIALS+=("$serial"); SDKS+=("$sdk"); RELS+=("$rel"); NAMES+=("$avd"); NATIVES+=("$native")
done

[ "${#SERIALS[@]}" -ge 1 ] || die "нет ни одного устройства с развёрнутым столом — мерить не на чем.
Поднимите эмулятор и пройдите установку:
  ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api35 -no-snapshot -no-audio &
  bash tests/e2e/two-devices.sh"

# ------------------------------------------------------------------ вход в образ

# Единственный способ выполнить команду внутри образа снаружи — повторить то, что
# делает само приложение (ProotSession): тот же proot из каталога библиотек APK, те
# же привязки, то же окружение. Скрипт кладётся в каталог данных приложения, потому
# что /data/local/tmp из-под run-as не читается.
read -r -d '' ENTER_SH <<'ENTER'
#!/system/bin/sh
# Вход в образ, как это делает APK. $1 — каталог библиотек APK, дальше команда.
NAT="$1"; shift
D=/data/user/0/ru.codoceh.stol/files
export LD_LIBRARY_PATH="$D/soname:$NAT"
export PROOT_LOADER="$NAT/libproot-loader.so"
export PROOT_TMP_DIR="$D/tmp"
export TMPDIR="$D/tmp"
export HOME=/root
exec /system/bin/linker64 "$NAT/libproot.so" \
  --kill-on-exit --link2symlink --sysvipc -0 -r "$D/rootfs" -w /root \
  -b /dev -b /proc -b /sys \
  -b /dev/urandom:/dev/random \
  -b /proc/self/fd:/dev/fd -b /proc/self/fd/0:/dev/stdin \
  -b /proc/self/fd/1:/dev/stdout -b /proc/self/fd/2:/dev/stderr \
  -b "$D/tmp/shm:/dev/shm" \
  /usr/bin/env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  TERM=xterm-256color LANG=C.UTF-8 /bin/sh -lc "$*"
ENTER

# Замерщик живёт ВНУТРИ образа отдельным файлом: команда через adb теряет кавычки,
# и всё сложное надо класть файлом, а не строкой.
#
# Конвейеров здесь нет намеренно: под proot «… | head» и «… | grep -q» роняют левую
# сторону с «Operation not permitted». Всё пишется в файлы и разбирается снаружи.
read -r -d '' PROBE_SH <<'PROBE'
#!/bin/sh
# /tmp/render-probe.sh — замер потолка отрисовки. $1 — геометрия окна, $2 — секунды.
set -u
GEOM="$1"; SECS="$2"
export DISPLAY="${STOL_DISPLAY:-:1}"
OUT=/tmp/render-ceiling
rm -rf "$OUT"
mkdir -p "$OUT" || exit 1

if ! command -v glxgears >/dev/null 2>&1; then
    echo "ERR=в образе нет glxgears — мерить нечем" > "$OUT/err"
    exit 1
fi

# Условия замера снимаются ДО кручения: потом машина уже занята замером.
timeout 10 wmctrl -lx > "$OUT/windows.txt" 2>&1
timeout 60 glxinfo -B > "$OUT/glxinfo.txt" 2>&1
ls -A /etc/vulkan/icd.d > "$OUT/vulkan.txt" 2>&1
ps -eo args > "$OUT/ps.txt" 2>&1

# Заодно снимаем то, из чего собран был бы windows-стек: архитектура образа, чужие
# архитектуры и наличие частей в каталоге. Это тоже условия замера — без них число
# читается как «медленно», хотя вопрос был «возможно ли вообще».
dpkg --print-architecture > "$OUT/arch.txt" 2>&1
dpkg --print-foreign-architectures > "$OUT/foreign.txt" 2>&1
apt-cache policy wine wine32 box64 > "$OUT/stack.txt" 2>&1

timeout "$SECS" glxgears -geometry "$GEOM" > "$OUT/gears.txt" 2>&1
echo "RC=$?" > "$OUT/rc"
exit 0
PROBE

# Кладёт файл на устройство внутрь каталога данных приложения. Через base64:
# кавычки и переводы строк команда через adb не переживает.
put_app_file() {
    local serial="$1" path="$2" body="$3" b64
    b64="$(printf '%s\n' "$body" | base64 | tr -d '\n')"
    "$ADB" -s "$serial" shell "run-as $PKG sh -c 'echo $b64 | base64 -d > $path'" 2>&1
}

# Выполняет команду внутри образа. Кавычки экранируются целиком: adb склеивает
# аргументы обратно в строку, и без этого до устройства доедет только первое слово.
in_image() {
    local serial="$1" native="$2" command="$3"
    "$ADB" -s "$serial" shell "run-as $PKG sh render-enter.sh $native $(printf '%q' "$command")" 2>&1 | tr -d '\r'
}

# ------------------------------------------------------------------ готовность стола

# Готовность — подключение к маячку 5910, и только оно. /proc/net/tcp не годится:
# сокеты программ внутри образа оттуда из-под adb не видны, хотя порт открыт.
# К 5901 не подключаемся вовсе: недочитанный баннер RFB заносит петлю в чёрный
# список TigerVNC и сломал бы то, что мы меряем.
beacon_open() {
    "$ADB" -s "$1" shell 'echo x | nc -w 1 127.0.0.1 5910 >/dev/null 2>&1 && echo OPEN || echo SHUT' \
        2>/dev/null | tr -d '\r' | grep -q OPEN
}

wait_desktop() {
    local serial="$1" waited=0
    if beacon_open "$serial"; then
        step "стол уже поднят"
        return 0
    fi
    "$ADB" -s "$serial" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    while [ "$waited" -lt "$DESKTOP_LIMIT" ]; do
        beacon_open "$serial" && { step "стол поднялся за ${waited} с"; return 0; }
        sleep 5
        waited=$((waited + 5))
    done
    return 1
}

# ------------------------------------------------------------------ замер

log "2. Меряем потолок отрисовки: glxgears в окне $GEOMETRY, $SECONDS_RUN с"

FAILED=0
declare -a SUMMARY=()

for i in "${!SERIALS[@]}"; do
    serial="${SERIALS[$i]}"; sdk="${SDKS[$i]}"; rel="${RELS[$i]}"
    avd="${NAMES[$i]}"; native="${NATIVES[$i]}"
    tag="$sdk-$avd"
    log "Устройство $avd ($serial), Android $rel (API $sdk)"

    put_app_file "$serial" "render-enter.sh" "$ENTER_SH" >/dev/null
    if ! in_image "$serial" "$native" 'uname -m' | grep -q aarch64; then
        bad "в образ не войти — proot из каталога библиотек APK не запустился"
        FAILED=$((FAILED+1)); SUMMARY+=("API $sdk ($avd): замер не состоялся — не войти в образ"); continue
    fi

    if ! wait_desktop "$serial"; then
        bad "стол не поднялся за $DESKTOP_LIMIT с — мерить нечего"
        FAILED=$((FAILED+1)); SUMMARY+=("API $sdk ($avd): замер не состоялся — стол не поднялся"); continue
    fi

    # Пакет ставится командой самого магазина. Уже стоит — apt просто ничего не делает.
    step "ставим $PACKAGE_TO_INSTALL командой магазина"
    put_app_file "$serial" "files/rootfs/tmp/render-install.sh" "$INSTALL_BODY" >/dev/null
    install_out="$(in_image "$serial" "$native" 'sh /tmp/render-install.sh > /tmp/render-install.log 2>&1; echo RC=$?')"
    if ! grep -q 'RC=0' <<<"$install_out"; then
        "$ADB" -s "$serial" shell "run-as $PKG cat files/rootfs/tmp/render-install.log" 2>/dev/null \
            | tr -d '\r' > "$ART/render-install-$tag.log"
        bad "$PACKAGE_TO_INSTALL не встал ($install_out) — журнал: $ART/render-install-$tag.log"
        FAILED=$((FAILED+1)); SUMMARY+=("API $sdk ($avd): замер не состоялся — пакет не встал"); continue
    fi
    ok "$PACKAGE_TO_INSTALL на месте"

    put_app_file "$serial" "files/rootfs/tmp/render-probe.sh" "$PROBE_SH" >/dev/null
    step "крутим glxgears $SECONDS_RUN с"
    in_image "$serial" "$native" "sh /tmp/render-probe.sh $GEOMETRY $SECONDS_RUN" >/dev/null

    # Забираем всё снятое. Пустой файл здесь — тоже показание, поэтому берём всё.
    grab() { "$ADB" -s "$serial" shell "run-as $PKG cat files/rootfs/tmp/render-ceiling/$1" 2>/dev/null | tr -d '\r'; }
    gears="$(grab gears.txt)"
    err="$(grab err)"
    glxinfo="$(grab glxinfo.txt)"
    windows="$(grab windows.txt)"
    vulkan="$(grab vulkan.txt)"
    stack="$(grab stack.txt)"
    arch="$(grab arch.txt)"
    foreign="$(grab foreign.txt)"
    # Загрузку берём с Android: /proc/loadavg программам внутри образа не отдаётся.
    loadavg="$("$ADB" -s "$serial" shell cat /proc/loadavg 2>/dev/null | tr -d '\r')"

    if [ -n "$err" ]; then
        bad "${err#ERR=}"
        FAILED=$((FAILED+1)); SUMMARY+=("API $sdk ($avd): замер не состоялся — ${err#ERR=}"); continue
    fi

    # Снятое кладём файлами и дальше работаем с файлами: передавать многострочный
    # вывод через строки оболочки — терять его на первом же неудачном символе.
    printf '%s\n' "$gears"   > "$ART/render-gears-$tag.txt"
    printf '%s\n' "$glxinfo" > "$ART/render-glxinfo-$tag.txt"
    printf '%s\n' "$windows" > "$ART/render-windows-$tag.txt"
    printf '%s\n' "$vulkan"  > "$ART/render-vulkan-$tag.txt"
    printf '%s\n' "$stack"   > "$ART/render-stack-$tag.txt"

    # Размер стола берём с живого процесса, а не из наших умолчаний.
    desktop_geom="$("$ADB" -s "$serial" shell "ps -A -o ARGS" 2>/dev/null | tr -d '\r' \
        | sed -n 's/.*[X]tigervnc :1 -geometry \([0-9x]*\).*/\1/p' | head -1)"
    [ -n "$desktop_geom" ] || desktop_geom="неизвестен"
    focus="$("$ADB" -s "$serial" shell dumpsys window 2>/dev/null | tr -d '\r' \
        | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \(.*\)}/\1/p' | head -1)"
    [ -n "$focus" ] || focus="неизвестно"

    json="$ART/render-ceiling-$tag.json"
    if ! python3 "$HERE/render-report.py" \
            --out "$json" \
            --gears "$ART/render-gears-$tag.txt" \
            --glxinfo "$ART/render-glxinfo-$tag.txt" \
            --windows "$ART/render-windows-$tag.txt" \
            --vulkan "$ART/render-vulkan-$tag.txt" \
            --stack "$ART/render-stack-$tag.txt" \
            --serial "$serial" --avd "$avd" --sdk "$sdk" --release "$rel" \
            --window "$GEOMETRY" --seconds "$SECONDS_RUN" \
            --desktop "$desktop_geom" --focus "$focus" --loadavg "$loadavg" \
            --arch "$arch" --foreign "${foreign:-нет}"; then
        bad "кадров в выводе glxgears не нашлось — замер не состоялся, вывод: $ART/render-gears-$tag.txt"
        FAILED=$((FAILED+1)); SUMMARY+=("API $sdk ($avd): замер не состоялся — кадров никто не насчитал"); continue
    fi

    fps_median="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['fps']['median'])" "$json")"
    fps_min="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['fps']['min'])" "$json")"
    fps_max="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['fps']['max'])" "$json")"
    renderer="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['conditions']['renderer'])" "$json")"
    accel="$(python3 -c "import json,sys; print(json.load(open(sys.argv[1]))['conditions']['accelerated'])" "$json")"

    ok "медиана $fps_median кадр/с (от $fps_min до $fps_max), отрисовщик $renderer, ускорение: $accel"
    step "число и условия: $json"
    SUMMARY+=("API $sdk ($avd): $fps_median кадр/с в окне $GEOMETRY на столе $desktop_geom, отрисовщик $renderer, ускорение $accel")
done

echo
log "Итог: потолок отрисовки"
for l in "${SUMMARY[@]}"; do step "$l"; done

[ "$FAILED" -eq 0 ] || die "замер не состоялся на $FAILED устройстве(ах) — числа нет, а значит и ответа нет.
Артефакты: $ART/render-*"

printf '\n\033[32mЗЕЛЁНО\033[0m  потолок отрисовки измерен на %d устройстве(ах); число — в %s/render-ceiling-*.json\n' \
    "${#SERIALS[@]}" "$ART"
exit 0
