#!/bin/sh
# Запуск Windows-программы двойным кликом по .exe/.msi/.bat/.cmd.
# Если стека Hangover нет — ставит его тихо (3 мин, 273 МБ), затем запускает wine.
# Путь приходит первым аргументом от Thunar (%f).
set -eu
EXE="$1"
[ -n "${EXE:-}" ] || exit 0
if [ ! -x /usr/bin/wine ]; then
  if command -v zenity >/dev/null 2>&1 && [ -n "${DISPLAY:-}" ]; then
    ( python3 /opt/stol/store/backend.py install-item windows --log /tmp/win-auto.log --progress /tmp/win-auto.progress &
      pid=$!
      while kill -0 $pid 2>/dev/null; do echo "# Ставится поддержка Windows — скачивается 273 МБ..."; sleep 0.5; done
    ) | zenity --progress --pulsate --auto-close --title="Ставится поддержка Windows" --text="Скачивается и распаковывается стек Hangover (~~2 ГБ) — первый запуск займёт 3–4 минуты..." --width=420 || exit 1
    wait $pid 2>/dev/null || true
  else
    python3 /opt/stol/store/backend.py install-item windows --log /tmp/win-auto.log --progress /tmp/win-auto.progress || exit 1
  fi
fi
case "$(printf '%s' "$EXE" | tr 'A-Z' 'a-z')" in
  *.msi) exec /usr/bin/wine msiexec /i "$EXE" ;;
  *.bat|*.cmd) exec /usr/bin/wine cmd /c "$EXE" ;;
  *) exec /usr/bin/wine "$EXE" ;;
esac
