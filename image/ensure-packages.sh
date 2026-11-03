#!/bin/sh
#
# /opt/stol/ensure-packages.sh — докачивает пакеты, которых нет в столе, собранном до обновления.

RUN="${STOL_RUN_DIR:-/run/stol}"
MARK=/opt/stol/.packages-2026.11.5
[ -f "$MARK" ] && exit 0

need=""
command -v pulseaudio >/dev/null || need="$need pulseaudio pulseaudio-utils"
command -v notify-send >/dev/null || need="$need libnotify-bin"
[ -x /usr/lib/aarch64-linux-gnu/xfce4/notifyd/xfce4-notifyd ] || [ -x /usr/lib/aarch64-linux-gnu/xfce4/notifyd ] \
    || need="$need xfce4-notifyd"
python3 -c 'import gi; gi.require_version("Wnck", "3.0")' 2>/dev/null || need="$need gir1.2-wnck-3.0"

if [ -n "$need" ]; then
    apt-get update -qq >>"$RUN/packages.log" 2>&1
    # shellcheck disable=SC2086
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends $need \
        >>"$RUN/packages.log" 2>&1 || exit 1
fi
touch "$MARK"
if [ -n "$need" ]; then
    pkill -f "window-memory.py" 2>/dev/null
    setsid python3 /opt/stol/window-memory.py >>"$RUN/packages.log" 2>&1 </dev/null &
    setsid /opt/stol/sound.sh >>"$RUN/packages.log" 2>&1 </dev/null &
fi
