#!/usr/bin/env bash
#
# tests/e2e/two-devices.sh — живой прогон APK на двух версиях Android (R01).
#
# Проверка ставит собранный APK на настоящее устройство, запускает его и ждёт,
# пока на экране появится нарисованный стол. Доказательство — снятый с устройства
# снимок экрана в tests/e2e/artifacts/ и рядом с ним запись фактов об устройстве.
#
# Номер версии Android берётся с самого устройства (`getprop ro.build.version.sdk`),
# а не из кода проверки и не из build.gradle: иначе проверка доказывает не прогон,
# а собственную начитанность.
#
# Устройств должно быть два и с РАЗНЫМИ номерами версии. Ни одного — проверка
# краснеет и говорит, чем её накормить (R01.3). Ничего не запускает сама: эмулятор
# поднимается снаружи, и его отсутствие обязано быть видно.
#
#   ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api35 -no-snapshot -no-audio &
#   ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api30 -no-snapshot -no-audio &
#
# Времени нужно много: APK весит под гигабайт, а образ внутри него разворачивается
# в 1,27 ГБ, и на эмуляторе это минуты. Ожидания настраиваются переменными
# STOL_E2E_INSTALL_TIMEOUT и STOL_E2E_DESKTOP_TIMEOUT (секунды).

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$REPO/tests/e2e"
ART="$HERE/artifacts"

PKG="ru.codoceh.stol"
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"

INSTALL_TIMEOUT="${STOL_E2E_INSTALL_TIMEOUT:-1800}"
DESKTOP_TIMEOUT="${STOL_E2E_DESKTOP_TIMEOUT:-1800}"
POLL_EVERY=10

ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

# ------------------------------------------------------------------ инструменты

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb ($ADB).
Поставьте platform-tools из Android SDK или укажите путь в STOL_E2E_ADB."

command -v python3 >/dev/null 2>&1 || die "не найден python3 — снимок экрана нечем разобрать."

python3 -c "import PIL" >/dev/null 2>&1 || die "у python3 нет Pillow — снимок экрана нечем разобрать.
Поставьте: python3 -m pip install Pillow"

[ -f "$APK" ] || die "APK не собран: $APK отсутствует.
Соберите: bash ci/build-release.sh"

mkdir -p "$ART"

# ------------------------------------------------------------------ рычаг порчи

MUTATE="${STOL_TWO_DEVICES_MUTATE:-0}"
if [ "$MUTATE" = "1" ]; then
    log "0. Проверка рычага порчи: реакция на экран сборки/зависания (R04)"
    fake_dump="$ART/mutation_anr_dump.xml"
    fake_shot="$ART/mutation_anr_shot.png"
    cat <<'EOF' >"$fake_dump"
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy rotation="0">
  <node package="ru.codoceh.stol" text="Собираем стол, шаг 3 из 4" />
  <node package="android" text="Приложение КовКод не отвечает" />
</hierarchy>
EOF
    python3 -c "from PIL import Image; Image.new('RGB', (1080, 2274), color='red').save('$fake_shot')"
    state="$(python3 "$HERE/screen-state.py" "$fake_dump" "$fake_shot" "$PKG" 2>&1)"
    verdict="$(sed -n 's/^VERDICT=//p' <<<"$state")"
    text="$(sed -n 's/^TEXT=//p' <<<"$state")"
    rm -f "$fake_dump" "$fake_shot"
    if [ "$verdict" != "WAITING" ] || [[ "$text" != *"не отвечает"* ]]; then
        die "Рычаг порчи НЕ СРАБОТАЛ: экран сборки с ANR распознан как VERDICT=$verdict, TEXT=$text"
    fi
    ok "Рычаг порчи сработал: экран сборки с ANR честно распознан как WAITING и отклоняет ложную готовность"
    if [ "${STOL_TWO_DEVICES_MUTATE_ONLY:-0}" = "1" ]; then
        exit 0
    fi
fi

# ------------------------------------------------------------------ устройства

log "1. Ищем живые устройства"

SERIALS=()
while read -r serial state _; do
    [ "$state" = "device" ] || continue
    SERIALS+=("$serial")
done < <("$ADB" devices | tail -n +2)

if [ "${#SERIALS[@]}" -eq 0 ]; then
    die "не подключено ни одного устройства — проверять нечего.
Поднимите два эмулятора с разными версиями Android и запустите проверку снова:
  ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api35 -no-snapshot -no-audio &
  ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api30 -no-snapshot -no-audio &
Готовность устройства: adb wait-for-device shell getprop sys.boot_completed"
fi

# Версия берётся с устройства; здесь же отсеиваем недогрузившиеся.
declare -a READY_SERIALS=() READY_SDKS=() READY_NAMES=()
for serial in "${SERIALS[@]}"; do
    booted="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    sdk="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
    release="$("$ADB" -s "$serial" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
    abi="$("$ADB" -s "$serial" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')"
    avd="$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')"
    [ -n "$avd" ] || avd="$serial"

    if [ "$booted" != "1" ]; then
        bad "$serial: система ещё не загрузилась (sys.boot_completed=$booted)"
        continue
    fi
    if ! [[ "$sdk" =~ ^[0-9]+$ ]]; then
        bad "$serial: устройство не назвало версию Android"
        continue
    fi
    if [ "$abi" != "arm64-v8a" ]; then
        bad "$serial: архитектура $abi, а стол собран только под arm64-v8a"
        continue
    fi

    ok "$serial: $avd, Android $release (API $sdk), $abi"
    READY_SERIALS+=("$serial")
    READY_SDKS+=("$sdk")
    READY_NAMES+=("$avd")
done

[ "${#READY_SERIALS[@]}" -ge 2 ] || die "живых устройств ${#READY_SERIALS[@]}, а нужно два — на разных версиях Android.
Поднимите второй эмулятор:
  ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api30 -no-snapshot -no-audio &"

# Берём крайние версии: так проверка идёт по минимальной и по максимальной.
LOW=0
HIGH=0
for i in "${!READY_SERIALS[@]}"; do
    [ "${READY_SDKS[$i]}" -lt "${READY_SDKS[$LOW]}" ] && LOW=$i
    [ "${READY_SDKS[$i]}" -gt "${READY_SDKS[$HIGH]}" ] && HIGH=$i
done

[ "${READY_SDKS[$LOW]}" -ne "${READY_SDKS[$HIGH]}" ] || die "все устройства на одной версии Android (API ${READY_SDKS[$LOW]}).
Требование R01.2 — две РАЗНЫЕ версии. Поднимите эмулятор с другой:
  ~/Library/Android/sdk/emulator/emulator -avd stol_phone_api30 -no-snapshot -no-audio &"

ok "прогон пойдёт по API ${READY_SDKS[$LOW]} и API ${READY_SDKS[$HIGH]}"

# ------------------------------------------------------------------ прогон

# Ставит APK на устройство. Установка почти гигабайта идёт минутами, поэтому
# запускаем её в стороне и печатаем, сколько уже ждём.
install_apk() {
    local serial="$1"
    local out="$ART/install-$serial.log"

    # Прошлую установку сносим. Во-первых, обновление поверх требует места под вторую
    # копию APK, и на устройстве, где уже лежат почти гигабайт пакета и развёрнутый
    # образ, установка падает на нехватке памяти. Во-вторых, так каждый прогон ставит
    # APK начисто и разворачивает образ заново — а это и есть то, что мы доказываем.
    "$ADB" -s "$serial" uninstall "$PKG" >/dev/null 2>&1
    step "прошлая установка снесена, свободно $("$ADB" -s "$serial" shell df -h /data | tail -1 | awk '{print $4}')"

    "$ADB" -s "$serial" install -r -t "$APK" >"$out" 2>&1 &
    local pid=$!
    local waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$INSTALL_TIMEOUT" ]; then
            kill "$pid" 2>/dev/null
            bad "установка не уложилась в $INSTALL_TIMEOUT с, подробности в $out"
            return 1
        fi
        sleep "$POLL_EVERY"
        waited=$((waited + POLL_EVERY))
        step "ставим APK ($(du -h "$APK" | cut -f1)), идёт ${waited} с"
    done
    wait "$pid"
    local code=$?
    if [ "$code" -ne 0 ] || ! grep -q "Success" "$out"; then
        bad "adb install вернул $code: $(tr '\n' ' ' <"$out")"
        return 1
    fi

    local path
    path="$("$ADB" -s "$serial" shell pm path "$PKG" 2>/dev/null | tr -d '\r')"
    [ -n "$path" ] || { bad "после установки пакета $PKG на устройстве нет"; return 1; }

    ok "APK установлен: $path"
    return 0
}

# Готовность стола — строго подключение к маячку 5910.
# К порту 5901 подключаться нельзя вовсе: недочитанный сокет заносит 127.0.0.1 в
# чёрный список TigerVNC, и следующий настоящий клиент получает отказ.
beacon_open() {
    local serial="$1"
    "$ADB" -s "$serial" shell 'echo x | nc -w 1 127.0.0.1 5910 >/dev/null 2>&1 && echo OPEN || echo SHUT' \
        2>/dev/null | tr -d '\r' | grep -q OPEN
}

# Ждёт, пока приложение дорисует стол. Состояние экрана определяет screen-state.py
# по дереву окон и по самому снимку; готовность подтверждается маячком 5910.
wait_for_desktop() {
    local serial="$1" sdk="$2" avd="$3"
    local shot="$ART/android-$sdk-$avd.png"
    local dump="$ART/windows-$sdk-$avd.xml"
    local waited=0 verdict="" text="" distinct="" share=""

    while [ "$waited" -lt "$DESKTOP_TIMEOUT" ]; do
        "$ADB" -s "$serial" exec-out screencap -p >"$shot" 2>/dev/null
        # Файл дампа сносим заранее: uiautomator иногда отказывается снимать дерево
        # окон, и оставшийся от прошлого круга файл выдал бы вчерашний экран за
        # сегодняшний.
        "$ADB" -s "$serial" shell rm -f /sdcard/stol-windows.xml >/dev/null 2>&1
        "$ADB" -s "$serial" shell uiautomator dump /sdcard/stol-windows.xml >/dev/null 2>&1
        "$ADB" -s "$serial" shell cat /sdcard/stol-windows.xml >"$dump" 2>/dev/null

        local state
        state="$(python3 "$HERE/screen-state.py" "$dump" "$shot" "$PKG" 2>&1)"
        verdict="$(sed -n 's/^VERDICT=//p' <<<"$state")"
        text="$(sed -n 's/^TEXT=//p' <<<"$state")"
        distinct="$(sed -n 's/^DISTINCT=//p' <<<"$state")"
        share="$(sed -n 's/^TOPSHARE=//p' <<<"$state")"

        # Не сняли дерево окон — это ещё не приговор: ждём дальше и пробуем снова.
        # Приговор выносит общий срок ожидания внизу.
        if [ -z "$verdict" ]; then
            step "${waited} с: экран не прочитался, пробуем ещё"
            sleep "$POLL_EVERY"
            waited=$((waited + POLL_EVERY))
            continue
        fi

        # Приложение живо? Упавшее нечего ждать.
        if ! "$ADB" -s "$serial" shell pidof "$PKG" >/dev/null 2>&1; then
            bad "приложение $PKG больше не запущено — стол не дождались"
            return 1
        fi

        case "$verdict" in
            DRAWN)
                if ! beacon_open "$serial"; then
                    step "${waited} с: снимок похож на стол, но маячок 5910 ещё не открыт (XFCE ещё поднимается)"
                else
                    ok "стол нарисован и маячок 5910 ответил за ${waited} с: цветов $distinct, самый частый занимает $share"
                    return 0
                fi
                ;;
            WAITING)
                # Скобки вокруг имени обязательны: когда переменная упирается прямо
                # в неASCII-символ вроде закрывающей кавычки, разбор имени зависит
                # от локали — в одной печатает, в другой падает на unbound variable.
                step "${waited} с: на экране «${text}»"
                # Экраны отказа и системные диалоги зависания
                case "$text" in
                    *"не открылся"*|*"не собрался"*|*"не отвечает"*|*"isn't responding"*|*"Close app"*|*"Закрыть"*)
                        bad "приложение сдалось или зависло: «${text}»"
                        return 1
                        ;;
                esac
                ;;
            BLANK)
                step "${waited} с: стол ещё пуст (цветов $distinct, заливка $share)"
                ;;
        esac

        sleep "$POLL_EVERY"
        waited=$((waited + POLL_EVERY))
    done

    bad "стол не дорисовался за $DESKTOP_TIMEOUT с: последний вердикт $verdict, цветов $distinct, заливка $share"
    return 1
}

# Перезагрузка перед прогоном — не ритуал, а условие доказательства. Стол внутри
# образа живёт отдельными процессами, и оставшийся от прошлого раза Xtigervnc
# нарисовал бы «стол» без всякого участия APK. После перезагрузки на устройстве
# нет ни одного процесса образа, и поднять стол может только само приложение.
reboot_device() {
    local serial="$1"
    local waited=0

    "$ADB" -s "$serial" reboot >/dev/null 2>&1 || { bad "устройство не перезагрузилось"; return 1; }
    "$ADB" -s "$serial" wait-for-device >/dev/null 2>&1

    while [ "$waited" -lt 300 ]; do
        if [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            local leftovers
            leftovers="$("$ADB" -s "$serial" shell "ps -A -o ARGS" 2>/dev/null | grep -cE 'libproot\.so|Xtigervnc')"
            [ "$leftovers" = "0" ] || { bad "после перезагрузки на устройстве остались процессы образа ($leftovers)"; return 1; }
            ok "устройство перезагружено за ${waited} с, процессов образа не осталось"
            return 0
        fi
        sleep "$POLL_EVERY"
        waited=$((waited + POLL_EVERY))
        step "ждём загрузки системы, ${waited} с"
    done

    bad "система не загрузилась за 300 с"
    return 1
}

run_device() {
    local serial="$1" sdk="$2" avd="$3"

    log "Устройство $avd ($serial), Android API $sdk"

    reboot_device "$serial" || return 1
    install_apk "$serial" || return 1

    "$ADB" -s "$serial" shell am force-stop "$PKG" >/dev/null 2>&1
    "$ADB" -s "$serial" logcat -c >/dev/null 2>&1
    "$ADB" -s "$serial" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 \
        || { bad "приложение не запустилось"; return 1; }
    ok "приложение запущено"

    # Факты об устройстве пишем рядом со снимком, и пишем ДО ожидания: снимок без
    # них ничего не значит, а снимок неудачи нужен ровно так же, как снимок успеха.
    {
        echo "устройство: $serial"
        echo "эмулятор:   $avd"
        echo "API:        $sdk (получен с устройства, ro.build.version.sdk)"
        echo "Android:    $("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
        echo "ABI:        $("$ADB" -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
        echo "APK:        $APK"
        echo "снят:       $(date '+%Y-%m-%d %H:%M:%S %Z')"
    } >"$ART/android-$sdk-$avd.txt"

    wait_for_desktop "$serial" "$sdk" "$avd" || {
        "$ADB" -s "$serial" logcat -d >"$ART/logcat-$sdk-$avd.txt" 2>/dev/null
        step "журнал устройства: $ART/logcat-$sdk-$avd.txt"
        return 1
    }

    ok "снимок стола: $ART/android-$sdk-$avd.png"
    return 0
}

log "2. Прогон на двух версиях Android"

FAILED=0
PASSED=0
declare -a REPORT=()

for i in "$LOW" "$HIGH"; do
    if run_device "${READY_SERIALS[$i]}" "${READY_SDKS[$i]}" "${READY_NAMES[$i]}"; then
        REPORT+=("API ${READY_SDKS[$i]} (${READY_NAMES[$i]}): стол нарисован")
        PASSED=$((PASSED + 1))
    else
        REPORT+=("API ${READY_SDKS[$i]} (${READY_NAMES[$i]}): стола нет")
        FAILED=$((FAILED + 1))
    fi
done

echo
log "Итог"
for line in "${REPORT[@]}"; do
    step "$line"
done

if [ "$FAILED" -ne 0 ]; then
    die "прогон на двух версиях Android не пройден: $PASSED из 2.
Доказательства (снимки, дампы окон, журналы) лежат в $ART"
fi

printf '\n\033[32mЗЕЛЁНО\033[0m  стол поднялся на двух версиях Android (%d из 2), снимки в %s\n' \
    "$PASSED" "$ART"
exit 0
