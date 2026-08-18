#!/usr/bin/env bash
#
# tests/e2e/office-on-device.sh — офис открывается тапом по ярлыку на живом Android
# (R01.1, R01.2, R16D).
#
# Задача 02 положила LibreOffice в образ и доказала это наличием файлов. Наличие
# файла — не запуск: `soffice` выходил с кодом 1 и одной строкой «ERROR: /proc not
# mounted», а человек видел мёртвый ярлык — тап не открывал ничего и ничего не
# говорил. Поэтому здесь ярлык нажимается пальцем через экран телефона, а окно
# ищется по номеру процесса, как того требует R01.1.
#
# Что доказывается на каждом устройстве:
#
#   R01.1 — ярлыки Writer, Calc и Impress на столе есть, и каждый ОТКРЫВАЕТСЯ:
#           после двойного тапа по значку на экране телефона на столе появляется
#           окно нужного класса, его номер процесса принадлежит живому soffice.bin,
#           окно на месте через 15 секунд, снимок экрана сохранён.
#
#   R01.2 — интерфейс офиса русский: заголовок окна Writer, снятый `wmctrl`,
#           содержит кириллицу.
#
# Окна опознаются по КЛАССУ (`wmctrl -lx`), а не по заголовку: образ русский, и
# заголовок зависит от языка. Кириллица в заголовке — это отдельная проверка R01.2,
# а не способ найти окно.
#
# Тапы не слепые: место ярлыка выводится из раскладки xfdesktop и снимка экрана
# (tests/e2e/store-points.py), прямоугольник стола на экране — из дерева окон Android.
#
# ВАЖНО про доказательство. Программы запускает САМ стол, поднятый приложением, а
# не эта проверка: тап уходит в приложение, оттуда в xfdesktop, и офис оказывается
# внутри той сессии proot, которую открыло приложение. Своя запускалка образа ниже
# (IMAGE_ENTER) нужна только чтобы посмотреть на стол снаружи — wmctrl, ps, ls — и
# она НАМЕРЕННО повторяет привязки приложения без подмены /proc: подмени она их у
# себя, проверка доказывала бы собственную обвязку, а не раздаваемый APK.
#
# Что здесь красное: нет устройства, в образе нет soffice, стол не поднялся, ярлыка
# на снимке не найти, после тапов окна нет, окно умерло за 15 секунд, заголовок
# Writer без кириллицы.
#
# Использование:
#   bash tests/e2e/office-on-device.sh                 все устройства с развёрнутым столом
#   bash tests/e2e/office-on-device.sh --only writer   одна программа (быстрее)
#
# Переменные: STOL_E2E_ADB, STOL_OFFICE_SERIAL (одно устройство),
# STOL_OFFICE_ALIVE (сколько секунд окно обязано прожить, по умолчанию 15),
# STOL_OFFICE_WINDOW_LIMIT (сколько ждать окно после тапа), STOL_OFFICE_TAPS,
# STOL_OFFICE_NO_RESTART=1 (не поднимать стол начисто — для отладки).

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="${REPO}/tests/e2e"
ART="${HERE}/artifacts"
PKG="ru.codoceh.stol"

ADB="${STOL_E2E_ADB:-${HOME}/Library/Android/sdk/platform-tools/adb}"
ALIVE="${STOL_OFFICE_ALIVE:-15}"
DESKTOP_LIMIT="${STOL_OFFICE_DESKTOP_LIMIT:-600}"
WINDOW_LIMIT="${STOL_OFFICE_WINDOW_LIMIT:-120}"
TAPS="${STOL_OFFICE_TAPS:-3}"
NO_RESTART="${STOL_OFFICE_NO_RESTART:-}"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

# Программа → ярлык на столе и класс окна. Класс берётся из StartupWMClass той же
# записи .desktop, что лежит на столе.
APPS="writer calc impress"
entry_of() { printf '/root/Desktop/libreoffice-%s.desktop' "$1"; }
class_of() { printf 'libreoffice.libreoffice-%s' "$1"; }

ONLY=""
while [ $# -gt 0 ]; do
    case "$1" in
        --only) shift; ONLY="${1:-}"; [ -n "${ONLY}" ] || die "--only без имени программы" ;;
        -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
        *) die "неизвестный ключ: $1" ;;
    esac
    shift
done
if [ -n "${ONLY}" ]; then
    case " ${APPS} " in
        *" ${ONLY} "*) APPS="${ONLY}" ;;
        *) die "не знаю программы «${ONLY}» — есть writer, calc, impress" ;;
    esac
fi

command -v "${ADB}" >/dev/null 2>&1 || die "не найден adb (${ADB}).
Поставьте platform-tools из Android SDK или укажите путь в STOL_E2E_ADB."
command -v python3 >/dev/null 2>&1 || die "не найден python3 — снимки и деревья окон разбирать нечем"
python3 -c "import PIL, numpy" >/dev/null 2>&1 \
    || die "у python3 нет Pillow или numpy — место ярлыка на снимке вывести нечем"
[ -f "${HERE}/store-points.py" ] || die "нет ${HERE}/store-points.py — координаты тапа выводить нечем"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/office-on-device.XXXXXX")"
SERIAL=""
NATIVE=""

office_cleanup() {
    [ -n "${SERIAL}" ] || return 0
    "${ADB}" -s "${SERIAL}" shell "run-as ${PKG} rm -f files/office-enter.sh files/rootfs/tmp/office-cmd.sh" >/dev/null 2>&1
    return 0
}
trap 'office_cleanup; rm -rf "${TMP}"' EXIT
mkdir -p "${ART}"

# ------------------------------------------------------------------ устройства

declare -a SERIALS=() SDKS=() RELS=() AVDS=() READY=()

collect_devices() {
    local serial state rest
    declare -a found=()
    # Список собирается ЦЕЛИКОМ и только потом обходится: adb, вызванный внутри цикла
    # по потоку, дочитывает этот же поток за себя.
    while read -r serial state rest; do
        [ "${state}" = "device" ] && found+=("${serial}")
    done < <("${ADB}" devices | tail -n +2)

    local sdk rel avd ready
    for serial in ${found[@]+"${found[@]}"}; do
        [ "$("${ADB}" -s "${serial}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || {
            bad "${serial}: система ещё не загрузилась"
            continue
        }
        sdk="$("${ADB}" -s "${serial}" shell getprop ro.build.version.sdk | tr -d '\r')"
        rel="$("${ADB}" -s "${serial}" shell getprop ro.build.version.release | tr -d '\r')"
        avd="$("${ADB}" -s "${serial}" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
        [ -n "${avd}" ] || avd="${serial}"
        ready="нет"
        case "$("${ADB}" -s "${serial}" shell run-as "${PKG}" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')" in
            *".stol-ready") ready="да" ;;
        esac
        SERIALS+=("${serial}"); SDKS+=("${sdk}"); RELS+=("${rel}"); AVDS+=("${avd}"); READY+=("${ready}")
        ok "${serial}: ${avd}, Android ${rel} (API ${sdk}), образ развёрнут: ${ready}"
    done
}

log "1. Ищем устройства"
collect_devices
[ "${#SERIALS[@]}" -ge 1 ] || die "не подключено ни одного живого устройства — доказывать не на чем.
Поднимите эмулятор и пройдите первую установку:
  ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api35 -no-snapshot -no-audio &
  bash tests/e2e/two-devices.sh"

declare -a TARGETS=()
for i in "${!SERIALS[@]}"; do
    [ "${READY[$i]}" = "да" ] || continue
    if [ -n "${STOL_OFFICE_SERIAL:-}" ] && [ "${SERIALS[$i]}" != "${STOL_OFFICE_SERIAL}" ]; then
        continue
    fi
    TARGETS+=("${i}")
done
[ "${#TARGETS[@]}" -ge 1 ] || die "ни на одном устройстве образ не развёрнут — офис проверять негде.
Пройдите первую установку: bash tests/e2e/two-devices.sh"

# ------------------------------------------------------------------ работа с образом

# Запускалка команд внутри образа для ОСМОТРА стола снаружи: wmctrl, ps, cat. Привязки
# повторяют ProotSession БЕЗ подмены /proc — офис здесь не запускается, его запускает
# сам стол, и подмена в этой обвязке доказывала бы не то, что раздаётся.
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

# Кладёт файл в каталог данных приложения. Через base64: кавычки и переводы строк
# команда через adb не переживает.
put_file() {
    local path="$1" body="$2" b64
    b64="$(printf '%s\n' "${body}" | base64 | tr -d '\n')"
    "${ADB}" -s "${SERIAL}" shell "run-as ${PKG} sh -c 'echo ${b64} | base64 -d > ${path}'" >/dev/null 2>&1
}

# Команда внутри образа. Едет файлом, а не строкой в аргументах: adb склеивает
# аргументы обратно в строку и теряет вложенные кавычки.
in_image() {
    put_file "files/rootfs/tmp/office-cmd.sh" "$1"
    "${ADB}" -s "${SERIAL}" shell "run-as ${PKG} sh files/office-enter.sh '${NATIVE}' office-cmd.sh" 2>&1 | tr -d '\r'
}

# Готовность стола — подключение к маячку 5910, и только оно.
beacon_open() {
    "${ADB}" -s "$1" shell 'echo x | nc -w 1 127.0.0.1 5910 >/dev/null 2>&1 && echo OPEN || echo SHUT' \
        2>/dev/null | tr -d '\r' | grep -q OPEN
}

wait_desktop() {
    local serial="$1" limit="$2" waited=0
    beacon_open "${serial}" && { step "стол уже поднят"; return 0; }
    "${ADB}" -s "${serial}" shell am start -n "${PKG}/.MainActivity" >/dev/null 2>&1
    while [ "${waited}" -lt "${limit}" ]; do
        sleep 5
        waited=$((waited + 5))
        beacon_open "${serial}" && { step "стол поднялся за ${waited} с"; return 0; }
    done
    return 1
}

# Точка стола → точка экрана.
dev_x() { python3 -c "print(int(round(${OFF_X} + $1 * ${SCALE})))"; }
dev_y() { python3 -c "print(int(round(${OFF_Y} + $1 * ${SCALE})))"; }

# Как приложение вписывает стол в экран телефона: размер стола у X-сервера,
# прямоугольник — из дерева окон Android (врезки полос читаются по-разному на 11 и 15).
office_geometry() {
    DESK_W="$(in_image 'xdpyinfo > /tmp/dpy.txt 2>&1; sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\1/p" /tmp/dpy.txt')"
    DESK_H="$(in_image 'sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\2/p" /tmp/dpy.txt')"
    DESK_W="$(printf '%s' "${DESK_W}" | tr -d ' \n')"
    DESK_H="$(printf '%s' "${DESK_H}" | tr -d ' \n')"
    [[ "${DESK_W}" =~ ^[0-9]+$ ]] && [[ "${DESK_H}" =~ ^[0-9]+$ ]] \
        || die "стол не назвал свой размер (xdpyinfo вернул «${DESK_W}×${DESK_H}») — координаты выводить не из чего"

    "${ADB}" -s "${SERIAL}" shell rm -f /sdcard/stol-frame.xml >/dev/null 2>&1
    "${ADB}" -s "${SERIAL}" shell uiautomator dump /sdcard/stol-frame.xml >/dev/null 2>&1
    "${ADB}" -s "${SERIAL}" shell cat /sdcard/stol-frame.xml > "${TMP}/frame.xml" 2>/dev/null
    [ -s "${TMP}/frame.xml" ] || die "дерево окон Android не снялось — куда приложение вписало стол, узнать нечем"

    local map
    map="$(python3 "${HERE}/store-points.py" frame --dump "${TMP}/frame.xml" \
        --desk-width "${DESK_W}" --desk-height "${DESK_H}" --package "${PKG}" 2>&1)" \
        || die "прямоугольник стола на экране не выведен: $(sed -n 's/^ОШИБКА=//p' <<<"${map}")"
    SCALE="$(sed -n 's/^SCALE=//p' <<<"${map}")"
    OFF_X="$(sed -n 's/^OFFSET_X=//p' <<<"${map}")"
    OFF_Y="$(sed -n 's/^OFFSET_Y=//p' <<<"${map}")"

    PANEL_TOP="$(in_image 'wmctrl -lG -x > /tmp/wg.txt 2>&1; grep -i xfce4-panel /tmp/wg.txt' \
        | sort -k6 -n -r | head -1 | awk '{print $4}')"
    [[ "${PANEL_TOP}" =~ ^[0-9]+$ ]] || PANEL_TOP="${DESK_H}"
    step "стол ${DESK_W}×${DESK_H} вписан в вид $(sed -n 's/^VIEW=//p' <<<"${map}"), масштаб ${SCALE}, панель с ${PANEL_TOP}"
}

# Список окон одной строкой на окно: id, стол, номер процесса, класс, узел, заголовок.
window_list() {
    in_image 'wmctrl -lpx > /tmp/wl.txt 2>&1; cat /tmp/wl.txt'
}

# Окно нужного класса: печатает «id<таб>процесс<таб>заголовок», пусто — окна нет.
window_of_class() {
    local class="$1"
    window_list | awk -v c="${class}" '$4 == c { id = $1; pid = $3; $1=$2=$3=$4=$5=""; sub(/^ +/, ""); printf "%s\t%s\t%s\n", id, pid, $0; exit }'
}

# Гасит офис целиком и ждёт, пока его окна пропадут со стола.
office_stop() {
    in_image 'pkill -f soffice.bin
pkill -f oosplash
exit 0' >/dev/null 2>&1
    local waited=0
    while [ "${waited}" -lt 30 ]; do
        window_list > "${TMP}/wl.txt"
        grep -qi 'libreoffice-' "${TMP}/wl.txt" || return 0
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

# ------------------------------------------------------------------ одна программа

# Место ярлыка на столе. Снимков может понадобиться несколько: xfdesktop дорисовывает
# значки не сразу, и первый снимок застаёт полупустой стол.
icon_point() {
    local entry="$1" shot="$2" point="" try
    for try in 1 2 3 4 5 6; do
        "${ADB}" -s "${SERIAL}" exec-out screencap -p > "${shot}" 2>/dev/null
        [ -s "${shot}" ] || die "снимок экрана телефона не снялся — доказывать нечем"
        in_image 'cat /root/.config/xfce4/desktop/icons.screen0.yaml' > "${TMP}/icons.yaml"
        [ -s "${TMP}/icons.yaml" ] \
            || die "xfdesktop не назвал раскладку значков (icons.screen0.yaml пуст) — место ярлыка выводить не из чего"
        point="$(python3 "${HERE}/store-points.py" icon \
            --shot "${shot}" --icons "${TMP}/icons.yaml" --entry "${entry}" \
            --scale "${SCALE}" --offset-x "${OFF_X}" --offset-y "${OFF_Y}" \
            --desk-width "${DESK_W}" --desk-height "${DESK_H}" --panel-top "${PANEL_TOP}")" && {
            printf '%s' "${point}"; return 0
        }
        step "снимок ${try}: $(sed -n 's/^ОШИБКА=//p' <<<"${point}")"
        point=""
        sleep 5
    done
    return 1
}

check_app() {
    local app="$1" tag="$2" report="$3"
    local entry class shot
    entry="$(entry_of "${app}")"
    class="$(class_of "${app}")"
    shot="${ART}/office-${app}-${tag}.png"

    log "   ${app}: ярлык ${entry}"

    in_image "test -f ${entry} && echo ЕСТЬ || echo НЕТ" | grep -q ЕСТЬ \
        || die "на столе нет ярлыка ${entry} — открывать нечего (R01.1)"

    office_stop || die "прошлый запуск офиса со стола не убрать — новое окно от старого не отличить"
    [ -z "$(window_of_class "${class}")" ] \
        || die "окно класса ${class} на столе уже есть до тапа — открытие ярлыка ничего не докажет"

    local point
    point="$(icon_point "${entry}" "${shot}")" \
        || die "место ярлыка ${app} на столе не выведено ни по одному из шести снимков.
Снимок: ${shot}"
    local icon_x icon_y
    icon_x="$(sed -n 's/^DESK_X=//p' <<<"${point}")"
    icon_y="$(sed -n 's/^DESK_Y=//p' <<<"${point}")"
    ok "ярлык ${app}: строка $(sed -n 's/^ROW=//p' <<<"${point}"), столбец $(sed -n 's/^COLUMN=//p' <<<"${point}"), шаг сетки $(sed -n 's/^STEP=//p' <<<"${point}") — точка стола (${icon_x}, ${icon_y})"

    local tap_x tap_y
    tap_x="$(dev_x "${icon_x}")"; tap_y="$(dev_y "${icon_y}")"

    # Разогревочный тап по пустому углу стола: первое касание после запуска стола
    # приложение забирает себе на фокус ввода, и до стола оно не доходит.
    step "разогрев: тап по пустому углу стола"
    "${ADB}" -s "${SERIAL}" shell "input tap $(dev_x $((DESK_W - 40))) $(dev_y $((PANEL_TOP - 40)))" >/dev/null 2>&1
    sleep 3

    # Двойной тап собирается вручную: два `input tap` подряд идут 1,2–1,7 с, каждый
    # поднимает свою виртуальную машину, и стол видит два отдельных щелчка.
    local found="" try waited
    for try in $(seq 1 "${TAPS}"); do
        step "тапаем дважды по ярлыку в точке экрана (${tap_x}, ${tap_y}), попытка ${try}"
        "${ADB}" -s "${SERIAL}" shell "input tap ${tap_x} ${tap_y} & sleep 0.25; input tap ${tap_x} ${tap_y}; wait" >/dev/null 2>&1
        waited=0
        while [ "${waited}" -lt "${WINDOW_LIMIT}" ]; do
            sleep 5
            waited=$((waited + 5))
            found="$(window_of_class "${class}")"
            [ -n "${found}" ] && break
        done
        [ -n "${found}" ] && { step "окно появилось за ${waited} с"; break; }
        window_list > "${TMP}/wl.txt"
        if grep -q "Ошибка запуска" "${TMP}/wl.txt"; then
            in_image 'wmctrl -l > /tmp/wl2.txt 2>&1
for id in $(awk "/Ошибка запуска/ {print \$1}" /tmp/wl2.txt); do wmctrl -i -c $id; done
exit 0' >/dev/null 2>&1
            die "стол ответил на ярлык ${app} окном «Ошибка запуска» — ярлык не открывается.
Так бывает, когда в сессии стола нет службы файлового менеджера (см. image/start-gui.sh)."
        fi
    done
    if [ -z "${found}" ]; then
        local said
        said="$(in_image "ls -1 /root/.config/libreoffice 2>/dev/null; soffice --version > /tmp/sv.txt 2>&1; cat /tmp/sv.txt" | tr '\n' ' ')"
        die "после ${TAPS} двойных тапов окна класса ${class} на столе нет — ярлык ${app} мёртв.
Что говорит сам офис в образе: ${said}
Снимок: ${shot}"
    fi

    local win pid title
    IFS=$'\t' read -r win pid title <<<"${found}"
    ok "окно ${win} найдено, номер процесса ${pid}, заголовок: ${title}"

    # Номер процесса обязан принадлежать живому офису, а не случайному окну.
    local owner
    owner="$(in_image "ps -o args= -p ${pid} > /tmp/own.txt 2>&1; cat /tmp/own.txt" | tr -d '\r' | tr '\n' ' ')"
    case "${owner}" in
        *soffice*) ok "процесс ${pid} — это офис: ${owner}" ;;
        *) die "окно ${win} числится за процессом ${pid}, а это не офис: ${owner}" ;;
    esac

    step "проверяем, что окно живо через ${ALIVE} с"
    sleep "${ALIVE}"
    local still
    still="$(window_of_class "${class}")"
    [ -n "${still}" ] && [ "$(cut -f1 <<<"${still}")" = "${win}" ] \
        || die "через ${ALIVE} с окна ${app} на столе не осталось — офис не живёт, а мигает"
    ok "через ${ALIVE} с окно ${win} на месте"

    "${ADB}" -s "${SERIAL}" exec-out screencap -p > "${shot}" 2>/dev/null
    [ -s "${shot}" ] || die "снимок с открытым окном ${app} не снялся"
    ok "снимок: ${shot}"

    # R01.2 — заголовок Writer обязан быть русским.
    if [ "${app}" = "writer" ]; then
        python3 -c "
import sys
title = sys.argv[1]
sys.exit(0 if any('А' <= ch <= 'я' or ch in 'Ёё' for ch in title) else 1)
" "${title}" \
            || die "заголовок окна Writer «${title}» без кириллицы — интерфейс офиса не русский (R01.2)"
        ok "заголовок Writer русский: ${title} (R01.2)"
    fi

    {
        printf '%-9s окно %s, процесс %s, живо %s с, заголовок: %s\n' \
            "${app}" "${win}" "${pid}" "${ALIVE}" "${title}"
        printf '%-9s ярлык в точке стола (%s, %s) → точка экрана (%s, %s), снимок %s\n' \
            "" "${icon_x}" "${icon_y}" "${tap_x}" "${tap_y}" "$(basename "${shot}")"
    } >> "${report}"

    office_stop >/dev/null 2>&1
    return 0
}

# ------------------------------------------------------------------ одно устройство

check_device() {
    local i="$1"
    SERIAL="${SERIALS[$i]}"
    local sdk="${SDKS[$i]}" avd="${AVDS[$i]}" rel="${RELS[$i]}"
    local tag="${sdk}-${avd}"
    local report="${ART}/office-${tag}.txt"

    log "2. Офис на устройстве ${avd} (${SERIAL}), Android ${rel}"

    local apk_path
    apk_path="$("${ADB}" -s "${SERIAL}" shell pm path "${PKG}" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
    [ -n "${apk_path}" ] || die "на устройстве ${SERIAL} нет приложения — проверять нечего"
    NATIVE="$(dirname "${apk_path}")/lib/arm64"
    put_file "files/office-enter.sh" "${IMAGE_ENTER}"

    # Стол поднимаем начисто: на пожившем столе ярлыки перестают отзываться на тап
    # (см. AGENTS.md), и это чужой отказ, а не отказ офиса.
    if [ -z "${NO_RESTART}" ]; then
        step "поднимаем стол начисто: гасим приложение и запускаем заново"
        "${ADB}" -s "${SERIAL}" shell am force-stop "${PKG}" >/dev/null 2>&1
        sleep 5
    fi
    wait_desktop "${SERIAL}" "${DESKTOP_LIMIT}" \
        || die "стол на устройстве ${SERIAL} не поднялся за ${DESKTOP_LIMIT} с — офис проверять негде"
    in_image 'uname -m' | grep -q aarch64 || die "в образ не войти — proot из каталога библиотек APK не запустился"

    in_image 'test -x /usr/bin/soffice && echo ЕСТЬ || echo НЕТ' | grep -q ЕСТЬ \
        || die "в образе нет /usr/bin/soffice — офис не поставился на сборке (R01.4)"
    ok "в образе есть /usr/bin/soffice"

    office_geometry

    {
        echo "устройство: ${SERIAL}"
        echo "эмулятор:   ${avd}"
        echo "Android:    ${rel} (API ${sdk})"
        echo "снято:      $(date '+%Y-%m-%d %H:%M:%S %Z')"
    } > "${report}"

    local app
    for app in ${APPS}; do
        check_app "${app}" "${tag}" "${report}"
    done
    ok "отчёт: ${report}"
    return 0
}

for i in "${TARGETS[@]}"; do
    check_device "${i}"
done

echo
printf '\033[32mЗЕЛЁНО\033[0m  офис открывается тапом по ярлыку на живом Android, окна живут, заголовок Writer русский (R01.1, R01.2, R16D)\n'
exit 0
