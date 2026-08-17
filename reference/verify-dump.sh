#!/usr/bin/env bash
# Проверяет сам дамп эталона, не ходя на телефон.
# Запуск: bash reference/verify-dump.sh
set -u

REF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FAIL=0
CHECKS=0

ok()   { CHECKS=$((CHECKS+1)); printf 'ok   %s\n' "$1"; }
bad()  { CHECKS=$((CHECKS+1)); FAIL=$((FAIL+1)); printf 'FAIL %s\n' "$1"; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

nonempty() { [ -f "$1" ] && [ -s "$1" ]; }

# 1. Пять артефактов на месте и непустые
for f in wallpaper.png wallpaper.sha256 screenshot.png README.md; do
  if nonempty "$REF/$f"; then ok "есть и непустой: $f"; else bad "нет или пустой: $f"; fi
done
for d in xfconf desktop-entries; do
  if [ -d "$REF/$d" ]; then ok "есть каталог: $d/"; else bad "нет каталога: $d/"; fi
done

# 2. sha256 обоев сходится
if nonempty "$REF/wallpaper.png" && nonempty "$REF/wallpaper.sha256"; then
  EXPECTED=$(awk '{print $1}' "$REF/wallpaper.sha256" | head -1)
  ACTUAL=$(sha256_of "$REF/wallpaper.png")
  if [ -n "$EXPECTED" ] && [ "$EXPECTED" = "$ACTUAL" ]; then
    ok "sha256 обоев сходится: $ACTUAL"
  else
    bad "sha256 обоев расходится: в файле '$EXPECTED', посчитано '$ACTUAL'"
  fi
else
  bad "sha256 обоев проверить нечем"
fi

# 3. Четыре канала xfconf, в каждом есть значения
for c in xsettings xfwm4 xfce4-desktop xfce4-panel; do
  F="$REF/xfconf/$c.txt"
  if ! nonempty "$F"; then
    bad "канал $c: файла нет или он пуст"
  else
    N=$(grep -c '^/' "$F" 2>/dev/null || echo 0)
    if [ "$N" -gt 0 ]; then ok "канал $c: $N значений"; else bad "канал $c: ни одного значения"; fi
  fi
done

# 4. Ровно те ярлыки, что перечислены в спеке
EXPECTED_NAMES=$(printf '%s\n' \
  "Antigravity IDE" "Antigravity 2.0" "Claude" "Visual Studio Code" "Obsidian" "ZCode" "Gemini" | sort)
# берём только Name= из секции [Desktop Entry] — первый в файле; Name= из [Desktop Action ...] не в счёт
ACTUAL_NAMES=$(for f in "$REF"/desktop-entries/*.desktop; do
  [ -f "$f" ] && grep -m1 '^Name=' "$f" | sed 's/^Name=//'
done | sort)
COUNT=$(ls -1 "$REF"/desktop-entries/*.desktop 2>/dev/null | wc -l | tr -d ' ')
if [ "$COUNT" = "7" ]; then ok "ярлыков ровно 7"; else bad "ярлыков $COUNT, ожидалось 7"; fi
if [ "$EXPECTED_NAMES" = "$ACTUAL_NAMES" ]; then
  ok "состав ярлыков совпадает со спекой"
else
  bad "состав ярлыков не совпадает со спекой"
  printf '     ожидалось: %s\n' "$(echo "$EXPECTED_NAMES" | tr '\n' '|')"
  printf '     в дампе:   %s\n' "$(echo "$ACTUAL_NAMES" | tr '\n' '|')"
fi

# 5. Ни в одном текстовом файле дампа нет строк, похожих на токен или ключ
SECRET_RE='sk-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|[0-9]{8,10}:AA[A-Za-z0-9_-]{30,}|(api[_-]?key|secret|token|password|passwd)[[:space:]]*[=:][[:space:]]*[A-Za-z0-9/+_-]{16,}'
HITS=$(grep -rEIn "$SECRET_RE" "$REF" --exclude='verify-dump.sh' 2>/dev/null | grep -v '^Binary file')
if [ -z "$HITS" ]; then
  ok "секретов в дампе не найдено"
else
  bad "в дампе есть строки, похожие на токен или ключ"
  printf '%s\n' "$HITS" | cut -c1-120 | sed 's/^/     /'
fi

echo "---"
if [ "$FAIL" -eq 0 ]; then
  echo "OK: проверок $CHECKS, провалов 0"
  exit 0
else
  echo "ПРОВАЛ: проверок $CHECKS, провалов $FAIL"
  exit 1
fi
