#!/usr/bin/env bash
#
# tests/e2e/warm-start.sh — сколько ждать стола, когда он уже установлен.
#
# Первый запуск разворачивает образ и идёт минуты. Это цена установки, её платят
# один раз. Дальше человек просто открывает приложение — и вот это время он видит
# каждый день. Проверка меряет именно его: образ на месте, приложение снято с памяти.
#
# Замер идёт на самом устройстве (warm-probe.sh): секунды из /proc/uptime, события
# из /proc/net/tcp. Снаружи мерить нельзя — одно обращение по adb и один снимок
# экрана стоят секунды, а весь ответ измеряется секундами.
#
# Три числа на каждый заход:
#   стол      — XFCE нарисовал панель и рабочий стол, маячок открыл порт
#   картинка  — стол виден на экране телефона (досматривается снимками, отсюда
#               грубее: один снимок на эмуляторе стоит секунды)
#   возврат   — свёрнутое приложение открыли снова, стол при этом не гас
#
# Перед каждым заходом стол гасится и проверяется, что порты закрыты. Иначе
# следующий заход померил бы уже поднятый стол и показал бы прекрасные ноль секунд.
#
# Бюджет взят из терпения человека и задан для ЖИВОГО ТЕЛЕФОНА. На эмуляторе
# число будет заметно хуже: там медленный диск, а весь запуск в него и упирается.
# Гоняя на эмуляторе, ставьте свой бюджет через STOL_WARM_BUDGET — но в продукте
# планку не поднимайте, чините запуск.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"
PKG="ru.codoceh.stol"

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
RUNS="${STOL_WARM_RUNS:-3}"
WARM_BUDGET_S="${STOL_WARM_BUDGET:-25}"
RESUME_BUDGET_MS="${STOL_RESUME_BUDGET:-1500}"
PROBE_LIMIT_S="${STOL_WARM_LIMIT:-600}"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

secs() { printf '%d,%02d с' $(( $1 / 100 )) $(( $1 % 100 )); }

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb ($ADB)."
python3 -c "import PIL" >/dev/null 2>&1 || die "у python3 нет Pillow — снимок экрана нечем разобрать."
[ -f "$HERE/warm-probe.sh" ] || die "рядом нет warm-probe.sh — мерить нечем."
mkdir -p "$ART"

# ------------------------------------------------------------------ устройства

# Список собирается ЦЕЛИКОМ и только потом обходится. Вызвать adb внутри цикла,
# который читает список из потока, нельзя: adb дочитывает этот же поток за себя,
# и все устройства кроме первого молча пропадают.
log "1. Ищем устройства с установленным столом"

declare -a FOUND=()
while read -r serial state _; do
    [ "$state" = "device" ] && FOUND+=("$serial")
done < <("$ADB" devices | tail -n +2)

declare -a SERIALS=() SDKS=() NAMES=()
for serial in "${FOUND[@]}"; do
    if [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
        bad "$serial: система ещё не загрузилась"
        continue
    fi
    if [ -z "$("$ADB" -s "$serial" shell pm path "$PKG" 2>/dev/null | tr -d '\r')" ]; then
        bad "$serial: приложение не установлено — мерить нечего"
        continue
    fi
    ready="$("$ADB" -s "$serial" shell run-as "$PKG" ls files/rootfs/.stol-ready 2>&1 | tr -d '\r')"
    case "$ready" in
        *".stol-ready")
            sdk="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
            rel="$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
            avd="$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
            [ -n "$avd" ] || avd="$serial"
            ok "$serial: $avd, Android $rel (API $sdk), образ развёрнут"
            SERIALS+=("$serial"); SDKS+=("$sdk"); NAMES+=("$avd")
            ;;
        *)
            bad "$serial: образ не развёрнут ($ready) — это ещё первая установка, а не повторный запуск"
            ;;
    esac
done

[ "${#SERIALS[@]}" -ge 1 ] || die "нет ни одного устройства с уже установленным столом.
Сначала пройдите первую установку — она же и есть доказательство R01:
  bash tests/e2e/two-devices.sh"

# ------------------------------------------------------------------ гашение

# Сначала по-хорошему: метку остановки видит сама сессия и заканчивается по своей
# воле. Потом force-stop — он снимает все процессы пакета, а вместе с ними и стол.
# Возвращаемся только убедившись, что порты закрыты: незакрытый порт означает
# живой стол, и следующий замер оказался бы замером пустоты.
quench() {
    local serial="$1" waited=0
    local run_dir="/data/user/0/$PKG/files/rootfs/run/stol"
    # Кавычки обязаны дойти до устройства целыми: adb склеивает команду обратно
    # в строку, и без внешних кавычек `sh -c` получает только первое слово.
    "$ADB" -s "$serial" shell "run-as $PKG sh -c 'mkdir -p $run_dir && touch $run_dir/stop'" >/dev/null 2>&1
    sleep 3
    "$ADB" -s "$serial" shell am force-stop "$PKG" >/dev/null 2>&1
    "$ADB" -s "$serial" shell "run-as $PKG sh -c 'rm -f $run_dir/stop'" >/dev/null 2>&1
    while [ "$waited" -lt 60 ]; do
        local live
        live="$("$ADB" -s "$serial" shell "ps -A -o ARGS" 2>/dev/null | grep -c 'Xtigervnc')"
        [ "$live" = "0" ] && return 0
        sleep 2
        waited=$((waited + 2))
    done
    return 1
}

# ------------------------------------------------------------------ картинка

# Третьим доводом можно попросить молчания: пока стол дорисовывается, «на экране
# не стол» — не отказ, а обычный кадр ожидания, и в отчёте ему не место.
drawn() {
    local serial="$1" tag="$2" quiet="${3:-}"
    local shot="$ART/warm-$tag.png" dump="$ART/warm-$tag.xml"
    "$ADB" -s "$serial" exec-out screencap -p >"$shot" 2>/dev/null
    "$ADB" -s "$serial" shell rm -f /sdcard/stol-warm.xml >/dev/null 2>&1
    "$ADB" -s "$serial" shell uiautomator dump /sdcard/stol-warm.xml >/dev/null 2>&1
    "$ADB" -s "$serial" shell cat /sdcard/stol-warm.xml >"$dump" 2>/dev/null
    local state verdict
    state="$(python3 "$HERE/screen-state.py" "$dump" "$shot" "$PKG" 2>&1)"
    verdict="$(sed -n 's/^VERDICT=//p' <<<"$state")"
    [ "$verdict" = "DRAWN" ] && return 0
    [ -n "$quiet" ] || bad "на экране не стол: $(sed -n 's/^TEXT=//p' <<<"$state") (вердикт ${verdict:-нечитаемо})"
    return 1
}

median() {
    printf '%s\n' "$@" | sort -n | sed -n "$(( ($# + 1) / 2 ))p"
}

# ------------------------------------------------------------------ замер

log "2. Повторный запуск: стол установлен, приложение снято с памяти"

FAILED=0
declare -a SUMMARY=()

for i in "${!SERIALS[@]}"; do
    serial="${SERIALS[$i]}"; sdk="${SDKS[$i]}"; avd="${NAMES[$i]}"
    log "Устройство $avd ($serial), Android API $sdk"

    "$ADB" -s "$serial" push "$HERE/warm-probe.sh" /data/local/tmp/warm-probe.sh >/dev/null 2>&1 \
        || { bad "не удалось положить замерщик на устройство"; FAILED=$((FAILED+1)); continue; }

    declare -a BEACONS=() SCREENS=() RESUMES=()
    device_failed=0

    for run in $(seq 1 "$RUNS"); do
        quench "$serial" || { bad "стол не погас за 60 с — замер был бы поддельным"; device_failed=1; break; }

        out="$("$ADB" -s "$serial" shell sh /data/local/tmp/warm-probe.sh "$PROBE_LIMIT_S" 2>&1 | tr -d '\r')"
        err="$(sed -n 's/^ERR=//p' <<<"$out")"
        if [ -n "$err" ]; then
            bad "заход $run: $err"
            device_failed=1
            break
        fi
        beacon="$(sed -n 's/^BEACON_CS=//p' <<<"$out")"
        if ! [[ "$beacon" =~ ^[0-9]+$ ]]; then
            bad "заход $run: замерщик не назвал время ($out)"
            device_failed=1
            break
        fi

        # Маячок открылся — приложению осталось подключиться и отрисовать кадр.
        # Это досматривается снимками экрана, поэтому и меряется грубее: цена
        # одного снимка на эмуляторе — те же секунды.
        painted=0
        until drawn "$serial" "$sdk-$avd-$run" тихо; do
            painted=$((painted + 1))
            if [ "$painted" -ge 15 ]; then
                drawn "$serial" "$sdk-$avd-$run"
                bad "заход $run: маячок открылся, а картинки на экране так и нет"
                device_failed=1
                break 2
            fi
            sleep 2
        done
        screen=$((beacon + painted * 200))

        step "заход $run: стол $(secs "$beacon"), картинка на экране $(secs "$screen")"
        BEACONS+=("$beacon"); SCREENS+=("$screen")

        # Возврат к работающему столу: свернули и открыли снова. Стол при этом
        # не гас, поэтому ждать нечего — и это должно быть видно в числах.
        "$ADB" -s "$serial" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
        sleep 1
        w="$("$ADB" -s "$serial" shell am start -W -n "$PKG/.MainActivity" 2>/dev/null | tr -d '\r')"
        total="$(sed -n 's/^TotalTime: //p' <<<"$w")"
        [[ "$total" =~ ^[0-9]+$ ]] || total="$(sed -n 's/^WaitTime: //p' <<<"$w")"
        if [[ "$total" =~ ^[0-9]+$ ]]; then
            step "заход $run: возврат ${total} мс"
            RESUMES+=("$total")
        else
            bad "заход $run: Android не назвал время возврата"
            device_failed=1
            break
        fi
    done

    # Журнал стола пишет отметки времени сам — по нему видно, на чём стоим.
    "$ADB" -s "$serial" shell run-as "$PKG" cat files/rootfs/run/stol/start.log 2>/dev/null \
        | tr -d '\r' > "$ART/warm-start-$sdk-$avd.log"

    quench "$serial" >/dev/null 2>&1

    if [ "$device_failed" -ne 0 ] || [ "${#SCREENS[@]}" -eq 0 ]; then
        FAILED=$((FAILED+1))
        SUMMARY+=("API $sdk ($avd): замер не состоялся")
        continue
    fi

    mb="$(median "${BEACONS[@]}")"
    ms="$(median "${SCREENS[@]}")"
    mr="$(median "${RESUMES[@]}")"

    line="API $sdk ($avd): картинка $(secs "$ms") (стол готов $(secs "$mb")), возврат ${mr} мс"
    if [ "$ms" -gt $((WARM_BUDGET_S * 100)) ]; then
        bad "медиана повторного запуска $(secs "$ms") — больше бюджета ${WARM_BUDGET_S} с"
        step "журнал стола с отметками времени: $ART/warm-start-$sdk-$avd.log"
        FAILED=$((FAILED+1))
        line="$line — ДОЛГО"
    elif [ "$mr" -gt "$RESUME_BUDGET_MS" ]; then
        bad "медиана возврата ${mr} мс — больше бюджета ${RESUME_BUDGET_MS} мс"
        FAILED=$((FAILED+1))
        line="$line — ДОЛГО"
    else
        ok "уложились: стол $(secs "$ms") при бюджете ${WARM_BUDGET_S} с, возврат ${mr} мс"
    fi
    SUMMARY+=("$line")
done

echo
log "Итог"
for l in "${SUMMARY[@]}"; do step "$l"; done

[ "$FAILED" -eq 0 ] || die "повторный запуск не уложился в бюджет или не состоялся.
Снимки экрана: $ART/warm-*.png"

printf '\n\033[32mЗЕЛЁНО\033[0m  повторный запуск укладывается в %s с, возврат в %s мс (устройств: %d, заходов: %d)\n' \
    "$WARM_BUDGET_S" "$RESUME_BUDGET_MS" "${#SERIALS[@]}" "$RUNS"
exit 0
