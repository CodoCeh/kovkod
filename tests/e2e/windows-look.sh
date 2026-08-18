#!/usr/bin/env bash
#
# tests/e2e/windows-look.sh — вид и повадки Windows 10-11 на столе (R03).
#
# Проверка из двух половин, и обе обязательны:
#
#   ВИД      — обратным чтением каналов xfconf из СОБРАННОГО ОБРАЗА. Настройки XFCE
#              живут дампами в reference/xfconf/, сборка раскладывает их в образ, а
#              здесь образ разворачивается и его же xfconf-query читает их обратно.
#              Читать XML глазами нельзя: он никем не проверяется, а обратное чтение
#              доказывает, что xfconfd понял ровно то, что мы написали.
#
#   ПОВАДКИ  — живыми действиями на устройстве. Двойной клик по заголовку, протаскивание
#              к краю и Alt+Tab идут через `adb shell input`: приложение переводит
#              касание в событие указателя RFB, другого пути внутрь стола снаружи нет.
#              Координаты считаются из состояния экрана (размер экрана, врезки системных
#              полос, размер стола из xdpyinfo) — вписанных чисел здесь нет.
#
# Что доказывается: R03.1 R03.2 R03.3 R03.4 (вид), R03.6 (кнопки выключения в «Пуске»),
# R03.10 R03.11 (тема и шрифт), R12i.2 (значок «Пуска» — наш), R03.5 (панель внизу на
# живом столе), R03.7 R03.8 R03.9 (повадки окон).
#
# Использование:
#   bash tests/e2e/windows-look.sh              обе половины
#   bash tests/e2e/windows-look.sh --look       только вид (устройство не нужно)
#   bash tests/e2e/windows-look.sh --live       только повадки
#
# Стол на устройстве обязан быть из ТОГО ЖЕ образа: половина «повадки» сначала сверяет
# ключи живого стола с эталонными дампами и краснеет на старом столе, а не показывает
# зелёное про вчерашний вид.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"
PKG="ru.codoceh.stol"

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
LIMA_VM="${STOL_LIMA_VM:-ceho}"
CHANNELS="xfce4-panel xfwm4 xsettings"
START_ICON="$REPO/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m %s\n' "$*"; FAILED=$((FAILED + 1)); }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

FAILED=0
MODE="all"
while [ $# -gt 0 ]; do
    case "$1" in
        --look) MODE="look" ;;
        --live) MODE="live" ;;
        -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
        *) die "неизвестный ключ: $1" ;;
    esac
    shift
done

TMP="$(mktemp -d "${TMPDIR:-/tmp}/windows-look.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$ART"

# ------------------------------------------------------------------ чтение дампа

# Значение ключа из дампа `xfconf-query -l -v`: ключ, отбивка, значение.
value_of() { sed -nE "s|^$2[[:space:]]{2,}(.*)$|\1|p" "$1" | head -1; }

want() {
    local file="$1" key="$2" expect="$3" what="$4" got
    got="$(value_of "$file" "$key")"
    if [ "$got" = "$expect" ]; then
        ok "$what: $key = $got"
    else
        bad "$what: $key = «${got:-нет ключа}», а должно быть «$expect»"
    fi
}

# ================================================================== ВИД

check_look() {
    log "1. Вид: обратное чтение каналов xfconf из собранного образа"

    local dir="$REPO/build/image"
    [ -f "$dir/rootfs.tar.zst" ] || die "нет собранного образа $dir/rootfs.tar.zst.
Соберите: bash tools/mkimage.sh"
    grep -q '"version"[^,]*-smoke' "$dir/image.json" 2>/dev/null \
        && die "в build/image лежит смоук-образ: в нём нет ни панели, ни темы — вид проверять не на чем"
    step "образ: $dir ($(cd "$dir" && cut -d' ' -f1 < rootfs.tar.zst.sha256 | cut -c1-12)…)"

    readback "$dir" > "$TMP/readback.txt" || die "обратное чтение каналов не состоялось, см. вывод выше"

    local ch
    for ch in $CHANNELS; do
        awk -v c="#### $ch" 'p && /^####/ { exit } p { print } $0 == c { p = 1 }' \
            "$TMP/readback.txt" > "$TMP/$ch.txt"
        [ -s "$TMP/$ch.txt" ] || die "канал $ch не прочитался из образа"
    done
    awk 'p { print } /^#### файлы/ { p = 1 }' "$TMP/readback.txt" > "$TMP/files.txt"

    local panel="$TMP/xfce4-panel.txt" wm="$TMP/xfwm4.txt" xs="$TMP/xsettings.txt"

    # --- R03.1 панель одна, снизу, во всю ширину
    want "$panel" "/panels" "[1]" "R03.1"
    want "$panel" "/panels/panel-1/position" "p=12;x=0;y=0" "R03.1"
    want "$panel" "/panels/panel-1/length" "100" "R03.1"
    if grep -q '^/panels/panel-2' "$panel"; then
        bad "R03.1: в канале осталась panel-2 — вторая панель на столе"
    else
        ok "R03.1: второй панели нет"
    fi

    # --- R03.2 порядок плагинов слева направо
    local ids order n type
    ids="$(value_of "$panel" "/panels/panel-1/plugin-ids" | tr -d '[]' | tr ',' ' ')"
    order=""
    for n in $ids; do
        type="$(value_of "$panel" "/plugins/plugin-$n")"
        [ -n "$type" ] || { bad "R03.2: плагина plugin-$n нет в канале, а он перечислен в панели"; type="?"; }
        order="$order $type"
    done
    order="${order# }"
    # Ровно то, что заказано: «Пуск», разделитель, закреплённые значки, окна,
    # распорный разделитель, трей, часы. Число значков — сколько закрепили.
    local pattern="^whiskermenu separator( launcher)+ tasklist separator systray clock$"
    if printf '%s' "$order" | grep -qE "$pattern"; then
        ok "R03.2: порядок плагинов слева направо — $order"
    else
        bad "R03.2: порядок плагинов «$order», а заказан whiskermenu, separator, launcher…, tasklist, separator, systray, clock"
    fi
    # Распорный — именно тот разделитель, что стоит между списком окон и треем.
    local prev="" spacer=""
    for n in $ids; do
        type="$(value_of "$panel" "/plugins/plugin-$n")"
        [ "$prev" = "tasklist" ] && [ "$type" = "separator" ] && spacer="$n"
        prev="$type"
    done
    if [ -n "$spacer" ] && [ "$(value_of "$panel" "/plugins/plugin-$spacer/expand")" = "true" ]; then
        ok "R03.2: разделитель между окнами и треем распорный (plugin-$spacer, expand=true)"
    else
        bad "R03.2: разделитель между списком окон и треем не распорный — трей и часы не прижмутся вправо"
    fi

    # --- R03.3 кнопки окна справа, меню окна слева нет
    want "$wm" "/general/button_layout" "|HMC" "R03.3"

    # --- R03.4 рабочее место одно, переключателя нет
    want "$wm" "/general/workspace_count" "1" "R03.4"
    if grep -qE '^/plugins/plugin-[0-9]+[[:space:]]+pager$' "$panel"; then
        bad "R03.4: на панели остался переключатель рабочих мест"
    else
        ok "R03.4: переключателя рабочих мест на панели нет"
    fi

    # --- R03.6 кнопки выключения и перезагрузки в «Пуске» скрыты
    local start
    start="$(grep -E '^/plugins/plugin-[0-9]+[[:space:]]+whiskermenu$' "$panel" | head -1 | sed -E 's|^/plugins/(plugin-[0-9]+).*|\1|')"
    if [ -z "$start" ]; then
        bad "R03.6: плагина «Пуск» в канале нет — гасить нечего"
    else
        want "$panel" "/plugins/$start/show-command-shutdown" "false" "R03.6"
        want "$panel" "/plugins/$start/show-command-restart" "false" "R03.6"
    fi

    # --- R03.7 и R03.8: половина в настройках, половина на живом столе
    want "$wm" "/general/double_click_action" "maximize" "R03.7"
    want "$wm" "/general/tile_on_move" "true" "R03.8"
    # xfwm4 отказывается класть окно к краю, пока включено заворачивание окон
    # (moveresize.c: «We cannot tile windows if wrapping is enabled»).
    want "$wm" "/general/wrap_windows" "false" "R03.8"

    # --- R03.10 тема
    want "$xs" "/Net/ThemeName" "Arc" "R03.10"
    want "$wm" "/general/theme" "Arc" "R03.10"

    # --- R03.11 шрифт и сглаживание
    want "$xs" "/Gtk/FontName" "Noto Sans 10" "R03.11"
    want "$xs" "/Xft/Antialias" "1" "R03.11"
    want "$xs" "/Xft/HintStyle" "hintslight" "R03.11"

    # --- имя набора значков не должно врать: в образе он обязан быть
    local icons
    icons="$(value_of "$xs" "/Net/IconThemeName")"
    if grep -qx "ЕСТЬ /usr/share/icons/$icons" "$TMP/files.txt"; then
        ok "R03.10: набор значков $icons в образе есть"
    else
        bad "R03.10: в дампе набор значков «$icons», а такого каталога в образе нет"
    fi

    # --- файлы, без которых вид — только слова
    local f
    while read -r f; do
        case "$f" in
            "ЕСТЬ "*) ok "в образе: ${f#ЕСТЬ }" ;;
            "НЕТ "*)  bad "в образе нет: ${f#НЕТ }" ;;
        esac
    done < <(grep -E '^(ЕСТЬ|НЕТ) /usr/share/(themes|fonts)' "$TMP/files.txt")

    # --- R12i.2 значок «Пуска» — наш файл, а не картинка из чужой темы
    local icon_path want_sha got_sha
    icon_path="$(value_of "$panel" "/plugins/$start/button-icon")"
    want_sha="$(sha256_of "$START_ICON")"
    got_sha="$(sed -n "s|^SHA $icon_path ||p" "$TMP/files.txt")"
    if [ -z "$icon_path" ]; then
        bad "R12i.2: у кнопки «Пуск» не задан значок"
    elif [ "$want_sha" = "$got_sha" ]; then
        ok "R12i.2: кнопка «Пуск» рисуется нашей наковальней ($icon_path)"
    else
        bad "R12i.2: значок «Пуска» $icon_path не совпал с $START_ICON ($got_sha != $want_sha)"
    fi
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Разворачивает образ и читает каналы его же xfconf-query. На macOS это делается в
# arm64-виртуалке lima — тем же способом, каким образ и собирался.
readback() {
    local dir="$1"
    if [ "$(uname -s)" = "Linux" ] && [ "$(id -u)" = "0" ]; then
        readback_here "$dir" "$CHANNELS" "$START_ICON"
        return
    fi
    command -v limactl >/dev/null 2>&1 \
        || die "нет limactl — обратное чтение идёт в arm64-виртуалке lima"
    limactl list --format '{{.Name}}' 2>/dev/null | grep -qx "$LIMA_VM" \
        || die "виртуалки lima «$LIMA_VM» нет — задайте STOL_LIMA_VM"
    if [ "$(limactl list --format '{{.Status}}' "$LIMA_VM")" != "Running" ]; then
        limactl start "$LIMA_VM" >/dev/null || die "виртуалка lima не поднялась"
    fi
    limactl shell "$LIMA_VM" -- sudo bash -s -- "$dir" "$CHANNELS" "$START_ICON" \
        < <(declare -f readback_here; printf 'readback_here "$@"\n')
}

readback_here() {
    local dir="$1" channels="$2" icon="$3"
    local work="/var/tmp/codoceh-stol/windows-look"
    local rootfs="$work/rootfs" stamp="$work/stamp" want
    want="$(cut -d' ' -f1 < "$dir/rootfs.tar.zst.sha256")"
    mkdir -p "$work"
    if [ ! -f "$stamp" ] || [ "$(cat "$stamp")" != "$want" ]; then
        printf 'разворачиваю образ (%s)\n' "$want" >&2
        umount "$rootfs/proc" "$rootfs/dev" 2>/dev/null
        rm -rf "$rootfs"; mkdir -p "$rootfs"
        zstd -dc "$dir/rootfs.tar.zst" | tar -C "$rootfs" -xf - --numeric-owner || return 1
        echo "$want" > "$stamp"
    fi
    mountpoint -q "$rootfs/proc" || mount -t proc proc "$rootfs/proc"
    # В образе /dev пуст: его вычищает сборка. Без /dev/null не поднимается даже dbus,
    # а без шины нечем читать каналы.
    mountpoint -q "$rootfs/dev" || mount --bind /dev "$rootfs/dev"

    local ch
    for ch in $channels; do
        echo "#### $ch"
        chroot "$rootfs" /usr/bin/env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin \
            LC_ALL=C.UTF-8 dbus-run-session -- xfconf-query -c "$ch" -l -v || return 1
    done

    echo "#### файлы"
    local f
    for f in /usr/share/themes/Arc /usr/share/themes/Arc/xfwm4 \
             /usr/share/fonts/truetype/noto/NotoSans-Regular.ttf \
             /usr/share/icons/Adwaita /usr/share/icons/AdwaitaLegacy \
             /usr/share/pixmaps/codoceh-start.png
    do
        if [ -e "$rootfs$f" ]; then echo "ЕСТЬ $f"; else echo "НЕТ $f"; fi
    done
    f=/usr/share/pixmaps/codoceh-start.png
    [ -f "$rootfs$f" ] && echo "SHA $f $(sha256sum "$rootfs$f" | cut -d' ' -f1)"
    umount "$rootfs/proc" "$rootfs/dev" 2>/dev/null
    return 0
}

# ================================================================== ПОВАДКИ

# Команда внутри образа на устройстве: тот же proot, каким приложение поднимает стол.
#
# Команда едет файлом, а не строкой в аргументах: adb склеивает команду обратно в
# строку и теряет вложенные кавычки, а здесь их полно (grep -E 'a|b').
in_image() {
    printf '%s\n' "$1" \
        | "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat > files/rootfs/tmp/wl-cmd.sh'" >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/windows-look-exec.sh '$NATIVE' wl-cmd.sh" 2>&1 | tr -d '\r'
}

check_live() {
    log "2. Повадки: живой стол на устройстве"

    command -v "$ADB" >/dev/null 2>&1 || die "не найден adb ($ADB)"

    SERIAL=""
    local serial
    while read -r serial state _; do
        [ "$state" = "device" ] || continue
        [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || continue
        case "$("$ADB" -s "$serial" shell run-as "$PKG" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')" in
            *".stol-ready") SERIAL="$serial"; break ;;
        esac
    done < <("$ADB" devices | tail -n +2)

    [ -n "$SERIAL" ] || die "нет устройства с развёрнутым столом.
Поставьте свежий APK и пройдите первую установку: bash tests/e2e/two-devices.sh"

    local sdk avd
    sdk="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
    avd="$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
    [ -n "$avd" ] || avd="$SERIAL"
    step "устройство $avd ($SERIAL), Android API $sdk"

    local apk_path
    apk_path="$("$ADB" -s "$SERIAL" shell pm path "$PKG" | tr -d '\r' | sed 's/package://' | head -1)"
    NATIVE="$(dirname "$apk_path")/lib/arm64"

    # Запускалка команд внутри образа: повторяет ProotSession приложения.
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat > files/windows-look-exec.sh'" <<'EOF'
#!/system/bin/sh
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
  TERM=xterm-256color LANG=C.UTF-8 DISPLAY=:1 /bin/sh -l "/tmp/$CMD"
EOF

    # Стол должен быть поднят. Готовность — подключением к маячку 5910: через
    # /proc/net/tcp сокеты образа из-под adb не видны, а к 5901 подключаться нельзя.
    "$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    local waited=0
    until "$ADB" -s "$SERIAL" shell "echo x | nc -w 1 127.0.0.1 5910 >/dev/null 2>&1 && echo OPEN" \
            | tr -d '\r' | grep -q OPEN; do
        waited=$((waited + 2))
        [ "$waited" -lt 240 ] || die "стол не поднялся за 240 с — повадки проверять не на чем"
        sleep 2
    done
    step "стол поднят, маячок отвечает (ждали ${waited} с)"

    live_is_fresh || return 0
    live_panel_at_bottom
    live_windows_setup
    live_double_click
    live_tile_on_move
    live_alt_tab
    live_cleanup
}

# Стол на устройстве обязан быть из того же образа: иначе зелёное будет про вчерашний вид.
live_is_fresh() {
    local stale=0 file key expect got
    in_image 'dbus-run-session -- xfconf-query -c xfce4-panel -l -v
echo @@@xfwm4
dbus-run-session -- xfconf-query -c xfwm4 -l -v
echo @@@xsettings
dbus-run-session -- xfconf-query -c xsettings -l -v' > "$TMP/live.txt"
    awk '/^@@@xfwm4/ { exit } { print }' "$TMP/live.txt" > "$TMP/live-panel.txt"
    awk 'p && /^@@@xsettings/ { exit } p { print } /^@@@xfwm4/ { p = 1 }' "$TMP/live.txt" > "$TMP/live-xfwm4.txt"
    awk 'p { print } /^@@@xsettings/ { p = 1 }' "$TMP/live.txt" > "$TMP/live-xsettings.txt"

    while read -r file key expect; do
        [ -n "$file" ] || continue
        got="$(value_of "$TMP/$file" "$key")"
        [ "$got" = "$expect" ] && continue
        step "стол отдаёт $key = «${got:-нет}», а в образе «$expect»"
        stale=1
    done <<'KEYS'
live-panel.txt /panels/panel-1/position p=12;x=0;y=0
live-panel.txt /panels/panel-1/length 100
live-xfwm4.txt /general/button_layout |HMC
live-xfwm4.txt /general/workspace_count 1
live-xfwm4.txt /general/tile_on_move true
live-xfwm4.txt /general/wrap_windows false
live-xsettings.txt /Net/ThemeName Arc
live-xsettings.txt /Gtk/FontName Noto Sans 10
KEYS

    if [ "$stale" -ne 0 ]; then
        bad "стол на устройстве собран из ДРУГОГО образа — повадки проверять бессмысленно.
       Переустановите приложение со свежим APK: adb shell pm clear $PKG, затем bash tests/e2e/two-devices.sh"
        return 1
    fi
    ok "стол на устройстве из того же образа: ключи вида сошлись"
    return 0
}

# Размер стола и пересчёт координат стола в точки экрана. Всё берётся с устройства:
# размер экрана, врезки системных полос и размер кадра из самого X-сервера.
live_geometry() {
    DESK_W="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\1/p"' | head -1)"
    DESK_H="$(in_image 'xdpyinfo | sed -n "s/^  dimensions: *\([0-9]*\)x\([0-9]*\).*/\2/p"' | head -1)"
    [[ "$DESK_W" =~ ^[0-9]+$ ]] && [[ "$DESK_H" =~ ^[0-9]+$ ]] \
        || die "стол не назвал свой размер (xdpyinfo вернул «$DESK_W×$DESK_H»)"

    "$ADB" -s "$SERIAL" shell wm size 2>/dev/null | tr -d '\r' > "$TMP/wm-size.txt"
    "$ADB" -s "$SERIAL" shell dumpsys window 2>/dev/null | tr -d '\r' > "$TMP/dumpsys.txt"

    local map
    map="$(python3 - "$TMP/wm-size.txt" "$TMP/dumpsys.txt" "$DESK_W" "$DESK_H" <<'PY'
import re, sys

size_txt, dump_txt, dw, dh = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])

size = open(size_txt).read()
m = re.findall(r"size:\s*(\d+)x(\d+)", size)
if not m:
    sys.exit("экран не назвал свой размер")
sw, sh = map(int, m[-1])          # Override size, если он есть, идёт последним

# Приложение раздвигает содержимое под системные полосы: корневой контейнер получает
# отступы ровно в их размер, поэтому стол рисуется в оставшемся прямоугольнике.
left = top = right = bottom = 0
for line in open(dump_txt):
    hit = re.search(r"InsetsSource .*type=(statusBars|navigationBars) frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\] visible=true", line)
    if not hit:
        continue
    x1, y1, x2, y2 = map(int, hit.groups()[1:])
    if x2 - x1 >= sw and y1 == 0:
        top = max(top, y2)
    elif x2 - x1 >= sw and y2 >= sh:
        bottom = max(bottom, sh - y1)
    elif y2 - y1 >= sh and x1 == 0:
        left = max(left, x2)
    elif y2 - y1 >= sh and x2 >= sw:
        right = max(right, sw - x1)

vw, vh = sw - left - right, sh - top - bottom
scale = min(vw / dw, vh / dh)
ox = left + (vw - dw * scale) / 2
oy = top + (vh - dh * scale) / 2
print("%.6f %.3f %.3f %d %d %d %d" % (scale, ox, oy, sw, sh, top, bottom))
PY
)" || die "не удалось пересчитать координаты стола в точки экрана"

    read -r SCALE OFF_X OFF_Y SCREEN_W SCREEN_H INSET_TOP INSET_BOTTOM <<<"$map"
    step "стол ${DESK_W}×${DESK_H} вписан в экран ${SCREEN_W}×${SCREEN_H} (полосы ${INSET_TOP}/${INSET_BOTTOM}), масштаб $SCALE"
}

# Точка стола → точка экрана.
dev_x() { python3 -c "print(int(round($OFF_X + $1 * $SCALE)))"; }
dev_y() { python3 -c "print(int(round($OFF_Y + $1 * $SCALE)))"; }

# --- R03.5 панель внизу на живом столе
live_panel_at_bottom() {
    live_geometry
    in_image 'wmctrl -lG -x' > "$TMP/windows.txt"
    local line y w
    # Панелей может быть несколько окон (всплывающие меню — тоже xfce4-panel), поэтому
    # берём самое широкое: сама панель растянута во всю ширину стола.
    line="$(grep -iE 'xfce4-panel' "$TMP/windows.txt" | sort -k6 -n -r | head -1)"
    if [ -z "$line" ]; then
        bad "R03.5: в дереве окон нет окна класса xfce4-panel"
        return
    fi
    y="$(awk '{print $4}' <<<"$line")"
    w="$(awk '{print $5}' <<<"$line")"
    local edge=$((DESK_H * 2 / 3))
    if [ "$w" -ge "$DESK_W" ] && [ "$y" -ge "$edge" ]; then
        ok "R03.5: панель во всю ширину ($w) и внизу — верхняя граница $y при границе трети $edge"
    else
        bad "R03.5: панель шириной $w с верхней границей $y, а нужна ширина $DESK_W и граница ниже $edge"
    fi
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$ART/windows-look-desktop.png" 2>/dev/null
    step "снимок стола: $ART/windows-look-desktop.png"
}

# --- два окна, которыми проверяются повадки
live_windows_setup() {
    # Окна для проверки — xmessage, а не терминал: терминал меняет размер только целыми
    # знакоместами (WM_NORMAL_HINTS: resize increment 10×19) и после прилипания к краю
    # окажется на 15 точек уже половины стола. Проверка бы краснела на честной повадке.
    printf '%s\n' 'xmessage -title codoceh-look-1 -geometry 600x400+400+300 okno-1 &
xmessage -title codoceh-look-2 -geometry 600x400+700+400 okno-2 &
sleep 300' \
        | "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat > files/rootfs/tmp/wl-hold.sh'" >/dev/null 2>&1
    # Сессия обязана жить: proot уносит с собой всё, что породил, — закроем её, и окна
    # закроются вместе с ней.
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/windows-look-exec.sh '$NATIVE' wl-hold.sh" \
        >/dev/null 2>&1 &
    HOLDER=$!
    local waited=0
    until [ "$(in_image 'wmctrl -l' | grep -c codoceh-look)" = "2" ]; do
        waited=$((waited + 3))
        if [ "$waited" -ge 90 ]; then
            bad "два окна для проверки повадок не открылись за 90 с"
            return 1
        fi
        sleep 3
    done
    WIN1="$(in_image 'wmctrl -l' | grep codoceh-look-1 | awk '{print $1}')"
    WIN2="$(in_image 'wmctrl -l' | grep codoceh-look-2 | awk '{print $1}')"
    step "окна открыты: $WIN1 и $WIN2"
}

# Геометрия окна: «x y ширина_клиента высота_клиента верх_рамки наружная_ширина».
# Наружная ширина — с рамкой: окно кладётся к краю целиком, а xwininfo меряет только
# содержимое, и половина стола не сошлась бы на толщину рамки.
win_geometry() {
    in_image "xwininfo -id $1 | grep -E 'Absolute upper-left|Width|Height'; xprop -id $1 _NET_FRAME_EXTENTS" \
        | python3 -c '
import re, sys
t = sys.stdin.read()
def num(name):
    m = re.search(name + r":\s*(-?\d+)", t)
    return int(m.group(1)) if m else None
ext = re.search(r"_NET_FRAME_EXTENTS\(CARDINAL\) = (\d+), (\d+), (\d+), (\d+)", t)
left, right, top = (int(ext.group(1)), int(ext.group(2)), int(ext.group(3))) if ext else (0, 0, 0)
vals = [num("Absolute upper-left X"), num("Absolute upper-left Y"), num("Width"), num("Height")]
if any(v is None for v in vals):
    sys.exit("окно не назвало свою геометрию")
print(" ".join(str(v) for v in vals), top, vals[2] + left + right)
'
}

# --- R03.7 двойной клик по заголовку разворачивает окно
live_double_click() {
    [ -n "${WIN1:-}" ] || return
    in_image "wmctrl -i -r $WIN1 -b remove,maximized_vert,maximized_horz
wmctrl -i -r $WIN1 -e 0,$((DESK_W / 4)),$((DESK_H / 3)),600,400" >/dev/null
    sleep 2
    local before after x y w h top outer tx ty try
    before="$(win_geometry "$WIN1")" || { bad "R03.7: окно не назвало геометрию"; return; }
    read -r x y w h top outer <<<"$before"
    tx="$(dev_x $((x + w / 2)))"; ty="$(dev_y $((y - top / 2)))"

    after="$before"
    for try in 1 2 3; do
        "$ADB" -s "$SERIAL" shell "input tap $tx $ty; input tap $tx $ty" >/dev/null 2>&1
        sleep 2
        after="$(win_geometry "$WIN1")" || continue
        read -r x y w h top outer <<<"$after"
        [ "$outer" -ge $((DESK_W - 2)) ] && break
        [ "$try" -lt 3 ] && step "двойной клик не сработал с $try-го раза, повторяем"
    done

    if [ "$before" = "$after" ]; then
        bad "R03.7: после двойного клика по заголовку геометрия не изменилась ($after)"
    elif [ "$outer" -ge $((DESK_W - 2)) ] && [ "$h" -ge $((DESK_H / 2)) ]; then
        ok "R03.7: двойной клик по заголовку развернул окно: было «$before», стало «$after»"
    else
        bad "R03.7: после двойного клика окно $outer×$h, а стол ${DESK_W}×${DESK_H} — это не разворот"
    fi
}

# --- R03.8 окно, подтащенное к краю, прилипает половиной экрана
live_tile_on_move() {
    [ -n "${WIN1:-}" ] || return
    in_image "wmctrl -i -r $WIN1 -b remove,maximized_vert,maximized_horz
wmctrl -i -r $WIN1 -e 0,$((DESK_W / 3)),$((DESK_H / 3)),600,400" >/dev/null
    sleep 2
    local x y w h top outer tx ty ex cmd i after
    read -r x y w h top outer < <(win_geometry "$WIN1")
    tx="$(dev_x $((x + w / 2)))"; ty="$(dev_y $((y - top / 2)))"
    ex="$(dev_x 0)"

    # Тащим заголовок к левому краю и задерживаемся там: xfwm4 кладёт окно, когда
    # указатель доходит до края, а «дошёл» он узнаёт из событий движения.
    cmd="input motionevent DOWN $tx $ty"
    for i in $(seq 1 6); do
        cmd="$cmd; input motionevent MOVE $(( tx - (tx - ex) * i / 6 )) $ty"
    done
    for i in 1 2 3; do cmd="$cmd; input motionevent MOVE $ex $ty"; done
    cmd="$cmd; input motionevent UP $ex $ty"
    "$ADB" -s "$SERIAL" shell "$cmd" >/dev/null 2>&1
    sleep 2

    after="$(win_geometry "$WIN1")"
    read -r x y w h top outer <<<"$after"
    local half=$((DESK_W / 2)) diff=$((outer - DESK_W / 2))
    [ "$diff" -lt 0 ] && diff=$((-diff))
    if [ "$diff" -le 2 ]; then
        ok "R03.8: окно прилипло к краю половиной стола — ширина с рамкой $outer при половине $half"
    else
        bad "R03.8: после протаскивания к краю ширина с рамкой $outer, а половина стола $half (геометрия «$after»)"
    fi
}

# --- R03.9 Alt+Tab меняет активное окно
live_alt_tab() {
    [ -n "${WIN2:-}" ] || return
    in_image "wmctrl -i -a $WIN2" >/dev/null
    sleep 2
    local before after try
    before="$(in_image 'xprop -root _NET_ACTIVE_WINDOW' | sed -n 's/.*# *\(0x[0-9a-f]*\).*/\1/p')"
    after="$before"
    for try in 1 2 3; do
        "$ADB" -s "$SERIAL" shell "input keycombination ALT_LEFT TAB" >/dev/null 2>&1
        sleep 2
        after="$(in_image 'xprop -root _NET_ACTIVE_WINDOW' | sed -n 's/.*# *\(0x[0-9a-f]*\).*/\1/p')"
        [ "$after" != "$before" ] && break
        [ "$try" -lt 3 ] && step "Alt+Tab не сработал с $try-го раза, повторяем"
    done
    if [ -z "$before" ] || [ -z "$after" ]; then
        bad "R03.9: стол не назвал активное окно"
    elif [ "$before" != "$after" ]; then
        ok "R03.9: Alt+Tab сменил активное окно: $before → $after"
    else
        bad "R03.9: после Alt+Tab активным осталось то же окно ($before)"
    fi
}

live_cleanup() {
    in_image 'pkill -f codoceh-look' >/dev/null 2>&1
    [ -n "${HOLDER:-}" ] && { kill "$HOLDER"; wait "$HOLDER"; } 2>/dev/null
    "$ADB" -s "$SERIAL" shell "run-as $PKG rm -f files/windows-look-exec.sh files/rootfs/tmp/wl-cmd.sh files/rootfs/tmp/wl-hold.sh" >/dev/null 2>&1
    return 0
}

# ==================================================================

[ "$MODE" = "live" ] || check_look
[ "$MODE" = "look" ] || check_live

echo
if [ "$FAILED" -eq 0 ]; then
    case "$MODE" in
        look) printf '\033[32mЗЕЛЁНО\033[0m  вид Windows в собранном образе на месте (R03.1–R03.4, R03.6, R03.10, R03.11)\n' ;;
        live) printf '\033[32mЗЕЛЁНО\033[0m  повадки Windows на живом столе на месте (R03.5, R03.7–R03.9)\n' ;;
        *)    printf '\033[32mЗЕЛЁНО\033[0m  вид и повадки Windows на месте (R03)\n' ;;
    esac
    exit 0
fi
printf '\033[31mКРАСНО\033[0m  провалов: %d — стол не выглядит и не ведёт себя как Windows\n' "$FAILED"
exit 1
