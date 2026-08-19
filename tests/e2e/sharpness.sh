#!/usr/bin/env bash
#
# tests/e2e/sharpness.sh — стол резкий, потому что его не масштабируют.
#
# Жалоба владельца звучала так: «тона рабочего стола очень бледные, раньше изображение
# было более резким и чётким». Причина не в цветах и не в теме. Стол поднимался в жёстком
# размере 1600×1000, а вид на телефоне — другой (1080×2274 на этом эмуляторе, 1248×1972 на
# Galaxy Fold). Картинка не ложилась на сетку точек экрана ни по одной оси, и приложение
# вписывало её с множителем 0,675 и билинейной интерполяцией: каждая точка экрана
# усреднялась из нескольких точек стола. Граница из чёрного в белое превращалась в две-три
# серые — это и есть «бледно и размыто».
#
# Проверка доказывает числами две вещи, и вторая без первой ничего не стоит.
#
#   1. МАСШТАБ. Размер X-сервера (xdpyinfo, изнутри образа) сверяется с размером вида, в
#      который приложение вписало стол (дерево окон Android → store-points.py frame).
#      Требуется точное совпадение и множитель ровно 1,000000. Это прямая проверка
#      причины: пока множитель не единица, интерполяция есть по определению.
#
#   2. РЕЗКОСТЬ. На стол выставляется одно и то же окно (xmessage, чёрным по белому) в
#      одном и том же месте и размере В ТОЧКАХ СТОЛА, а его прямоугольник на экране
#      берётся у самого X-сервера (xwininfo) и пересчитывается по множителю и смещению.
#      Так до и после сравнивается ОДИН И ТОТ ЖЕ участок стола, а не похожий.
#
#      Мера — перепад яркости между соседними точками снимка, в долях от полного размаха
#      255. Считаются все пары по горизонтали и вертикали, из них:
#         резкие  — доля пар с перепадом ≥ 96 (больше трети размаха): это настоящие
#                   границы, нарисованные точка в точку;
#         край    — 99-й процентиль перепада: насколько сильным вообще бывает переход.
#      Интерполяция бьёт по обоим сразу: она размазывает один сильный перепад в несколько
#      слабых. Числа с живых эмуляторов на пересобранном образе: 11,4 % и край 255 на всех
#      трёх геометриях (1080×2274, 1080×2211, 1280×744). Под рычагом порчи на планшете —
#      9,5 % и край 223: падение видно числом, но порогов не пробивает, потому что там
#      ужатие всего до 0,744. Красное на рычаге держит масштаб (см. tests/e2e/artifacts/sharpness-*).
#
# РЫЧАГ ПОРЧИ. STOL_SHARP_MUTATE=1 возвращает в образ на устройстве жёсткий размер
# (STOL_GEOMETRY=1600x1000 прямо в /opt/stol/start-gui.sh) и гоняет ту же проверку. Она
# обязана покраснеть на масштабе — это прямая проверка причины. Резкость под рычагом
# проседает, но порога не всегда пробивает: на планшете ужатие всего до 0,744. Файл
# возвращается на место в конце прогона, чем бы тот ни кончился.
#
# Запуск: bash tests/e2e/sharpness.sh
#   STOL_SHARP_SERIAL=emulator-5554   какое устройство брать
#   STOL_SHARP_MUTATE=1               прогон рычага порчи
#   STOL_SHARP_EDGE=200               порог 99-го процентиля перепада
#   STOL_SHARP_SHARE=8                порог доли резких пар, в процентах

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"
PKG="ru.codoceh.stol"

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
EDGE_MIN="${STOL_SHARP_EDGE:-200}"
SHARE_MIN="${STOL_SHARP_SHARE:-8}"
MUTATE="${STOL_SHARP_MUTATE:-0}"
TAG="codoceh-sharp-$$"
# Окно-мишень: одно и то же место и размер в точках стола. Влезает и в 1600×1000, и в
# узкий портрет телефона. Шрифт у xmessage — встроенный растровый X, его не трогает ни
# масштаб интерфейса, ни тема: мишень одинакова в любом прогоне.
WIN_GEOMETRY="420x250+40+40"
# Текст мишени плотный и только из ASCII: чем больше границ на единицу площади, тем
# честнее мера. Кириллица тут не нужна — растровый шрифт xmessage её не знает.
TARGET_TEXT="IIIIIIIIIIII llllllllllll 111111111111
MMMMMMMMMMMM WWWWWWWWWWWW ############
oOoOoOoOoOoO xXxXxXxXxXxX 010101010101
|_|_|_|_|_|_ /\\/\\/\\/\\/\\/\\ ============
IIIIIIIIIIII llllllllllll 111111111111
MMMMMMMMMMMM WWWWWWWWWWWW ############
oOoOoOoOoOoO xXxXxXxXxXxX 010101010101
|_|_|_|_|_|_ /\\/\\/\\/\\/\\/\\ ============
IIIIIIIIIIII llllllllllll 111111111111
MMMMMMMMMMMM WWWWWWWWWWWW ############
oOoOoOoOoOoO xXxXxXxXxXxX 010101010101
|_|_|_|_|_|_ /\\/\\/\\/\\/\\/\\ ============"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { CHECKS=$((CHECKS+1)); printf '    \033[32mОК\033[0m  %s\n' "$*"; }
bad()  { CHECKS=$((CHECKS+1)); FAIL=$((FAIL+1)); printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

CHECKS=0
FAIL=0

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb ($ADB)"
python3 -c "import PIL" >/dev/null 2>&1 || die "у python3 нет Pillow — снимок разобрать нечем"
[ -f "$HERE/store-points.py" ] || die "нет $HERE/store-points.py — прямоугольник стола выводить нечем"

TMP="$(mktemp -d)"
mkdir -p "$ART"

# ------------------------------------------------------------------ устройство

log "1. Устройство с развёрнутым столом"

SERIAL=""
while read -r serial state _; do
    [ "$state" = "device" ] || continue
    [ -z "${STOL_SHARP_SERIAL:-}" ] || [ "$serial" = "$STOL_SHARP_SERIAL" ] || continue
    [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || continue
    case "$("$ADB" -s "$serial" shell run-as "$PKG" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')" in
        *".stol-ready") SERIAL="$serial"; break ;;
    esac
done < <("$ADB" devices | tail -n +2)

[ -n "$SERIAL" ] || die "нет устройства с развёрнутым столом.
Поставьте APK и пройдите первую установку: bash tests/e2e/two-devices.sh"

SDK="$("$ADB" -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
DENSITY="$("$ADB" -s "$SERIAL" shell wm density | tr -d '\r' | sed -n 's/.*: *\([0-9]*\)$/\1/p' | tail -1)"
# Имя устройства в имени снимка: проверка гоняется по очереди на нескольких эмуляторах,
# и без него каждый следующий прогон стирал бы доказательство предыдущего.
AVD="$("$ADB" -s "$SERIAL" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
[ -n "$AVD" ] || AVD="$SERIAL"
DEV_TAG="${SDK}-${AVD}"
step "устройство $SERIAL, Android API $SDK, плотность экрана $DENSITY"

APK_PATH="$("$ADB" -s "$SERIAL" shell pm path "$PKG" | tr -d '\r' | sed 's/package://' | head -1)"
NATIVE="$(dirname "$APK_PATH")/lib/arm64"

# Запускалка команд внутри образа: повторяет ProotSession приложения.
"$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat > files/sharp-enter.sh'" <<'EOF'
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

# Команда внутри образа. Едет файлом, а не строкой в аргументах: adb склеивает аргументы
# обратно в строку и теряет вложенные кавычки.
in_image() {
    printf '%s\n' "$1" \
        | "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat > files/rootfs/tmp/sharp-cmd.sh'" >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh files/sharp-enter.sh '$NATIVE' sharp-cmd.sh" 2>&1 | tr -d '\r'
}

SCRIPT="files/rootfs/opt/stol/start-gui.sh"
cleanup() {
    in_image "pkill -f 'xmessage -title $TAG'" >/dev/null 2>&1
    if [ "$MUTATE" = "1" ]; then
        "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cp $SCRIPT.sharpbak $SCRIPT && rm -f $SCRIPT.sharpbak'" >/dev/null 2>&1
        step "жёсткий размер убран, скрипт запуска возвращён на место"
    fi
    "$ADB" -s "$SERIAL" shell "run-as $PKG rm -f files/sharp-enter.sh files/rootfs/tmp/sharp-cmd.sh files/rootfs/tmp/sharp-hold.sh" >/dev/null 2>&1
    rm -rf "$TMP"
}
trap cleanup EXIT

# ------------------------------------------------------------------ рычаг порчи

if [ "$MUTATE" = "1" ]; then
    log "1а. Рычаг порчи: возвращаем жёсткий размер 1600×1000 в образ"
    "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cp $SCRIPT $SCRIPT.sharpbak'" >/dev/null 2>&1
    in_image "sed -i 's|^STOL_GEOMETRY=.*|STOL_GEOMETRY=1600x1000|' /opt/stol/start-gui.sh
grep -n '^STOL_GEOMETRY=' /opt/stol/start-gui.sh" | sed 's/^/    /'
fi

# ------------------------------------------------------------------ стол начисто

log "2. Поднимаем стол начисто"

"$ADB" -s "$SERIAL" shell am force-stop "$PKG" >/dev/null 2>&1
sleep 2
"$ADB" -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1

# Готовность — прямоугольником стола в дереве окон: пока стол не показан, вида нет.
frame_map() {
    "$ADB" -s "$SERIAL" shell rm -f /sdcard/stol-frame.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/stol-frame.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell cat /sdcard/stol-frame.xml > "$TMP/frame.xml" 2>/dev/null
    [ -s "$TMP/frame.xml" ] || return 1
    python3 "$HERE/store-points.py" frame --dump "$TMP/frame.xml" \
        --desk-width "$DESK_W" --desk-height "$DESK_H" --package "$PKG" 2>/dev/null
}

# Числа берутся ТОЛЬКО из строки dimensions и проверяются на вид. Без проверки сюда
# приезжает единица из «Can\'t open display :1», пока X-сервер ещё не поднят, — и вся
# дальнейшая арифметика идёт по выдуманному размеру стола.
DESK_W=""; DESK_H=""
waited=0
until [[ "$DESK_W" =~ ^[0-9]+$ ]] && [[ "$DESK_H" =~ ^[0-9]+$ ]]; do
    DIMS="$(in_image 'xdpyinfo > /tmp/sharp-xd.txt; grep dimensions /tmp/sharp-xd.txt' \
        | sed -n 's/^ *dimensions: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | head -1)"
    DESK_W="${DIMS%% *}"
    DESK_H="${DIMS##* }"
    [[ "$DESK_W" =~ ^[0-9]+$ ]] && [[ "$DESK_H" =~ ^[0-9]+$ ]] && break
    waited=$((waited + 5))
    [ "$waited" -lt 240 ] || die "стол не поднялся за $waited с — X-сервер не назвал свой размер"
    sleep 5
done
step "X-сервер поднят: ${DESK_W}×${DESK_H}"

MAP=""
waited=0
until [ -n "$MAP" ]; do
    MAP="$(frame_map)"
    [ -n "$MAP" ] && break
    waited=$((waited + 5))
    [ "$waited" -lt 120 ] || die "стол не показан на экране за $waited с — вида в дереве окон нет"
    sleep 5
done
SCALE="$(sed -n 's/^SCALE=//p' <<<"$MAP")"
OFF_X="$(sed -n 's/^OFFSET_X=//p' <<<"$MAP")"
OFF_Y="$(sed -n 's/^OFFSET_Y=//p' <<<"$MAP")"
VIEW="$(sed -n 's/^VIEW=//p' <<<"$MAP")"
VIEW_W="${VIEW%%x*}"
VIEW_H="${VIEW#*x}"; VIEW_H="${VIEW_H%%+*}"

# ------------------------------------------------------------------ масштаб

log "3. Масштаб: одна точка стола — одна точка экрана"

step "стол ${DESK_W}×${DESK_H}, вид ${VIEW_W}×${VIEW_H}, множитель ${SCALE}"

if [ "$DESK_W" = "$VIEW_W" ] && [ "$DESK_H" = "$VIEW_H" ]; then
    ok "размер стола совпадает с размером вида: ${DESK_W}×${DESK_H}"
else
    bad "стол ${DESK_W}×${DESK_H} поднят не в размер вида ${VIEW_W}×${VIEW_H} — картинку придётся вписывать"
fi

if [ "$SCALE" = "1.000000" ]; then
    ok "множитель ровно 1,000000 — растягивать нечего, интерполировать нечем"
else
    bad "множитель ${SCALE}, а нужен 1,000000: каждая точка экрана собрана из нескольких точек стола"
fi

# ------------------------------------------------------------------ мишень

log "4. Мишень на столе: одно и то же окно в одном и том же месте"

in_image "pkill -f 'xmessage -title codoceh-sharp'" >/dev/null 2>&1
printf '%s\n' "xmessage -title $TAG -geometry $WIN_GEOMETRY '$TARGET_TEXT' &
sleep 300" \
    | "$ADB" -s "$SERIAL" shell "run-as $PKG sh -c 'cat > files/rootfs/tmp/sharp-hold.sh'" >/dev/null 2>&1
# Сессия обязана жить: proot уносит с собой всё, что породил.
"$ADB" -s "$SERIAL" shell "run-as $PKG sh files/sharp-enter.sh '$NATIVE' sharp-hold.sh" >/dev/null 2>&1 &

WIN=""
waited=0
until [ -n "$WIN" ]; do
    WIN="$(in_image 'wmctrl -l' | awk -v t=" $TAG\$" '$0 ~ t { print $1 }' | head -1)"
    [ -n "$WIN" ] && break
    waited=$((waited + 3))
    [ "$waited" -lt 60 ] || die "окно-мишень $TAG не появилось на столе за $waited с"
    sleep 3
done

# Прямоугольник мишени берём у самого X-сервера: где бы менеджер окон её ни поставил,
# числа будут настоящими и в настоящих точках стола.
GEOM="$(in_image "xwininfo -id $WIN > /tmp/sharp-win.txt; grep -E 'Absolute upper-left|Width:|Height:' /tmp/sharp-win.txt")"
WIN_X="$(sed -n 's/.*Absolute upper-left X: *\([0-9-]*\).*/\1/p' <<<"$GEOM" | head -1)"
WIN_Y="$(sed -n 's/.*Absolute upper-left Y: *\([0-9-]*\).*/\1/p' <<<"$GEOM" | head -1)"
WIN_W="$(sed -n 's/.*Width: *\([0-9]*\).*/\1/p' <<<"$GEOM" | head -1)"
WIN_H="$(sed -n 's/.*Height: *\([0-9]*\).*/\1/p' <<<"$GEOM" | head -1)"
[ -n "$WIN_X" ] && [ -n "$WIN_W" ] || die "X-сервер не назвал прямоугольник мишени: $GEOM"
step "мишень на столе: ${WIN_W}×${WIN_H} в точке +${WIN_X}+${WIN_Y}"

# ------------------------------------------------------------------ резкость

log "5. Резкость: перепад яркости между соседними точками экрана"

SHOT="$ART/sharpness-screen-${DEV_TAG}.png"
CROP="$ART/sharpness-target-${DEV_TAG}.png"
[ "$MUTATE" = "1" ] && { SHOT="$ART/sharpness-screen-${DEV_TAG}-zhestko.png"; CROP="$ART/sharpness-target-${DEV_TAG}-zhestko.png"; }
sleep 2
"$ADB" -s "$SERIAL" exec-out screencap -p > "$SHOT" 2>/dev/null
[ -s "$SHOT" ] || die "снимок экрана не снялся"

NUMBERS="$(python3 - "$SHOT" "$CROP" "$OFF_X" "$OFF_Y" "$SCALE" "$WIN_X" "$WIN_Y" "$WIN_W" "$WIN_H" <<'PY'
import sys
from PIL import Image

shot, crop_path, off_x, off_y, scale, wx, wy, ww, wh = sys.argv[1:10]
off_x, off_y, scale = float(off_x), float(off_y), float(scale)
wx, wy, ww, wh = int(wx), int(wy), int(ww), int(wh)

# Точка стола → точка экрана. Тот же пересчёт, что делает сам Viewport приложения.
left = int(round(off_x + wx * scale))
top = int(round(off_y + wy * scale))
right = int(round(off_x + (wx + ww) * scale))
bottom = int(round(off_y + (wy + wh) * scale))

image = Image.open(shot).convert("L")
left, top = max(0, left), max(0, top)
right, bottom = min(image.width, right), min(image.height, bottom)
if right - left < 16 or bottom - top < 16:
    sys.stderr.write("мишень не попала на экран: %d,%d..%d,%d\n" % (left, top, right, bottom))
    raise SystemExit(1)

patch = image.crop((left, top, right, bottom))
patch.save(crop_path)

w, h = patch.size
px = patch.load()
deltas = []
for y in range(h):
    for x in range(w - 1):
        deltas.append(abs(px[x, y] - px[x + 1, y]))
for y in range(h - 1):
    for x in range(w):
        deltas.append(abs(px[x, y] - px[x, y + 1]))

deltas.sort()
total = len(deltas)
sharp = sum(1 for d in deltas if d >= 96)
edge = deltas[int(0.99 * (total - 1))]

print("CROP=%dx%d+%d+%d" % (right - left, bottom - top, left, top))
print("SHARE=%.1f" % (100.0 * sharp / total))
print("EDGE=%d" % edge)
PY
)" || die "снимок не разобран — мишень не попала на экран"

CROP_RECT="$(sed -n 's/^CROP=//p' <<<"$NUMBERS")"
SHARE="$(sed -n 's/^SHARE=//p' <<<"$NUMBERS")"
EDGE="$(sed -n 's/^EDGE=//p' <<<"$NUMBERS")"

step "участок экрана под мишенью: $CROP_RECT"
step "снимки: $SHOT и $CROP"

if awk "BEGIN { exit !($SHARE >= $SHARE_MIN) }"; then
    ok "резких переходов ${SHARE} % при пороге ${SHARE_MIN} %"
else
    bad "резких переходов всего ${SHARE} % при пороге ${SHARE_MIN} % — границы размазаны"
fi

if [ "$EDGE" -ge "$EDGE_MIN" ]; then
    ok "край границы ${EDGE} из 255 при пороге ${EDGE_MIN}"
else
    bad "край границы ${EDGE} из 255 при пороге ${EDGE_MIN} — переход из чёрного в белое собран из серых"
fi

# ------------------------------------------------------------------ итог

printf '\n'
if [ "$FAIL" -eq 0 ]; then
    printf '\033[32mЗЕЛЁНО\033[0m  проверок пройдено: %d. Стол %s точка в точку, резких переходов %s %%, край %s.\n' \
        "$CHECKS" "${DESK_W}×${DESK_H}" "$SHARE" "$EDGE"
    exit 0
fi
printf '\033[31mКРАСНО\033[0m  проверок %d, из них не прошло %d.\n' "$CHECKS" "$FAIL"
exit 1
