#!/usr/bin/env bash
#
# tests/e2e/desktop-endurance.sh — стол остаётся рабочим со временем (R15D).
#
# Жалоба, ради которой это написано: на пожившем столе ярлыки перестают открываться
# вовсе. Щелчок доходит до панели — меню «Пуск» открывается, — а значок на столе на
# двойной тап не отзывается ничем: ни выделением, ни окном, ни ошибкой. На столе,
# поднятом только что, тот же тап в ту же точку открывает программу. Для человека это
# значит: поработал час — половина стола умерла.
#
# Здесь это ловится на живом устройстве в три шага:
#
#   1. СВЕЖИЙ СТОЛ  — стол поднимается начисто, и ярлык открывается с ПЕРВОГО двойного
#                     тапа, без разогревочного касания по пустому месту. Разогрев здесь
#                     запрещён намеренно: человек не знает, что первое касание надо
#                     потратить впустую, и для него потерянный тап — тот же отказ.
#
#   2. РАБОТА       — со столом работают так, как работал бы человек: открывают окно,
#                     двигают его, закрывают, открывают «Пуск» и закрывают. После этого
#                     стол выдерживается: по умолчанию 15 минут, столько же, сколько
#                     прошло до отказа в разборе (STOL_ENDURANCE_MINUTES).
#
#   1а. ПАЛЕЦ     — пороги двойного щелчка на устройстве те же, что в
#                     `reference/xfconf/xsettings.txt`; пара щелчков с человеческими
#                     зазором и смещением ярлык открывает; десять двойных тапов подряд
#                     дают десять открытий с ПЕРВОГО раза.
#
#   1в. ПАЛЕЦ И ЧАСЫ — короткое касание обязано остаться ЛЕВЫМ щелчком: после ряда тапов
#                     указатель свободен, меню стола никто не открывал. И наоборот —
#                     нарочное удержание обязано меню открыть. Пока удержание мерилось
#                     будильником приложения, под нагрузкой короткий тап становился
#                     правым щелчком, меню забирало указатель, и тап «пропадал».
#
#   1б. СЛУЖБА ЗАПУСКА — состояние отказа, вызванное по своей воле: со стола снимается
#                     служба файлового менеджера (org.xfce.FileManager), которой
#                     xfdesktop отдаёт запуск `.desktop`. Следующий двойной тап обязан
#                     открыть ярлык: службу поднимает сама шина. Пока окружения у шины
#                     не было, она этого не могла — и стол отвечал «Ошибка запуска» на
#                     каждый щелчок до перезапуска приложения.
#
#   3. ПОСЛЕ РАБОТЫ — тот же ярлык в ту же точку тапается снова. Он обязан открыться.
#
# Координаты не вписаны: место ярлыка выводится из раскладки xfdesktop и снимка экрана,
# прямоугольник стола — из дерева окон Android (tests/e2e/store-points.py).
#
#   4. ЗАКЛИНЕННАЯ КНОПКА — состояние, в котором отказ воспроизводится по своей воле,
#                     а не по случаю: второй клиент RFB нажимает кнопку и не отпускает
#                     её (tests/e2e/click-path.py wedge). X-сервер делает событие только
#                     на ИЗМЕНЕНИЕ бита маски и, пока кнопка нажата, держит указатель
#                     захваченным окном, в котором её нажали. Снаружи это и есть жалоба:
#                     тап уезжает в панель, стол не получает ничего. Здесь требуется,
#                     чтобы щелчок пришёл туда же, куда курсор, — в ярлык, а не в панель.
#
# ЧТО ЗДЕСЬ ИЗМЕРЕНО, А ЧТО НЕТ. Путь щелчка теперь виден: `tests/e2e/click-path.py`
# спрашивает сам X-сервер, какие кнопки он считает нажатыми, свободен ли указатель и
# какое окно лежит под курсором. Живыми прогонами на обоих эмуляторах установлено:
# оба тапа двойного тапа доходят до сервера всегда, промежуток между ними 40–305 мс при
# окне двойного щелчка XFCE в 400 мс, маска кнопок при нажатии пустая. То есть ни потеря
# тапа, ни промах по времени, ни залипание в обычной работе отказ не объясняют — они
# исключены измерением. Заклиненная кнопка отказ объясняет полностью и снимается только
# разрывом связи RFB, то есть перезапуском приложения, — как в жалобе.
#
# ПРИЧИНА ОТКАЗА НАЙДЕНА, И ОНА НЕ В ПЕРЕДАЧЕ. Ярлык открывается двойным щелчком, а парой
# два щелчка считает GTK по двум порогам: окну времени (`Net/DoubleClickTime`) и окну
# смещения (`Net/DoubleClickDistance`). Живым замером на столе граница названа числами:
# при мышиных 400 мс и 5 точках пара с зазором 350 мс открывала ярлык 3 раза из 3, с
# зазором 450 мс — 0 из 3; смещение второго щелчка на 5 точек — 3 из 3, на 8 точек — 0 из 3.
# Ловушкой щелчков снято распределение настоящего пути (adb → приложение → RFB → X): все
# 40 нажатий доехали, зазор 114–488 мс при медиане 240, и 1 пара из 20 вышла за 400 мс —
# ровно та доля тапов, что «не открывались». Пороги подняты до 1000 мс и 32 точек
# (`reference/xfconf/xsettings.txt`), и на обоих эмуляторах стало 10 открытий из 10.
#
# ВТОРОЙ СЛОЙ ОТКАЗА НАЙДЕН, И ОН НЕ В ЩЕЛЧКЕ, А В ЗАПУСКЕ. Ярлык открывает не
# xfdesktop: `.desktop` он отдаёт службе org.xfce.FileManager на сессионной шине, а
# держит эту службу единственный `thunar --daemon`, поднятый start-gui.sh один раз за
# жизнь стола. Служба ушла — и каждый двойной щелчок по ЛЮБОМУ ярлыку кончается окном
# «Ошибка запуска», потому что поднять её заново некому. Снято живьём на Android 15:
# `pkill -f "thunar --daemon"` — и десять двойных тапов подряд дали десять отказов, а
# окно ошибки назвало место словами шины:
# «GDBus.Error:org.freedesktop.DBus.Error.Spawn.ChildExited: Process org.xfce.FileManager
# exited with status 1». Шина службу поднять пыталась (файл
# /usr/share/dbus-1/services/org.xfce.FileManager.service), но запускает она службы в
# СВОЁМ окружении, а в нём не было ни DISPLAY, ни адреса самой шины: `Thunar
# --gapplication-service`, запущенный так, выходит с кодом 1 на «cannot open display».
# Лечение — выдать шине окружение стола ДО её запуска (image/start-gui.sh); после него
# тот же снос службы лечится сам собой: следующий тап открыл магазин за 3 с, а ряд из
# десяти тапов дал десять открытий.
#
# ЧЕГО ЭТА ПРОВЕРКА ПОКА НЕ ЛОВИТ. Откуда кнопка залипает в живой работе, не найдено:
# ведение пальцем, уход из приложения посреди ведения и долгое нажатие маску не оставляют
# (проверено). Поэтому шаги 1–3 остаются ловушкой — зелёное в них значит «в этот раз стол
# выдержал», а не «отказа больше нет», — и при отказе они теперь снимают состояние пути
# щелчка, чтобы отказ разбирали по числам, а не по снимку экрана.
#
# Что здесь красное: нет устройства, стол не поднялся, ярлык не открылся на свежем
# столе, ярлык перестал открываться после работы. При отказе снимается снимок экрана,
# список окон и состояние частей стола — чтобы отказ можно было разбирать, а не гадать.
#
# Использование:
#   bash tests/e2e/desktop-endurance.sh                вся проверка
#   STOL_ENDURANCE_MINUTES=2 bash tests/e2e/desktop-endurance.sh    короткая выдержка
#
# Переменные: STOL_E2E_ADB, STOL_ENDURANCE_SERIAL (устройство), STOL_ENDURANCE_MINUTES
# (выдержка, по умолчанию 15), STOL_ENDURANCE_DESKTOP_LIMIT (сколько ждать стол).

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"
PKG="ru.codoceh.stol"
ENTRY="/root/Desktop/codoceh-store.desktop"
TITLE="Магазин КодоЦех"

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
MINUTES="${STOL_ENDURANCE_MINUTES:-15}"
DESKTOP_LIMIT="${STOL_ENDURANCE_DESKTOP_LIMIT:-600}"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

case "$#" in
    0) ;;
    *) case "$1" in
           -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
           *) die "неизвестный ключ: $1" ;;
       esac ;;
esac

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb (${ADB}).
Поставьте platform-tools из Android SDK или укажите путь в STOL_E2E_ADB."
command -v python3 >/dev/null 2>&1 || die "не найден python3 — снимки и деревья окон разбирать нечем"
python3 -c "import PIL, numpy" >/dev/null 2>&1 \
    || die "у python3 нет Pillow или numpy — место ярлыка на снимке вывести нечем"
[ -f "$HERE/store-points.py" ] || die "нет $HERE/store-points.py — координаты тапа выводить нечем"
[ -f "$HERE/click-path.py" ] || die "нет $HERE/click-path.py — путь щелчка смотреть нечем"
[[ "$MINUTES" =~ ^[0-9]+$ ]] || die "STOL_ENDURANCE_MINUTES=${MINUTES} — это не число минут"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/desktop-endurance.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$ART"

# ------------------------------------------------------------------ устройство

# Список собирается ЦЕЛИКОМ и только потом обходится: adb, вызванный внутри цикла по
# потоку, дочитывает этот же поток за себя, и все устройства кроме первого пропадают.
declare -a FOUND=()
while read -r serial state _; do
    [ "$state" = "device" ] && FOUND+=("$serial")
done < <("$ADB" devices | tail -n +2)

SERIAL=""
for serial in ${FOUND[@]+"${FOUND[@]}"}; do
    [ -z "${STOL_ENDURANCE_SERIAL:-}" ] || [ "$serial" = "$STOL_ENDURANCE_SERIAL" ] || continue
    [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || continue
    case "$("$ADB" -s "$serial" shell run-as "$PKG" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')" in
        *".stol-ready") SERIAL="$serial"; break ;;
    esac
done
[ -n "$SERIAL" ] || die "нет устройства с развёрнутым столом${STOL_ENDURANCE_SERIAL:+ среди названных в STOL_ENDURANCE_SERIAL (${STOL_ENDURANCE_SERIAL})}.
Поставьте свежий APK и пройдите первую установку: bash tests/e2e/two-devices.sh"

SDK="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
REL="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.release | tr -d '\r')"
AVD="$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
[ -n "$AVD" ] || AVD="$SERIAL"
TAG="${SDK}-${AVD}"

APK_PATH="$("$ADB" -s "$SERIAL" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
[ -n "$APK_PATH" ] || die "на устройстве ${SERIAL} нет приложения — проверять нечего"
NATIVE="$(dirname "$APK_PATH")/lib/arm64"

# ------------------------------------------------------------------ работа с образом

# Кладёт файл в каталог данных приложения. Через base64: кавычки и переводы строк
# команда через adb не переживает.
put_file() {
    local path="$1" body="$2" b64
    b64="$(printf '%s\n' "$body" | base64 | tr -d '\n')"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'echo $b64 | base64 -d > $path'" >/dev/null 2>&1
}

# Запускалка команд внутри образа: повторяет то, что делает приложение (ProotSession).
# Шина берётся та же, на которой живёт стол.
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
    put_file "files/rootfs/tmp/endurance-cmd.sh" "$1"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/endurance-enter.sh '$NATIVE' endurance-cmd.sh" 2>&1 | tr -d '\r'
}

# Готовность стола — подключение к маячку 5910, и только оно: сокеты программ внутри
# образа из /proc/net/tcp под adb не видны, а к 5901 подключаться нельзя вовсе.
beacon_open() {
    "$ADB" -s "$SERIAL" shell 'echo x | nc -w 1 127.0.0.1 5910 >/dev/null 2>&1 && echo OPEN || echo SHUT' \
        2>/dev/null | tr -d '\r' | grep -q OPEN
}

# Точка стола → точка экрана.
dev_x() { python3 -c "print(int(round($OFF_X + $1 * $SCALE)))"; }
dev_y() { python3 -c "print(int(round($OFF_Y + $1 * $SCALE)))"; }

# Двойной тап приходится собирать вручную. Два `input tap` подряд идут 1,2–1,7 с: каждый
# поднимает свою виртуальную машину, и стол видит два отдельных щелчка, а не двойной —
# окно двойного щелчка в XFCE 400 мс. Первый запускается в стороне, второй через
# четверть секунды.
double_tap() {
    "$ADB" -s "$SERIAL" shell "input tap $1 $2 & sleep 0.25; input tap $1 $2; wait" >/dev/null 2>&1
}

# Что X-сервер думает о щелчке прямо сейчас: маска нажатых кнопок, захвачен ли
# указатель и какое окно лежит под курсором. Без этого отказ разбирать нечем: на снимке
# экрана ничего из этого не видно.
click_state() {
    in_image 'python3 /root/click-path.py state'
}

# Одной строкой — для журнала прогона.
click_line() {
    local state
    state="$(click_state)"
    printf 'курсор (%s, %s), нажаты: %s, указатель %s, окно под курсором %s' \
        "$(sed -n 's/^КУРСОР_X=//p' <<<"${state}")" \
        "$(sed -n 's/^КУРСОР_Y=//p' <<<"${state}")" \
        "$(sed -n 's/^НАЖАТЫ=//p' <<<"${state}")" \
        "$(sed -n 's/^ЗАХВАТ_СЛОВОМ=//p' <<<"${state}")" \
        "$(sed -n 's/^ОКНО=//p' <<<"${state}")"
}

# Есть ли на столе окно с таким заголовком.
window_here() {
    in_image 'wmctrl -l' | grep -q "$1"
}

# Ждёт окно магазина до предела в секундах. Спать вслепую нельзя: на программной
# отрисовке окно иногда появляется на 12-й секунде, и «не открылось» вместо «открылось
# медленно» — это ложное красное, а не дефект.
wait_store() {
    local limit="${1:-30}" waited=0
    STORE_WAITED=0
    while [ "$waited" -lt "$limit" ]; do
        if window_here "$TITLE"; then
            STORE_WAITED="$waited"
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
    STORE_WAITED="$waited"
    return 1
}
STORE_WAITED=0

# Что видно в момент отказа: снимок, окна, части стола. Без этого красное неразбираемо.
evidence() {
    local why="$1" shot="$ART/endurance-${TAG}-${2}.png"
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$shot" 2>/dev/null
    {
        echo "устройство: ${SERIAL} (${AVD}), Android ${REL} (API ${SDK})"
        echo "снято:      $(date '+%Y-%m-%d %H:%M:%S %Z')"
        echo "причина:    ${why}"
        echo
        echo "части стола:"
        in_image 'ps -eo pid,stat,etime,rss,comm | grep -E "Xtigervnc|xfwm4|xfsettingsd|xfce4-panel|xfdesktop|thunar|dbus-daemon" | grep -v grep'
        echo
        echo "окна стола:"
        in_image 'wmctrl -lGx'
        echo
        echo "хвост журнала стола:"
        in_image 'tail -15 /run/stol/start.log'
    } > "$ART/endurance-${TAG}-${2}.txt" 2>&1
    step "снимок: ${shot}"
    step "разбор: $ART/endurance-${TAG}-${2}.txt"
}

# Закрыть окно магазина, если оно осталось: следующий тап должен открывать своё окно,
# а не находить чужое.
close_store() {
    in_image "for id in \$(wmctrl -l | grep '$TITLE' | cut -d' ' -f1); do wmctrl -i -c \$id; done
pkill -f codoceh-store.py
exit 0" >/dev/null 2>&1
    sleep 3
}

# ------------------------------------------------------------------ 1. свежий стол

log "1. Свежий стол: поднимаем начисто на ${AVD} (${SERIAL}), Android ${REL}"
put_file "files/endurance-enter.sh" "$IMAGE_ENTER"
put_file "files/rootfs/root/click-path.py" "$(cat "$HERE/click-path.py")"

"$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 5
"$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
WAITED=0
while [ "$WAITED" -lt "$DESKTOP_LIMIT" ]; do
    sleep 5
    WAITED=$((WAITED + 5))
    beacon_open && break
done
beacon_open || die "стол на устройстве ${SERIAL} не поднялся за ${DESKTOP_LIMIT} с — выдерживать нечего"
step "стол поднялся за ${WAITED} с"
in_image 'uname -m' | grep -q aarch64 || die "в образ не войти — proot из каталога библиотек APK не запустился"

# Чистое поле: чужого окна магазина на столе быть не должно.
close_store
window_here "$TITLE" && die "на столе уже висит окно «${TITLE}» — новое от старого не отличить"

# --- геометрия и место ярлыка
DESK_W="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\1/p"' | head -1)"
DESK_H="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\2/p"' | head -1)"
[[ "$DESK_W" =~ ^[0-9]+$ ]] && [[ "$DESK_H" =~ ^[0-9]+$ ]] \
    || die "стол не назвал свой размер (xdpyinfo вернул «${DESK_W}×${DESK_H}») — координаты выводить не из чего"

# Дерево окон снимается с повтором: маячок отвечает раньше, чем приложение показывает
# стол на экране, и снятый в эту щель дамп ещё не знает вида, в который стол вписан.
# Один снимок здесь давал красное на пустом месте — «на экране сейчас не стол».
MAP=""
for try in 1 2 3 4 5 6; do
    "$ADB" -s "$SERIAL" shell rm -f /sdcard/stol-frame.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/stol-frame.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell cat /sdcard/stol-frame.xml > "$TMP/frame.xml" 2>/dev/null
    if [ -s "$TMP/frame.xml" ]; then
        MAP="$(python3 "$HERE/store-points.py" frame --dump "$TMP/frame.xml" \
            --desk-width "$DESK_W" --desk-height "$DESK_H" --package "$PKG" 2>&1)" && break
        step "снимок дерева окон ${try}: $(sed -n 's/^ОШИБКА=//p' <<<"${MAP}")"
    else
        step "снимок дерева окон ${try}: дамп пустой"
    fi
    MAP=""
    sleep 10
done
[ -n "$MAP" ] || die "прямоугольник стола на экране не выведен ни по одному из шести дампов — последняя причина выше"
SCALE="$(sed -n 's/^SCALE=//p' <<<"$MAP")"
OFF_X="$(sed -n 's/^OFFSET_X=//p' <<<"$MAP")"
OFF_Y="$(sed -n 's/^OFFSET_Y=//p' <<<"$MAP")"

PANEL_TOP="$(in_image 'wmctrl -lG -x' | grep -i 'xfce4-panel' | sort -k6 -n -r | head -1 | awk '{print $4}')"
[[ "$PANEL_TOP" =~ ^[0-9]+$ ]] || PANEL_TOP="$DESK_H"
step "стол ${DESK_W}×${DESK_H} вписан в вид $(sed -n 's/^VIEW=//p' <<<"$MAP"), масштаб ${SCALE}, панель с ${PANEL_TOP}"

POINT=""
for try in 1 2 3 4 5 6; do
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$TMP/before.png" 2>/dev/null
    in_image 'cat /root/.config/xfce4/desktop/icons.screen0.yaml' > "$TMP/icons.yaml"
    POINT="$(python3 "$HERE/store-points.py" icon --shot "$TMP/before.png" --icons "$TMP/icons.yaml" \
        --entry "$ENTRY" --scale "$SCALE" --offset-x "$OFF_X" --offset-y "$OFF_Y" \
        --desk-width "$DESK_W" --desk-height "$DESK_H" --panel-top "$PANEL_TOP")" && break
    step "снимок ${try}: $(sed -n 's/^ОШИБКА=//p' <<<"${POINT}")"
    POINT=""
    sleep 5
done
[ -n "$POINT" ] || die "место ярлыка магазина на столе не выведено ни по одному из шести снимков — последняя причина выше"
ICON_X="$(sed -n 's/^DESK_X=//p' <<<"$POINT")"
ICON_Y="$(sed -n 's/^DESK_Y=//p' <<<"$POINT")"
TAP_X="$(dev_x "$ICON_X")"
TAP_Y="$(dev_y "$ICON_Y")"
ok "ярлык магазина: точка стола (${ICON_X}, ${ICON_Y}) → точка экрана (${TAP_X}, ${TAP_Y})"

# --- первое касание: разогрева нет намеренно
step "двойной тап по ярлыку — первым касанием, без разогрева по пустому месту"
FRESH=""
for try in 1 2 3 4 5 6 7 8; do
    double_tap "$TAP_X" "$TAP_Y"
    sleep 10
    if window_here "$TITLE"; then
        FRESH="$try"
        break
    fi
    step "с ${try}-го раза магазин не открылся: $(click_line)"
done
if [ -z "$FRESH" ]; then
    evidence "на свежем столе ярлык не открылся после восьми двойных тапов" "fresh"
    die "ярлык не открывается даже на свежем столе — выдерживать нечего, чинить надо запуск"
fi
[ "$FRESH" = "1" ] \
    && ok "ярлык открылся с первого двойного тапа: первое касание не потеряно" \
    || { evidence "первое касание после запуска стола пропало: ярлык открылся только с ${FRESH}-го тапа" "first-touch"
         die "первое касание после запуска стола пропадает: ярлык открылся только с ${FRESH}-го двойного тапа.
Человек этого не знает и видит отказ: тапнул по значку — не открылось." ; }

# ------------------------------------------------------------------ 1а. палец, а не мышь

log "1а. Палец, а не мышь: пороги двойного щелчка и десять тапов подряд"

# Ярлык открывается ДВОЙНЫМ щелчком, а двойным парой щелчков её считает GTK — по двум
# порогам сразу: окну времени (`Net/DoubleClickTime`) и окну смещения
# (`Net/DoubleClickDistance`). Мышь в оба укладывается всегда, палец — нет: между двумя
# касаниями проходит больше времени, и вторая точка не совпадает с первой. Пара, вышедшая
# за любой из порогов, остаётся двумя одиночными щелчками: значок только выделяется, а
# программа не запускается. Панель при этом отвечает на тот же тап, потому что ей хватает
# одного щелчка — отсюда и жалоба «тап доходит до панели, а до стола нет».
XSETTINGS_XML="/root/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml"
WANT_TIME="$(sed -n 's|^/Net/DoubleClickTime  *||p' "$REPO/reference/xfconf/xsettings.txt")"
WANT_AWAY="$(sed -n 's|^/Net/DoubleClickDistance  *||p' "$REPO/reference/xfconf/xsettings.txt")"
[[ "$WANT_TIME" =~ ^[0-9]+$ ]] && [[ "$WANT_AWAY" =~ ^[0-9]+$ ]] \
    || die "в reference/xfconf/xsettings.txt не названы пороги двойного щелчка — сверять не с чем"

LIVE="$(in_image "grep -E 'DoubleClick(Time|Distance)' ${XSETTINGS_XML}")"
HAVE_TIME="$(sed -n 's/.*"DoubleClickTime".*value="\([0-9]*\)".*/\1/p' <<<"${LIVE}")"
HAVE_AWAY="$(sed -n 's/.*"DoubleClickDistance".*value="\([0-9]*\)".*/\1/p' <<<"${LIVE}")"
[ "$HAVE_TIME" = "$WANT_TIME" ] && [ "$HAVE_AWAY" = "$WANT_AWAY" ] \
    || die "на устройстве пороги двойного щелчка (время ${HAVE_TIME:-нет}, смещение ${HAVE_AWAY:-нет})
не те, что названы в reference/xfconf/xsettings.txt (время ${WANT_TIME}, смещение ${WANT_AWAY}).
Образ на устройстве старше правки — пересоберите его и поставьте заново."
ok "пороги двойного щелчка на устройстве: время ${HAVE_TIME} мс, смещение ${HAVE_AWAY} точек"

# Отказ, вызванный по своей воле: пара щелчков с человеческими зазором и смещением. На
# мышиных порогах (400 мс, 5 точек) она НЕ открывает ярлык — это и есть дефект; на
# нынешних обязана открывать.
FINGER_GAP=600
FINGER_AWAY=12
close_store
step "двойной щелчок пальцем: зазор ${FINGER_GAP} мс, второй щелчок на ${FINGER_AWAY} точек в стороне"
in_image "python3 /root/click-path.py dclick ${ICON_X%.*} ${ICON_Y%.*} ${FINGER_GAP} ${FINGER_AWAY}" >/dev/null 2>&1
if ! wait_store 30; then
    evidence "пара щелчков с зазором ${FINGER_GAP} мс и смещением ${FINGER_AWAY} точек ярлык не открыла" "finger"
    die "двойной щелчок ПАЛЬЦЕМ ярлык не открывает: зазор ${FINGER_GAP} мс и смещение ${FINGER_AWAY} точек
вышли за пороги двойного щелчка (${HAVE_TIME} мс, ${HAVE_AWAY} точек), и стол засчитал два
одиночных щелчка вместо пары. Для человека это «тапнул — не открылось», при живой панели."
fi
ok "двойной щелчок пальцем открывает ярлык: пара засчитана парой"
close_store

# Десять двойных тапов подряд, каждый обязан открыть ярлык С ПЕРВОГО раза. Один тап
# ничего не доказывает: отказ редкий, и на одной пробе он проезжает незамеченным.
ROW=10
MISSED=0
# Окно закрывается ПЕРЕД каждым тапом, а не после: после последнего оно должно остаться
# открытым — со столом дальше работают, а не поднимают его заново.
for round in $(seq 1 "$ROW"); do
    close_store
    # Дать устройству отдышаться перед тапом. Замер под нагрузкой: пока эмулятор занят
    # (закрытие окна, свои сессии proot), из 20 нажатий до X-сервера доезжают 16, а зазор
    # между парой гуляет от 22 до 2210 мс — потому что `input tap` поднимает на каждый тап
    # свою виртуальную машину, и две гонятся друг с другом. Это разброс обвязки, а не стола:
    # мерить ярлык на занятом устройстве значит мерить `input tap`.
    sleep 6
    double_tap "$TAP_X" "$TAP_Y"
    if wait_store 30; then
        [ "$STORE_WAITED" -ge 9 ] && step "тап ${round} из ${ROW}: окно магазина появилось на ${STORE_WAITED}-й секунде"
    else
        MISSED=$((MISSED + 1))
        step "тап ${round} из ${ROW}: ярлык не открылся — $(click_line)"
        for again in 2 3 4; do
            double_tap "$TAP_X" "$TAP_Y"
            wait_store 30 && break
        done
    fi
done
if [ "$MISSED" -gt 0 ]; then
    evidence "из ${ROW} двойных тапов подряд ${MISSED} потребовали второй попытки" "row"
    die "ярлык открывается не с первого раза: из ${ROW} двойных тапов подряд ${MISSED} потребовали
второй попытки. Это ровно жалоба владельца — «открывается со второго, с третьего, с четвёртого»."
fi
ok "${ROW} двойных тапов подряд — ${ROW} открытий с первого раза"

# ------------------------------------------------------------------ 1в. палец и часы

log "1в. Долгое нажатие — это меню, короткое — запуск"

# Стол различает щелчки по времени удержания: короткое касание приложение переводит в
# ЛЕВЫЙ щелчок, долгое — в ПРАВЫЙ, а на правый щелчок стол открывает своё меню. Меню
# забирает указатель себе (пустая маска кнопок, а указатель ЗАХВАЧЕН), и следующие тапы
# уходят в меню, а не в ярлык. Пока приложение мерило удержание своим будильником, под
# нагрузкой это происходило само собой: отложенный вызов срабатывал раньше, чем
# разбиралось отпускание, и КОРОТКИЙ тап становился правым щелчком. Здесь проверяется
# оба конца правила: после обычного двойного тапа указатель обязан быть свободен, а
# нарочное удержание обязано меню открыть — иначе правый щелчок пропал вовсе.
GRAB_NOW="$(in_image 'python3 /root/click-path.py state' | sed -n 's/^ЗАХВАТ_СЛОВОМ=//p')"
case "${GRAB_NOW}" in
    свободен) ok "после ряда тапов указатель свободен: меню стола никто не открывал" ;;
    *) evidence "после обычных тапов указатель ${GRAB_NOW}" "menu-grab"
       die "обычный тап открыл меню стола: указатель ${GRAB_NOW}, а кнопок не нажато.
Приложение приняло короткое касание за долгое и послало ПРАВЫЙ щелчок. Меню держит
указатель, и следующие тапы уходят в него, а не в ярлык — это и есть «тапнул, а
открылось только со второго-третьего раза». Удержание надо мерить часами самого
касания (MotionEvent.getEventTime минус getDownTime), а не отложенным вызовом:
см. DesktopView.onTouchEvent." ;;
esac

step "нарочное удержание ${LONG_HOLD:=900} мс по ярлыку — правый щелчок и меню стола"
"$ADB" -s "$SERIAL" shell "input swipe $TAP_X $TAP_Y $TAP_X $TAP_Y $LONG_HOLD" >/dev/null 2>&1
sleep 4
HELD="$(in_image 'python3 /root/click-path.py state' | sed -n 's/^ЗАХВАТ_СЛОВОМ=//p')"
"$ADB" -s "$SERIAL" shell input keyevent 111 >/dev/null 2>&1
sleep 3
case "${HELD}" in
    свободен) evidence "удержание ${LONG_HOLD} мс меню стола не открыло" "no-menu"
              die "долгое нажатие перестало быть правым щелчком: удержание ${LONG_HOLD} мс не открыло
меню стола (указатель ${HELD}). Значит различение короткого и долгого касания сломано в
другую сторону — меню на столе теперь не вызвать вовсе." ;;
    *) ok "удержание ${LONG_HOLD} мс открыло меню стола: указатель ${HELD}" ;;
esac
FREED="$(in_image 'python3 /root/click-path.py state' | sed -n 's/^ЗАХВАТ_СЛОВОМ=//p')"
[ "$FREED" = "свободен" ] || step "меню закрылось не сразу: указатель ${FREED}"
close_store

# ------------------------------------------------------------------ 1б. служба запуска

log "1б. Служба запуска: ярлык открывается и после того, как служба ушла"

# Ярлык открывает не xfdesktop. Он отдаёт запуск `.desktop` службе
# org.xfce.FileManager на сессионной шине, а держит эту службу `thunar --daemon`,
# который поднимает start-gui.sh ровно один раз. Служба ушла — и стол на КАЖДЫЙ
# двойной щелчок отвечает окном «Ошибка запуска», пока приложение не перезапустят:
# ровно жалоба владельца «лечится только перезапуском». Второй раз службу никто не
# поднимает — её обязана поднять сама шина по файлу
# /usr/share/dbus-1/services/org.xfce.FileManager.service, а шина запускает
# службы в СВОЁМ окружении. Без DISPLAY в нём `Thunar --gapplication-service`
# выходит с кодом 1 на «cannot open display», и запуск теряется здесь — не в
# щелчке. Поэтому окружение шине выдаётся до её запуска (image/start-gui.sh).
#
# Здесь это состояние вызывается по своей воле: службу снимают и требуют, чтобы
# следующий же двойной тап открыл ярлык.
close_store
BUS_ENV="$(in_image 'D=$(pgrep -f "dbus-daemon --session" | head -1)
tr "\0" "\n" < /proc/$D/environ > /tmp/busenv.txt
grep -E "^(DISPLAY|DBUS_SESSION_BUS_ADDRESS)=" /tmp/busenv.txt
exit 0')"
step "окружение шины: $(printf '%s' "${BUS_ENV}" | tr '\n' ' ')"

in_image 'pkill -f "thunar --daemon"
pkill -f "Thunar --gapplication-service"
sleep 2
exit 0' >/dev/null 2>&1
FM_LEFT="$(in_image 'ps -eo args > /tmp/ps.txt
grep -c -E "^(thunar|/usr/bin/Thunar)" /tmp/ps.txt
exit 0' | tail -1)"
[ "${FM_LEFT}" = "0" ] || step "служба снялась не до конца (осталось ${FM_LEFT}) — смотрим, что будет"
step "служба файлового менеджера снята: теперь её обязана поднять сама шина"

double_tap "$TAP_X" "$TAP_Y"
if ! wait_store 45; then
    LAUNCH_ERR=""
    in_image 'wmctrl -l' | grep -q "Ошибка запуска" && LAUNCH_ERR="да"
    evidence "после снятия службы файлового менеджера ярлык не открылся${LAUNCH_ERR:+ (стол ответил окном «Ошибка запуска»)}" "fm-service"
    die "запуск теряется в службе файлового менеджера: ${TITLE} не открылся за 45 с после того,
как со стола ушла служба org.xfce.FileManager. Шина обязана поднять её сама по файлу
/usr/share/dbus-1/services/org.xfce.FileManager.service, но запускает службы в своём
окружении — а в нём сейчас: ${BUS_ENV:-пусто}. Без DISPLAY «Thunar --gapplication-service»
выходит с кодом 1 («cannot open display»), и КАЖДЫЙ двойной щелчок по любому ярлыку
отвечает окном «Ошибка запуска» до перезапуска приложения. Лечение — выдать шине
окружение стола до её запуска, см. image/start-gui.sh."
fi
ok "служба ушла — шина подняла её сама, ярлык открылся за ${STORE_WAITED} с"
close_store

# ------------------------------------------------------------------ 2. работа

log "2. Работа: окна, «Пуск», выдержка ${MINUTES} мин"

# Окно двигают и закрывают — так со столом работает человек, а не так, что стол стоит
# нетронутым и потому «жив».
STORE_WIN="$(in_image "wmctrl -l" | awk -v t="$TITLE" 'index($0, t) { print $1 }' | head -1)"
in_image "wmctrl -i -r $STORE_WIN -e 0,120,80,640,520" >/dev/null 2>&1
sleep 2
in_image "wmctrl -i -r $STORE_WIN -b add,maximized_vert,maximized_horz" >/dev/null 2>&1
sleep 2
close_store
window_here "$TITLE" && die "окно магазина не закрылось — дальше проверять нечего"
ok "окно открыли, подвигали и закрыли"

# Меню «Пуск»: щелчок по панели — та часть стола, которая в жалобе продолжает работать.
"$ADB" -s "$SERIAL" shell "input tap $(dev_x 20) $(dev_y $((PANEL_TOP + (DESK_H - PANEL_TOP) / 2)))" >/dev/null 2>&1
sleep 4
if window_here "Whisker Menu"; then
    ok "меню «Пуск» открылось по щелчку в панель"
    "$ADB" -s "$SERIAL" shell input keyevent 111 >/dev/null 2>&1
    sleep 2
else
    step "меню «Пуск» по щелчку в панель не открылось — запомним это в разборе"
fi

step "выдержка ${MINUTES} мин: стол стоит запущенным, никто его не трогает"
BEGAN="$(date +%s)"
LEFT=$((MINUTES * 60))
while [ "$LEFT" -gt 0 ]; do
    sleep 60
    LEFT=$((LEFT - 60))
    [ $((LEFT % 300)) -eq 0 ] && [ "$LEFT" -gt 0 ] && step "осталось $((LEFT / 60)) мин"
done
ok "стол простоял $(( ($(date +%s) - BEGAN) / 60 )) мин под нагрузкой обычного дня"

# ------------------------------------------------------------------ 3. после работы

log "3. После работы: тот же ярлык в ту же точку"

beacon_open || { evidence "маячок закрылся — стол не пережил выдержку" "beacon"
                 die "после выдержки стол не отвечает на маячке 5910 — он просто умер" ; }

AGED=""
for try in 1 2 3 4 5 6 7 8; do
    double_tap "$TAP_X" "$TAP_Y"
    sleep 10
    if window_here "$TITLE"; then
        AGED="$try"
        break
    fi
    step "с ${try}-го раза магазин не открылся: $(click_line)"
    if in_image 'wmctrl -l' | grep -q "Ошибка запуска"; then
        in_image 'for id in $(wmctrl -l | grep "Ошибка запуска" | cut -d" " -f1); do wmctrl -i -c $id; done' >/dev/null 2>&1
        evidence "стол ответил окном «Ошибка запуска»" "launch-error"
        die "на пожившем столе ярлык отвечает окном «Ошибка запуска» — из сессии ушла служба
файлового менеджера: xfdesktop отдаёт запуск .desktop ей (см. image/start-gui.sh)."
    fi
done

if [ -z "$AGED" ]; then
    evidence "после ${MINUTES} мин работы ярлык не открылся после восьми двойных тапов" "aged"
    die "на пожившем столе ярлык магазина не открывается: восемь двойных тапов в ту же точку,
что открывала его на свежем столе, не дали ничего. Стол при этом жив — маячок отвечает.
Для человека это и есть «поработал — половина стола умерла» (R15D)."
fi
ok "после ${MINUTES} мин работы ярлык открылся с ${AGED}-го двойного тапа"
close_store

# ------------------------------------------------------------------ 4. заклиненная кнопка

log "4. Заклиненная кнопка: тот самый отказ, вызванный по своей воле"

# Второй клиент RFB нажимает среднюю кнопку над панелью и не отпускает её. Средняя
# выбрана намеренно: приложение ею не пользуется, значит само оно это состояние не
# снимет — как не снимало бы потерянное отпускание.
WEDGE_X=$((DESK_W - 100))
WEDGE_Y=$((PANEL_TOP + (DESK_H - PANEL_TOP) / 2))
put_file "files/rootfs/tmp/wedge-cmd.sh" "python3 /root/click-path.py wedge ${WEDGE_X} ${WEDGE_Y} 2 180 > /root/wedge.log 2>&1"
"$ADB" -s "$SERIAL" shell "run-as $PKG sh files/endurance-enter.sh '$NATIVE' wedge-cmd.sh" >/dev/null 2>&1 &
WEDGE_JOB=$!
unwedge() {
    kill "$WEDGE_JOB" 2>/dev/null
    in_image 'pkill -f click-path.py
exit 0' >/dev/null 2>&1
    sleep 3
}
sleep 10

STATE="$(click_state)"
MASK="$(sed -n 's/^МАСКА=//p' <<<"${STATE}")"
case "${MASK}" in
    0x0000|"") unwedge
               die "заклинить кнопку не удалось (маска ${MASK:-пустая}) — значит и проверять нечего.
Второй клиент RFB до стола не дошёл: смотрите /root/wedge.log в образе." ;;
esac
ok "кнопка заклинена в точке стола (${WEDGE_X}, ${WEDGE_Y}): нажаты $(sed -n 's/^НАЖАТЫ=//p' <<<"${STATE}"), указатель $(sed -n 's/^ЗАХВАТ_СЛОВОМ=//p' <<<"${STATE}")"

step "двойной тап по ярлыку при заклиненной кнопке"
double_tap "$TAP_X" "$TAP_Y"
sleep 8
STATE="$(click_state)"
WEDGED_X="$(sed -n 's/^КУРСОР_X=//p' <<<"${STATE}")"
WEDGED_Y="$(sed -n 's/^КУРСОР_Y=//p' <<<"${STATE}")"
AWAY="$(python3 -c "print(int(max(abs(${WEDGED_X} - ${ICON_X}), abs(${WEDGED_Y} - ${ICON_Y}))))" 2>/dev/null)"
[ -n "$AWAY" ] || AWAY=9999
unwedge

if [ "$AWAY" -gt 40 ]; then
    evidence "при заклиненной кнопке щелчок ушёл не в ярлык: курсор встал в (${WEDGED_X}, ${WEDGED_Y})" "wedge"
    die "при заклиненной кнопке тап уехал мимо ярлыка: курсор встал в (${WEDGED_X}, ${WEDGED_Y}),
а ярлык в (${ICON_X}, ${ICON_Y}) — щелчок забрало окно, которым указатель захвачен, чаще
всего панель. Это и есть жалоба R15D: тап доходит до панели, а до стола нет. Щелчок
обязан начинаться с пустой маски кнопок, чтобы снимать такое залипание, — см.
RfbClient.sendClick в приложении."
fi
ok "при заклиненной кнопке щелчок пришёл в ярлык: курсор (${WEDGED_X}, ${WEDGED_Y}), мимо не больше ${AWAY} точек"

STATE="$(click_state)"
case "$(sed -n 's/^МАСКА=//p' <<<"${STATE}")" in
    0x0000) ok "залипание снято: нажатых кнопок у сервера не осталось" ;;
    *) evidence "после снятия второго клиента кнопка всё ещё нажата" "wedge-left"
       die "кнопка осталась нажатой и после разрыва связи второго клиента: $(click_line)" ;;
esac

WEDGE_OPEN=""
for try in 1 2 3 4; do
    double_tap "$TAP_X" "$TAP_Y"
    sleep 10
    if window_here "$TITLE"; then
        WEDGE_OPEN="$try"
        break
    fi
    step "с ${try}-го раза магазин не открылся: $(click_line)"
done
if [ -z "$WEDGE_OPEN" ]; then
    evidence "после заклинивания и его снятия ярлык не открылся" "wedge-after"
    die "стол не вернулся к жизни после заклиненной кнопки: ярлык не открылся с четырёх двойных тапов."
fi
ok "после заклинивания стол работает: ярлык открылся с ${WEDGE_OPEN}-го двойного тапа"
close_store

{
    echo "устройство: ${SERIAL}"
    echo "эмулятор:   ${AVD}"
    echo "Android:    ${REL} (API ${SDK})"
    echo "ярлык:      ${ENTRY}, точка стола (${ICON_X}, ${ICON_Y}) → точка экрана (${TAP_X}, ${TAP_Y})"
    echo "свежий:     открылся с ${FRESH}-го двойного тапа (разогрева не было)"
    echo "выдержка:   ${MINUTES} мин работы и простоя"
    echo "после:      открылся с ${AGED}-го двойного тапа"
    echo "заклинивание: щелчок пришёл в ярлык, мимо ${AWAY} точек; после снятия открылся с ${WEDGE_OPEN}-го тапа"
    echo "снято:      $(date '+%Y-%m-%d %H:%M:%S %Z')"
} > "$ART/endurance-${TAG}.txt"

echo
printf '\033[32mЗЕЛЁНО\033[0m  стол пережил %s мин работы: ярлык открывается и после неё (R15D)\n' "$MINUTES"
step "разбор: $ART/endurance-${TAG}.txt"
exit 0
