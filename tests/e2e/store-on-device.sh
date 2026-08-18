#!/usr/bin/env bash
#
# tests/e2e/store-on-device.sh — магазин и кузница, доказанные на самом Android (R05, R06).
#
# Прошлый прогон закрыл две дыры бумагой. Магазин ставил программы только внутри
# виртуалки lima: на телефоне стол был живой, а магазином там ни разу ничего не
# ставили. Экран сборки с кузницей и процентом держался на коде и модульных
# тестах — ни одного снимка с устройства.
#
# Здесь обе вещи доказываются на живом эмуляторе:
#
#   МАГАЗИН (R05)  — тап по ярлыку магазина на экране телефона, тап по «Поставить»
#                    в окне магазина, установка, запуск программы, живое окно.
#                    До установки программы на столе нет, и это подтверждается.
#                    Координаты тапов выведены из состояния экрана: место ярлыка —
#                    из раскладки xfdesktop и снимка телефона, место кнопки — из
#                    дерева окон самого магазина (tests/e2e/store-points.py).
#
#   КУЗНИЦА (R06)  — во время ПЕРВОЙ установки APK с экрана снимается снимок и
#                    рядом дамп дерева окон, в котором есть узел «Собрано N %».
#                    Файлы обязаны появиться в ходе прогона: старые сносятся до
#                    установки, а у новых сверяется отметка времени.
#
# Что здесь красное: нет устройства, стол не поднялся, ярлык магазина не открылся,
# магазин не поставил, окна нет, экрана сборки не дождались. Все отказы называют
# причину, а не «проверка не прошла».
#
# Использование:
#   bash tests/e2e/store-on-device.sh            обе половины
#   bash tests/e2e/store-on-device.sh --store    только магазин (APK не переставляется)
#   bash tests/e2e/store-on-device.sh --forge    только кузница (первая установка APK)
#
# Устройства: кузница ставится на то, где APK старее (или STOL_FORGE_SERIAL),
# магазин проверяется на устройстве с развёрнутым столом (или STOL_STORE_SERIAL).
#
# Переменные: STOL_E2E_ADB, STOL_STORE_ID (id программы из каталога, по умолчанию
# galculator), STOL_STORE_ALIVE (15 с), STOL_STORE_DESKTOP_LIMIT, STOL_STORE_APT_LIMIT,
# STOL_FORGE_LIMIT (сколько ловить экран сборки), STOL_FORGE_ATTEMPTS, STOL_E2E_INSTALL_TIMEOUT, STOL_STORE_NO_START (не поднимать стол
# самому), STOL_STORE_MUTATION (no-install | no-window — рычаги, которыми проверяется,
# что проверка умеет краснеть).

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"
PKG="ru.codoceh.stol"
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
STORE_ID="${STOL_STORE_ID:-galculator}"
ALIVE="${STOL_STORE_ALIVE:-15}"
DESKTOP_LIMIT="${STOL_STORE_DESKTOP_LIMIT:-600}"
APT_LIMIT="${STOL_STORE_APT_LIMIT:-900}"
FORGE_LIMIT="${STOL_FORGE_LIMIT:-420}"
FORGE_ATTEMPTS="${STOL_FORGE_ATTEMPTS:-3}"
INSTALL_TIMEOUT="${STOL_E2E_INSTALL_TIMEOUT:-2400}"
MUTATION="${STOL_STORE_MUTATION:-}"
NO_START="${STOL_STORE_NO_START:-}"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

MODE="all"
while [ $# -gt 0 ]; do
    case "$1" in
        --store) MODE="store" ;;
        --forge) MODE="forge" ;;
        -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
        *) die "неизвестный ключ: $1" ;;
    esac
    shift
done

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb (${ADB}).
Поставьте platform-tools из Android SDK или укажите путь в STOL_E2E_ADB."
command -v python3 >/dev/null 2>&1 || die "не найден python3 — снимки и деревья окон разбирать нечем"
python3 -c "import PIL, numpy" >/dev/null 2>&1 \
    || die "у python3 нет Pillow или numpy — место ярлыка на снимке вывести нечем"
[ -f "$HERE/store-points.py" ] || die "нет $HERE/store-points.py — координаты тапа выводить нечем"
[ -f "$REPO/store/backend.py" ] || die "нет store/backend.py — сносить пакет магазинными командами нечем"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/store-on-device.XXXXXX")"
trap 'store_cleanup; rm -rf "$TMP"' EXIT
mkdir -p "$ART"

# ------------------------------------------------------------------ устройства

# Список собирается ЦЕЛИКОМ и только потом обходится: adb, вызванный внутри цикла по
# потоку, дочитывает этот же поток за себя, и все устройства кроме первого пропадают.
declare -a SERIALS=() SDKS=() RELS=() AVDS=() READY=() STAMPS=()

collect_devices() {
    local serial state rest
    declare -a found=()
    while read -r serial state rest; do
        [ "$state" = "device" ] && found+=("$serial")
    done < <("$ADB" devices | tail -n +2)

    local sdk rel avd ready stamp
    for serial in ${found[@]+"${found[@]}"}; do
        [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
            bad "${serial}: система ещё не загрузилась"
            continue
        }
        sdk="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
        rel="$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
        avd="$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
        [ -n "$avd" ] || avd="$serial"
        ready="нет"
        case "$("$ADB" -s "$serial" shell run-as "$PKG" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')" in
            *".stol-ready") ready="да" ;;
        esac
        stamp="$("$ADB" -s "$serial" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' \
            | sed -n 's/.*lastUpdateTime=//p' | head -1)"
        [ -n "$stamp" ] || stamp="0000-00-00 00:00:00"
        SERIALS+=("$serial"); SDKS+=("$sdk"); RELS+=("$rel"); AVDS+=("$avd")
        READY+=("$ready"); STAMPS+=("$stamp")
        ok "${serial}: ${avd}, Android ${rel} (API ${sdk}), образ развёрнут: ${ready}, APK от ${stamp}"
    done
}

log "1. Ищем устройства"
collect_devices
[ "${#SERIALS[@]}" -ge 1 ] || die "не подключено ни одного живого устройства — доказывать не на чем.
Поднимите эмулятор и пройдите первую установку:
  ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api35 -no-snapshot -no-audio &
  bash tests/e2e/two-devices.sh"

index_of_serial() {
    local want="$1" i
    for i in "${!SERIALS[@]}"; do
        [ "${SERIALS[$i]}" = "$want" ] && { printf '%s' "$i"; return 0; }
    done
    return 1
}

# Кузницу снимаем там, где APK старее: свежий APK туда ещё предстоит поставить, и
# первая установка — единственный случай, когда экран сборки вообще показывают.
pick_forge() {
    local i best=0
    for i in "${!SERIALS[@]}"; do
        [[ "${STAMPS[$i]}" < "${STAMPS[$best]}" ]] && best="$i"
    done
    printf '%s' "$best"
}

# Магазин проверяем на устройстве с развёрнутым столом — и, если есть выбор, не на
# том, куда сейчас переставляется APK.
pick_store() {
    local i forge="$1" fallback=""
    for i in "${!SERIALS[@]}"; do
        [ "${READY[$i]}" = "да" ] || continue
        [ -n "$fallback" ] || fallback="$i"
        [ "$i" = "$forge" ] && continue
        printf '%s' "$i"; return 0
    done
    [ -n "$fallback" ] || return 1
    printf '%s' "$fallback"
}

# ------------------------------------------------------------------ работа с образом

# Кладёт файл в каталог данных приложения. Через base64: кавычки и переводы строк
# команда через adb не переживает.
put_file() {
    local serial="$1" path="$2" body="$3" b64
    b64="$(printf '%s\n' "$body" | base64 | tr -d '\n')"
    "$ADB" -s "$serial" shell "run-as $PKG sh -c 'echo $b64 | base64 -d > $path'" >/dev/null 2>&1
}

# Запускалка команд внутри образа: повторяет то, что делает приложение (ProotSession).
# Шина берётся та же, на которой живёт стол, — иначе программы внутри образа не
# увидят ни xfconf, ни службу файлового менеджера.
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

# Команда внутри образа. Едет файлом, а не строкой в аргументах: adb склеивает
# аргументы обратно в строку и теряет вложенные кавычки.
in_image() {
    put_file "$SERIAL" "files/rootfs/tmp/store-cmd.sh" "$1"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/store-enter.sh '$NATIVE' store-cmd.sh" 2>&1 | tr -d '\r'
}

# Готовность стола — подключение к маячку 5910, и только оно: сокеты программ внутри
# образа из /proc/net/tcp под adb не видны, а к 5901 подключаться нельзя вовсе —
# недочитанный баннер RFB заносит петлю в чёрный список TigerVNC.
beacon_open() {
    "$ADB" -s "$1" shell 'echo x | nc -w 1 127.0.0.1 5910 >/dev/null 2>&1 && echo OPEN || echo SHUT' \
        2>/dev/null | tr -d '\r' | grep -q OPEN
}

wait_desktop() {
    local serial="$1" limit="$2" waited=0
    beacon_open "$serial" && { step "стол уже поднят"; return 0; }
    [ -n "$NO_START" ] || "$ADB" -s "$serial" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    while [ "$waited" -lt "$limit" ]; do
        sleep 5
        waited=$((waited + 5))
        beacon_open "$serial" && { step "стол поднялся за ${waited} с"; return 0; }
    done
    return 1
}

# ================================================================== КУЗНИЦА (R06)

# Ставит APK начисто. Возвращает ненулевой код, если установка не состоялась.
forge_install() {
    local serial="$1"
    "$ADB" -s "$serial" uninstall "$PKG" >/dev/null 2>&1
    "$ADB" -s "$serial" install -r -t "$APK" >"$TMP/install.log" 2>&1 &
    local pid=$! waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$INSTALL_TIMEOUT" ]; then
            kill "$pid" 2>/dev/null
            bad "установка не уложилась в ${INSTALL_TIMEOUT} с, журнал: $TMP/install.log"
            return 1
        fi
        sleep 10
        waited=$((waited + 10))
        step "ставим APK, идёт ${waited} с"
    done
    wait "$pid"
    grep -q Success "$TMP/install.log" || {
        bad "adb install не сказал Success: $(tr '\n' ' ' <"$TMP/install.log")"
        return 1
    }
    ok "свежий APK установлен за ${waited} с"
    return 0
}

# Один заход: поставить APK начисто, запустить и ловить экран сборки, пока он на
# экране. Окно у него узкое — минута-полторы, — а `uiautomator dump` на живой
# анимации кузницы ждёт покоя и отвечает не сразу, поэтому паузы между снимками
# нет: следующий круг начинается, как только вернулся предыдущий дамп.
forge_attempt() {
    local serial="$1" tag="$2" shot="$3" dump="$4"
    FORGE_PERCENT=""
    FORGE_SECONDS=0
    FORGE_TRIES=0

    forge_install "$serial" || return 1
    "$ADB" -s "$serial" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 \
        || { bad "приложение не запустилось — экран сборки показывать некому"; return 1; }

    local began now
    began="$(date +%s)"
    step "ловим экран сборки: снимок и дамп дерева окон подряд, без пауз"
    while :; do
        now="$(date +%s)"
        FORGE_SECONDS=$((now - began))
        [ "$FORGE_SECONDS" -lt "$FORGE_LIMIT" ] || {
            bad "за ${FORGE_LIMIT} с экран сборки поймать не удалось (снято ${FORGE_TRIES} дампов)"
            return 1
        }

        "$ADB" -s "$serial" exec-out screencap -p > "$TMP/forge.png" 2>/dev/null
        "$ADB" -s "$serial" shell rm -f /sdcard/stol-forge.xml >/dev/null 2>&1
        "$ADB" -s "$serial" shell uiautomator dump /sdcard/stol-forge.xml >/dev/null 2>&1
        "$ADB" -s "$serial" shell cat /sdcard/stol-forge.xml > "$TMP/forge.xml" 2>/dev/null
        FORGE_TRIES=$((FORGE_TRIES + 1))

        FORGE_PERCENT="$(grep -o 'Собрано [0-9]\{1,3\} %' "$TMP/forge.xml" 2>/dev/null | head -1)"
        if [ -n "$FORGE_PERCENT" ]; then
            cp "$TMP/forge.png" "$shot"
            cp "$TMP/forge.xml" "$dump"
            return 0
        fi

        # Приложение могло упасть: ждать нечего.
        if ! "$ADB" -s "$serial" shell pidof "$PKG" >/dev/null 2>&1; then
            "$ADB" -s "$serial" logcat -d > "$ART/forge-logcat-${tag}.txt" 2>/dev/null
            bad "приложение больше не запущено — экрана сборки не будет.
       Журнал устройства: $ART/forge-logcat-${tag}.txt"
            return 1
        fi
        # Стол поднялся — сборка уже позади, ловить больше нечего.
        if beacon_open "$serial"; then
            bad "сборка закончилась за ${FORGE_SECONDS} с, а экран с процентом снять не успели (дампов ${FORGE_TRIES})"
            return 1
        fi
    done
}

check_forge() {
    local i="$1"
    local serial="${SERIALS[$i]}" sdk="${SDKS[$i]}" avd="${AVDS[$i]}" rel="${RELS[$i]}"
    local tag="${sdk}-${avd}"
    local shot="$ART/forge-${tag}.png" dump="$ART/forge-${tag}.xml"

    log "2. Кузница: снимок экрана сборки на устройстве ${avd} (${serial}), Android ${rel}"
    [ -f "$APK" ] || die "APK не собран: ${APK} отсутствует.
Соберите: bash ci/build-release.sh"

    # Старые доказательства сносим ДО установки: файл, доживший с прошлого раза,
    # выдал бы вчерашнюю кузницу за сегодняшнюю (R06.2).
    rm -f "$shot" "$dump"
    local started attempt=1 caught=""
    started="$(date +%s)"

    # Заходов больше одного: экран сборки живёт минуту, а дамп дерева окон на живой
    # анимации отвечает секунды — промахнуться можно не по вине продукта. Промах
    # стоит одной переустановки, поэтому повтор дешевле, чем ложное красное.
    while [ "$attempt" -le "$FORGE_ATTEMPTS" ]; do
        step "заход ${attempt} из ${FORGE_ATTEMPTS}: сносим прошлую установку и ставим свежий APK ($(du -h "$APK" | cut -f1))"
        if forge_attempt "$serial" "$tag" "$shot" "$dump"; then
            caught="да"
            break
        fi
        attempt=$((attempt + 1))
    done

    [ -n "$caught" ] || die "экран сборки с кузницей и процентом на устройстве снять не удалось за ${FORGE_ATTEMPTS} заход(а).
В дереве окон ни разу не встретился узел «Собрано N %» — либо экрана сборки нет, либо он не
показывает процент. Последний дамп: $TMP/forge.xml"

    # Файл обязан появиться в ходе прогона, а не лежать с прошлого раза (R06.2).
    local mtime
    mtime="$(python3 -c "import os,sys; print(int(os.path.getmtime(sys.argv[1])))" "$shot")"
    [ "$mtime" -ge "$started" ] \
        || die "снимок ${shot} старше начала прогона — снят не этой проверкой"

    {
        echo "устройство: ${serial}"
        echo "эмулятор:   ${avd}"
        echo "Android:    ${rel} (API ${sdk})"
        echo "APK:        ${APK}"
        echo "снято:      $(date '+%Y-%m-%d %H:%M:%S %Z'), на ${FORGE_SECONDS}-й секунде после запуска, дамп ${FORGE_TRIES}-й"
        echo "в дереве:   ${FORGE_PERCENT}"
    } > "$ART/forge-${tag}.txt"

    ok "экран сборки снят на ${FORGE_SECONDS}-й секунде: в дереве окон «${FORGE_PERCENT}»"
    step "снимок: ${shot}"
    step "дамп дерева окон: ${dump}"
    return 0
}

# ================================================================== МАГАЗИН (R05)

SERIAL=""
NATIVE=""
HOLDER=""

store_cleanup() {
    [ -n "$SERIAL" ] || return 0
    in_image 'pkill -f "codoceh-store.py"
pkill -f "/tmp/store-hold.sh"
[ -f /tmp/store-run.pid ] && kill "$(cat /tmp/store-run.pid)" 2>/dev/null
rm -f /tmp/store-run.pid
exit 0' >/dev/null 2>&1
    [ -n "$HOLDER" ] && { kill "$HOLDER"; wait "$HOLDER"; } 2>/dev/null
    "$ADB" -s "$SERIAL" shell "run-as $PKG rm -f files/store-enter.sh files/rootfs/tmp/store-cmd.sh files/rootfs/tmp/store-hold.sh" >/dev/null 2>&1
    return 0
}

# Точка стола → точка экрана.
dev_x() { python3 -c "print(int(round($OFF_X + $1 * $SCALE)))"; }
dev_y() { python3 -c "print(int(round($OFF_Y + $1 * $SCALE)))"; }

# Как приложение вписывает стол в экран телефона. Размер стола берётся у самого
# X-сервера, а прямоугольник, в который приложение вписывает картинку, — из дерева
# окон Android: это вид нашего пакета, принимающий касания.
#
# Врезки системных полос из `dumpsys window` здесь НЕ читаются, хотя так и просилось:
# на Android 11 строки InsetsSource выглядят иначе, чем на 15, врезки читались нулями,
# и стол «съезжал» на 31 точку — проверка искала ярлыки не там, где они нарисованы.
# Дерево окон отдаёт готовый прямоугольник и одинаково на обеих версиях.
store_geometry() {
    DESK_W="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\1/p"' | head -1)"
    DESK_H="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\2/p"' | head -1)"
    [[ "$DESK_W" =~ ^[0-9]+$ ]] && [[ "$DESK_H" =~ ^[0-9]+$ ]] \
        || die "стол не назвал свой размер (xdpyinfo вернул «${DESK_W}×${DESK_H}») — координаты выводить не из чего"

    "$ADB" -s "$SERIAL" shell rm -f /sdcard/stol-frame.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/stol-frame.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell cat /sdcard/stol-frame.xml > "$TMP/frame.xml" 2>/dev/null
    [ -s "$TMP/frame.xml" ] || die "дерево окон Android не снялось — куда приложение вписало стол, узнать нечем"

    local map
    map="$(python3 "$HERE/store-points.py" frame --dump "$TMP/frame.xml" \
        --desk-width "$DESK_W" --desk-height "$DESK_H" --package "$PKG" 2>&1)" \
        || die "прямоугольник стола на экране не выведен: $(sed -n 's/^ОШИБКА=//p' <<<"${map}")"
    SCALE="$(sed -n 's/^SCALE=//p' <<<"$map")"
    OFF_X="$(sed -n 's/^OFFSET_X=//p' <<<"$map")"
    OFF_Y="$(sed -n 's/^OFFSET_Y=//p' <<<"$map")"

    # Панель — тоже яркая полоса во всю ширину стола; её верхняя граница нужна, чтобы
    # не считать панель строкой значков.
    PANEL_TOP="$(in_image 'wmctrl -lG -x' | grep -i 'xfce4-panel' | sort -k6 -n -r | head -1 | awk '{print $4}')"
    [[ "$PANEL_TOP" =~ ^[0-9]+$ ]] || PANEL_TOP="$DESK_H"
    step "стол ${DESK_W}×${DESK_H} вписан в вид $(sed -n 's/^VIEW=//p' <<<"$map"), масштаб ${SCALE}, панель с ${PANEL_TOP}"
}

# Окно по заголовку: ровно одно, иначе выбирать наугад нельзя.
window_id() {
    local title="$1" found count
    found="$(in_image 'wmctrl -l' | awk -v t="$title" 'index($0, t) { print $1 }')"
    count="$(printf '%s' "$found" | grep -c .)"
    [ "$count" = "1" ] || return 1
    printf '%s' "$found"
}

check_store() {
    local i="$1"
    SERIAL="${SERIALS[$i]}"
    local sdk="${SDKS[$i]}" avd="${AVDS[$i]}" rel="${RELS[$i]}"
    local tag="${sdk}-${avd}"

    log "3. Магазин: установка тапом по ярлыку на устройстве ${avd} (${SERIAL}), Android ${rel}"

    local apk_path
    apk_path="$("$ADB" -s "$SERIAL" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
    [ -n "$apk_path" ] || die "на устройстве ${SERIAL} нет приложения — ставить нечем"
    NATIVE="$(dirname "$apk_path")/lib/arm64"
    put_file "$SERIAL" "files/store-enter.sh" "$IMAGE_ENTER"

    # Стол поднимаем начисто. На столе, который живёт давно и через который уже
    # прошли чужие прогоны, тапы по ярлыкам перестают открывать программы: щелчок
    # доходит до панели, но значок на столе на него не отзывается. Проверено:
    # сразу после запуска стола ярлык открывается, на пожившем — нет. Пока причина
    # не найдена, проверка начинает с чистого стола, а не доказывает на чужом.
    if [ -z "$NO_START" ]; then
        step "поднимаем стол начисто: гасим приложение и запускаем заново"
        "$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
        sleep 5
    fi
    wait_desktop "$SERIAL" "$DESKTOP_LIMIT" \
        || die "стол на устройстве ${SERIAL} не поднялся за ${DESKTOP_LIMIT} с — магазин проверять негде"
    in_image 'uname -m' | grep -q aarch64 || die "в образ не войти — proot из каталога библиотек APK не запустился"

    # --- какую программу ставим: берём из каталога В ОБРАЗЕ, а не из репозитория
    in_image 'cat /opt/stol/store/catalog.json' > "$TMP/catalog.json"
    local target
    target="$(python3 - "$TMP/catalog.json" "$STORE_ID" 2>&1 <<'PY'
import json, sys
try:
    catalog = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception as broken:
    sys.exit("каталог магазина в образе не читается: %s" % broken)
for index, item in enumerate(catalog):
    if item.get("id") == sys.argv[2]:
        # Разделитель не пробельный: у программы без своих переменных поле env пусто,
        # а пробельный разделитель склеил бы пустое поле с соседним.
        print("%d|%d|%s|%s|%s" % (index, len(catalog), item["package"],
                                  item.get("env", ""), item.get("name", "")))
        break
else:
    sys.exit("в каталоге образа нет программы с id «%s» — ставить нечего" % sys.argv[2])
PY
)" || die "$target"
    local index rows package env name
    IFS='|' read -r index rows package env name <<<"$target"
    ok "ставим ${name} (${package}): ${index}-я строка каталога из ${rows}"

    # --- R05.3: пакет сносится перед установкой, отсутствие подтверждается
    local remove_body
    remove_body="$(python3 - "$REPO" "$package" 2>&1 <<'PY'
import sys
sys.path.insert(0, sys.argv[1] + "/store")
import backend
sys.stdout.write(backend.remove_command(sys.argv[2]))
PY
)" || die "магазин не отдал команду удаления — сломан store/backend.py"
    step "сносим ${package} магазинной командой, чтобы установка была настоящей"
    in_image "$remove_body
dpkg --purge $package >/dev/null 2>&1
exit 0" >/dev/null 2>&1

    local before
    before="$(in_image "dpkg-query -W -f='\${Status}' $package 2>/dev/null; echo; command -v $package || echo НЕТ")"
    if printf '%s' "$before" | grep -q "install ok installed"; then
        die "перед установкой ${package} всё ещё стоит в образе — доказывать нечего:
${before}"
    fi
    printf '%s' "$before" | grep -q "НЕТ" \
        || die "перед установкой в образе уже лежит программа ${package} (${before}) — установка ничего не докажет"
    ok "до установки программы на столе нет: dpkg её не знает, ${package} в путях не найден"

    # --- чистое поле: чужих окон магазина на столе быть не должно
    in_image 'pkill -f "codoceh-store.py"' >/dev/null 2>&1
    sleep 2
    if in_image 'wmctrl -l' | grep -q "Магазин КодоЦех"; then
        die "на столе осталось окно магазина от прошлого раза — новое от старого не отличить"
    fi

    store_geometry

    # --- снимок «до» и место ярлыка магазина
    #
    # Снимков может понадобиться несколько: на свежем устройстве xfdesktop дорисовывает
    # ярлыки не сразу, и первый снимок застаёт полупустой стол. Пересъёмка честнее
    # ожидания «на всякий случай»: как только ярлык на снимке нашёлся, работаем.
    local point="" icon_x="" icon_y="" try
    for try in 1 2 3 4 5 6; do
        "$ADB" -s "$SERIAL" exec-out screencap -p > "$ART/store-before-${tag}.png" 2>/dev/null
        [ -s "$ART/store-before-${tag}.png" ] || die "снимок экрана телефона не снялся — доказывать нечем"
        in_image 'cat /root/.config/xfce4/desktop/icons.screen0.yaml' > "$TMP/icons.yaml"
        [ -s "$TMP/icons.yaml" ] \
            || die "xfdesktop не назвал раскладку значков (icons.screen0.yaml пуст) — место ярлыка выводить не из чего"
        point="$(python3 "$HERE/store-points.py" icon \
            --shot "$ART/store-before-${tag}.png" --icons "$TMP/icons.yaml" \
            --entry /root/Desktop/codoceh-store.desktop \
            --scale "$SCALE" --offset-x "$OFF_X" --offset-y "$OFF_Y" \
            --desk-width "$DESK_W" --desk-height "$DESK_H" --panel-top "$PANEL_TOP")" && break
        step "снимок ${try}: $(sed -n 's/^ОШИБКА=//p' <<<"${point}")"
        point=""
        sleep 5
    done
    [ -n "$point" ] || die "место ярлыка магазина на столе не выведено ни по одному из шести снимков —
последняя причина выше. Снимок: $ART/store-before-${tag}.png"
    icon_x="$(sed -n 's/^DESK_X=//p' <<<"$point")"
    icon_y="$(sed -n 's/^DESK_Y=//p' <<<"$point")"
    ok "ярлык магазина: строка $(sed -n 's/^ROW=//p' <<<"$point"), столбец $(sed -n 's/^COLUMN=//p' <<<"$point"), шаг сетки $(sed -n 's/^STEP=//p' <<<"$point") — точка стола (${icon_x}, ${icon_y})"

    # --- тап по ярлыку: двойной клик, как на столе Windows
    local tap_x tap_y store_win=""
    tap_x="$(dev_x "$icon_x")"; tap_y="$(dev_y "$icon_y")"

    # Разогревочный тап по пустому углу стола. Первое касание после запуска стола
    # пропадает: приложение на нём забирает фокус ввода, и до стола это касание не
    # доходит. Проверено перезапуском — первый двойной тап не открывает ничего,
    # второй открывает. Пустой угол берётся справа внизу: значки xfdesktop
    # выкладывает слева, там их не бывает.
    step "разогрев: тап по пустому углу стола"
    "$ADB" -s "$SERIAL" shell "input tap $(dev_x $((DESK_W - 40))) $(dev_y $((PANEL_TOP - 40)))" >/dev/null 2>&1
    sleep 3

    # Двойной тап приходится собирать вручную. Два `input tap` подряд идут на эмуляторе
    # 1,2–1,7 секунды: каждый поднимает свою виртуальную машину, и стол видит два
    # отдельных щелчка, а не двойной — окно двойного щелчка в XFCE 400 мс. Поэтому
    # первый запускается в стороне, а второй через четверть секунды: обе машины
    # поднимаются одинаково долго, и щелчки приходят с человеческим промежутком.
    step "тапаем дважды по ярлыку в точке экрана (${tap_x}, ${tap_y})"
    for try in 1 2 3 4 5 6 7 8; do
        "$ADB" -s "$SERIAL" shell "input tap $tap_x $tap_y & sleep 0.25; input tap $tap_x $tap_y; wait" >/dev/null 2>&1
        sleep 10
        store_win="$(window_id "Магазин КодоЦех")" && break
        step "с ${try}-го раза магазин не открылся, повторяем"
        # Окно отказа xfdesktop перехватывает дальнейшие клики: закрываем и говорим прямо.
        if in_image 'wmctrl -l' | grep -q "Ошибка запуска"; then
            in_image 'for id in $(wmctrl -l | grep "Ошибка запуска" | cut -d" " -f1); do wmctrl -i -c $id; done' >/dev/null 2>&1
            die "стол ответил на ярлык магазина окном «Ошибка запуска» — ярлык на столе не открывается.
Так бывает, когда в сессии стола нет службы файлового менеджера: xfdesktop отдаёт запуск
.desktop ей и без неё не запускает ни магазин, ни офис (см. image/start-gui.sh)."
        fi
    done
    [ -n "$store_win" ] || die "после восьми двойных тапов по ярлыку окна магазина на столе нет —
ярлык магазина с телефона не открывается"
    ok "магазин открылся тапом по ярлыку: окно ${store_win}"

    # Окно на весь стол: в 640×520 видно шесть строк каталога, и седьмая программа
    # была бы недоступна тапу не по вине магазина.
    in_image "wmctrl -i -r $store_win -b add,maximized_vert,maximized_horz" >/dev/null 2>&1
    sleep 3

    # --- тап по «Поставить» нужной строки
    in_image "xwininfo -id $store_win -tree" > "$TMP/tree.txt"
    local button
    button="$(python3 "$HERE/store-points.py" button --tree "$TMP/tree.txt" --index "$index" --rows "$rows")" \
        || die "кнопку «Поставить» в окне магазина не найти: $(sed -n 's/^ОШИБКА=//p' <<<"$button")"
    local btn_x btn_y
    btn_x="$(dev_x "$(sed -n 's/^DESK_X=//p' <<<"$button")")"
    btn_y="$(dev_y "$(sed -n 's/^DESK_Y=//p' <<<"$button")")"
    ok "кнопка строки $((index + 1)) — точка экрана (${btn_x}, ${btn_y})"

    if [ "$MUTATION" = "no-install" ]; then
        step "мутация no-install: по кнопке не тапаем — проверка обязана покраснеть"
    else
        "$ADB" -s "$SERIAL" shell "input tap $btn_x $btn_y" >/dev/null 2>&1
    fi

    # --- ждём, пока магазин доставит пакет
    step "ждём установку: спрашиваем dpkg каждые 10 с"
    local waited=0 installed=""
    while [ "$waited" -lt "$APT_LIMIT" ]; do
        sleep 10
        waited=$((waited + 10))
        if in_image "dpkg-query -W -f='\${Status}' $package 2>/dev/null" | grep -q "install ok installed"; then
            installed="да"
            break
        fi
        [ $((waited % 60)) -eq 0 ] && step "идёт ${waited} с, магазин ещё ставит"
    done
    [ -n "$installed" ] || die "магазин не поставил ${package} за ${APT_LIMIT} с — установка с телефона не работает.
Что говорит сам магазин: $(in_image "tail -3 /tmp/store.log 2>/dev/null" | tr '\n' ' ')"
    ok "магазин поставил ${package} за ${waited} с"

    # --- запуск программы и живое окно
    # Мутация no-window: вместо программы запускаем то, у чего окна нет и быть не может.
    local launch="${package}"
    [ -n "$env" ] && launch="${env} ${package}"
    [ "$MUTATION" = "no-window" ] && launch="sleep 300"
    step "запускаем ${launch} и ищем его окно по номеру процесса"
    put_file "$SERIAL" "files/rootfs/tmp/store-hold.sh" "#!/bin/sh
${launch} >/tmp/store-run.log 2>&1 &
echo \$! > /tmp/store-run.pid
sleep 300"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/store-enter.sh '$NATIVE' store-hold.sh" >/dev/null 2>&1 &
    HOLDER=$!

    local pid="" window="" alive=0
    waited=0
    while [ "$waited" -lt 120 ]; do
        sleep 5
        waited=$((waited + 5))
        pid="$(in_image 'cat /tmp/store-run.pid 2>/dev/null' | tr -d ' ')"
        [[ "$pid" =~ ^[0-9]+$ ]] || continue
        window="$(in_image 'wmctrl -lp' | awk -v p="$pid" '$3 == p { print $1 }' | head -1)"
        [ -n "$window" ] && break
    done
    [ -n "$window" ] || die "окна программы ${package} на столе нет: процесс ${pid:-не запустился}, ждали ${waited} с.
Что сказала программа: $(in_image 'cat /tmp/store-run.log 2>/dev/null' | tr '\n' ' ')"
    ok "окно найдено по номеру процесса ${pid}: ${window}"

    step "проверяем, что окно живо через ${ALIVE} с"
    sleep "$ALIVE"
    alive="$(in_image "wmctrl -lp" | awk -v p="$pid" '$3 == p { print $1 }' | head -1)"
    [ "$alive" = "$window" ] \
        || die "через ${ALIVE} с окна программы ${package} на столе не осталось — программа не живёт, а мигает"
    ok "через ${ALIVE} с окно ${window} на месте"

    "$ADB" -s "$SERIAL" exec-out screencap -p > "$ART/store-after-${tag}.png" 2>/dev/null
    [ -s "$ART/store-after-${tag}.png" ] || die "снимок «после» не снялся"

    {
        echo "устройство: ${SERIAL}"
        echo "эмулятор:   ${avd}"
        echo "Android:    ${rel} (API ${sdk})"
        echo "программа:  ${name} (${package}), ${index}-я строка каталога из ${rows}"
        echo "ярлык:      точка стола (${icon_x}, ${icon_y}) → точка экрана (${tap_x}, ${tap_y})"
        echo "кнопка:     точка экрана (${btn_x}, ${btn_y})"
        echo "окно:       ${window}, номер процесса ${pid}, живо ${ALIVE} с"
        echo "снято:      $(date '+%Y-%m-%d %H:%M:%S %Z')"
    } > "$ART/store-${tag}.txt"

    ok "снимки: $ART/store-before-${tag}.png и $ART/store-after-${tag}.png"
    return 0
}

# ==================================================================

# Устройство выбирается здесь, а не внутри подстановки команды: die из подстановки
# гасит только подоболочку, и следом печаталась бы вторая, уже неверная причина.
if [ -n "${STOL_FORGE_SERIAL:-}" ]; then
    FORGE_I="$(index_of_serial "$STOL_FORGE_SERIAL")" \
        || die "устройства ${STOL_FORGE_SERIAL} из STOL_FORGE_SERIAL среди живых нет"
else
    FORGE_I="$(pick_forge)"
fi

STORE_I=""
if [ "$MODE" != "forge" ]; then
    if [ -n "${STOL_STORE_SERIAL:-}" ]; then
        STORE_I="$(index_of_serial "$STOL_STORE_SERIAL")" \
            || die "устройства ${STOL_STORE_SERIAL} из STOL_STORE_SERIAL среди живых нет"
        [ "${READY[$STORE_I]}" = "да" ] || [ "$MODE" = "all" ] \
            || die "на устройстве ${STOL_STORE_SERIAL} образ не развёрнут — магазин проверять негде.
Пройдите первую установку: bash tests/e2e/two-devices.sh"
    else
        STORE_I="$(pick_store "$FORGE_I")" \
            || die "ни на одном устройстве образ не развёрнут — магазин проверять негде.
Пройдите первую установку: bash tests/e2e/two-devices.sh"
    fi
fi

[ "$MODE" = "store" ] || check_forge "$FORGE_I"
if [ "$MODE" != "forge" ]; then
    # Кузница переставила APK на своём устройстве: если магазин проверяется там же,
    # стол сначала разворачивается заново.
    [ "$STORE_I" = "$FORGE_I" ] && [ "$MODE" != "store" ] && step "магазин проверяем на том же устройстве — ждём, пока стол развернётся"
    check_store "$STORE_I"
fi

echo
case "$MODE" in
    forge) printf '\033[32mЗЕЛЁНО\033[0m  экран сборки с кузницей и процентом снят с живого устройства (R06)\n' ;;
    store) printf '\033[32mЗЕЛЁНО\033[0m  магазин поставил программу на живом Android тапом по ярлыку (R05)\n' ;;
    *)     printf '\033[32mЗЕЛЁНО\033[0m  магазин доказан на самом Android и кузница снята с устройства (R05, R06)\n' ;;
esac
exit 0
