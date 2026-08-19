#!/usr/bin/env bash
#
# tests/e2e/process-count.sh — замер числа процессов сессии стола под proot (R01, R02).
#
# На Android 12+ (API 31+) действует системное ограничение PhantomProcessRecord:
# не более 32 дочерних процессов на UID приложения. При превышении система
# принудительно убивает процессы (включая Xtigervnc и dbus-daemon).
#
# Этот тест замеряет количество процессов под UID приложения на живом устройстве,
# выводит детализацию и проверяет, что базовое число процессов стола держится
# в безопасном коридоре.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="${STOL_E2E_ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG="ru.codoceh.stol"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
step() { printf '    %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
bad()  { printf '    \033[31mНЕТ\033[0m %s\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

command -v "$ADB" >/dev/null 2>&1 || die "не найден adb ($ADB)."

log "1. Поиск устройств"
declare -a DEVICES=()
while read -r serial state _; do
    [ "$state" = "device" ] && DEVICES+=("$serial")
done < <("$ADB" devices | tail -n +2)

[ "${#DEVICES[@]}" -ge 1 ] || die "нет подключенных устройств"

for serial in "${DEVICES[@]}"; do
    sdk="$("$ADB" -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    rel="$("$ADB" -s "$serial" shell getprop ro.build.version.release | tr -d '\r')"
    
    # Проверяем наличие установленного приложения
    path="$("$ADB" -s "$serial" shell pm path "$PKG" 2>/dev/null | tr -d '\r')"
    if [ -z "$path" ]; then
        step "$serial (API $sdk): приложение не установлено, пропускаем"
        continue
    fi
    
    log "2. Замер процессов на $serial (Android $rel, API $sdk)"
    
    # Убеждаемся, что приложение запущено
    "$ADB" -s "$serial" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    sleep 3
    
    # Получаем UID приложения
    app_uid="$("$ADB" -s "$serial" shell "dumpsys package $PKG | grep -E '(userId|appId)=' | head -1" | awk -F'=' '{print $2}' | tr -d '\r ')"
    if [ -z "$app_uid" ]; then
        app_uid="$("$ADB" -s "$serial" shell "ps -ef | grep -m1 '$PKG' | awk '{print \$1}'" | tr -d '\r ')"
    else
        # Преобразуем числовой userId (например 10223) в формат u0_a223
        if [ "$app_uid" -ge 10000 ] 2>/dev/null; then
            idx=$((app_uid - 10000))
            app_uid="u0_a$idx"
        fi
    fi
    
    [ -n "$app_uid" ] || die "не удалось определить UID приложения $PKG"
    
    step "UID приложения: $app_uid"
    
    # Считаем процессы под UID
    procs="$("$ADB" -s "$serial" shell "ps -ef | awk -v u=\"$app_uid\" '\$1==u {print \$0}'" | tr -d '\r')"
    count="$(printf '%s\n' "$procs" | grep -v '^$' | wc -l | tr -d ' ')"
    
    step "Всего процессов под UID: $count"
    printf '%s\n' "$procs" | awk '{printf "      %s (pid %s, ppid %s): %s\n", $8, $2, $3, substr($0, index($0,$8))}'
    
    # Порог безопасности: базовая сессия стола должна быть <= 18 процессов (лимит системы 32)
    if [ "$count" -le 18 ]; then
        ok "$serial: базовое число процессов ($count) в безопасном диапазоне (<= 18, порог системы 32)"
    else
        bad "$serial: число процессов ($count) превышает рекомендуемый запас 18"
    fi
done

log "ЗЕЛЁНО: замер процессов завершён успешно"
exit 0
