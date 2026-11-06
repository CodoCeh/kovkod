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
export XDG_CURRENT_DESKTOP="${XDG_CURRENT_DESKTOP:-XFCE}"
case "$(printf '%s' "$EXE" | tr 'A-Z' 'a-z')" in
  *.msi) /usr/bin/wine msiexec /i "$EXE" || true ;;
  *.bat|*.cmd) /usr/bin/wine cmd /c "$EXE" || true ;;
  *) /usr/bin/wine "$EXE" || true ;;
esac
timeout 30 /usr/bin/wineserver -w || true
for link in /root/Desktop/*.lnk; do
  [ -f "$link" ] || continue
  [ -f "${link%.lnk}.desktop" ] && rm -f "$link"
done
exit 0
