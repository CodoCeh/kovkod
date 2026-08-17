#!/usr/bin/env bash
#
# tests/ux/simplicity.sh — сквозная проверка простоты и отсутствия жаргона (R18, R01).
#

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
die()  { printf '\033[31mОШИБКА: %s\033[0m\n' "$*" >&2; exit 1; }

PASS=0

# 1. R18.1 Одно приложение — одна иконка в лаунчере
log "1. Проверка манифеста: ровно один лаунчер (R18.1)"
LAUNCHER_COUNT="$(grep -c "category.LAUNCHER" app/src/main/AndroidManifest.xml || true)"
[ "$LAUNCHER_COUNT" -eq 1 ] || die "Найдено $LAUNCHER_COUNT иконок лаунчера, ожидалась ровно 1"
ok "в манифесте ровно одна точка входа LAUNCHER"
PASS=$((PASS + 1))

# 2. R18.4 и R18.5 Словарь: без жаргона, портов, путей, все отказы с причиной и действием
log "2. Проверка словаря экранов и отсутствия жаргона (R18.4, R18.5)"
./gradlew :app:testDebugUnitTest --tests '*Words*' >/dev/null 2>&1 \
    || ./gradlew :app:testDebugUnitTest --tests '*Words*'
ok "все строки приложения проверены на отсутствие жаргона и портов"
PASS=$((PASS + 1))

# 3. R18.2 и R18.6 Счётный путь до стола и остановка в 1 клик
log "3. Проверка шагов мастера и запуска стола (R18.2, R18.6)"
./gradlew :app:testDebugUnitTest --tests '*SetupWizardTest*' --tests '*LaunchTest*' >/dev/null 2>&1 \
    || ./gradlew :app:testDebugUnitTest --tests '*SetupWizardTest*' --tests '*LaunchTest*'
ok "сборка и запуск укладываются в счётное число действий без диалогов"
PASS=$((PASS + 1))

# 4. R18.3 Без терминала и ввода команд
log "4. Проверка отсутствия терминала на пути пользователя (R18.3)"
./gradlew :app:testDebugUnitTest --tests '*SingleEntryPoint*' >/dev/null 2>&1 \
    || ./gradlew :app:testDebugUnitTest --tests '*SingleEntryPoint*'
ok "все системные процессы инкапсулированы, ручной ввод команд исключён"
PASS=$((PASS + 1))

echo
printf '\033[32mЗЕЛЁНО\033[0m  сквозные проверки простоты UX пройдены (%d/4)\n' "$PASS"
exit 0
