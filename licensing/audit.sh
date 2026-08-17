#!/usr/bin/env bash
#
# licensing/audit.sh — ревизия прав, лицензий и проверка отсутствия сирот (R16, R10i).
#

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
die()  { printf '\033[31mОШИБКА: %s\033[0m\n' "$*" >&2; exit 1; }

PASS=0

# 1. Проверка отсутствия сиротских библиотек (libdash.so)
log "1. Проверка отсутствия сирот (libdash.so удалён)"
if find app/src/main/jniLibs/ app/src/main/assets/ -name "libdash.so" 2>/dev/null | grep -q .; then
    die "Сиротский файл libdash.so всё ещё присутствует в репозитории"
fi
ok "сирота libdash.so отсутствует в сборке"
PASS=$((PASS + 1))

# 2. Проверка реестра заимствований и соответствия файлам
log "2. Проверка реестра заимствований и лицензий через unit-тесты"
./gradlew :app:testDebugUnitTest --tests '*About*' --tests '*Offer*' --tests '*NativeBinaries*' >/dev/null 2>&1 \
    || ./gradlew :app:testDebugUnitTest --tests '*About*' --tests '*Offer*' --tests '*NativeBinaries*'

ok "реестр заимствований строго совпадает с файлами и зависимостями сборки"
PASS=$((PASS + 1))

# 3. Проверка наличия и структуры письменного предложения исходников
log "3. Проверка письменного предложения исходников (R16b, R16.5)"
OFFER_FILE="app/src/main/assets/about/source-offer.txt"
[ -f "$OFFER_FILE" ] || die "Файл $OFFER_FILE отсутствует"
[ "$(wc -c < "$OFFER_FILE")" -gt 500 ] || die "Файл $OFFER_FILE слишком короткий"
grep -q "GNU GPL" "$OFFER_FILE" || die "В предложении нет упоминания GNU GPL"
grep -q "три года" "$OFFER_FILE" || die "В предложении нет упоминания 3-летнего срока"

ok "письменное предложение исходников сформировано и проверено"
PASS=$((PASS + 1))

echo
printf '\033[32mЗЕЛЁНО\033[0m  все проверки ревизии прав пройдены (%d/3)\n' "$PASS"
exit 0
