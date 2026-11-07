#!/bin/sh
export DEBIAN_FRONTEND=noninteractive
export HOME="${HOME:-/root}" DISPLAY="${DISPLAY:-:1}"
LIST=/etc/apt/sources.list.d/claude-desktop.list
LOCK=/tmp/kovkod-claude-update.lock
STATE="$HOME/.config/kovkod"
PENDING="$STATE/claude-update-pending"
SKIPPED="$STATE/claude-update-skipped"
APT="-o DPkg::Lock::Timeout=300"
ONLY="-o Dir::Etc::sourcelist=$LIST -o Dir::Etc::sourceparts=- -o APT::Get::List-Cleanup=0"
UPGRADE="install -y --only-upgrade --no-install-recommends claude-desktop"

mkdir -p "$STATE"

say() { notify-send "$1" "$2" 2>/dev/null; }

has_desktop() { dpkg-query -W -f='${Status}' claude-desktop 2>/dev/null | grep -q "install ok installed" && [ -f "$LIST" ]; }
desktop_current() { dpkg-query -W -f='${Version}' claude-desktop; }
desktop_candidate() { LC_ALL=C apt-cache policy claude-desktop | awk '/Candidate:/ {print $2}'; }

apply() {
    [ -f "$PENDING" ] || return 0
    pgrep -x claude-desktop >/dev/null && return 0
    (
        flock -n 8 || exit 0
        pgrep -x claude-desktop >/dev/null && exit 0
        say "Claude обновляется" "Откроется сам, если вы его запускали."
        # shellcheck disable=SC2086
        apt-get $UPGRADE $APT || {
            # shellcheck disable=SC2086
            apt-get -f install -y $APT
            # shellcheck disable=SC2086
            apt-get $UPGRADE $APT
        } && rm -f "$PENDING" && say "Claude обновлён" "Версия $(desktop_current)."
    ) 8>"$LOCK" 9>&-
}

if [ "${1:-}" = apply ]; then
    apply
    exit 0
fi

exec 9>/tmp/kovkod-claude-updater.lock
flock -n 9 || exit 0

ask() {
    python3 - "$1" 9>&- <<'PY'
import sys
import tkinter as tk
from tkinter import messagebox
root = tk.Tk()
root.withdraw()
sys.exit(0 if messagebox.askyesno("Обновление Claude", sys.argv[1]) else 3)
PY
}

check() {
    has_desktop || return 0
    # shellcheck disable=SC2086
    apt-get update -y $APT $ONLY >/dev/null 2>&1
    candidate=$(desktop_candidate)
    [ -n "$candidate" ] && [ "$candidate" != "(none)" ] || return 0
    dpkg --compare-versions "$candidate" gt "$(desktop_current)" || return 0
    [ "$candidate" = "$(cat "$SKIPPED" 2>/dev/null)" ] && return 0
    size=$(apt-cache show "claude-desktop=$candidate" 2>/dev/null | awk '/^Size:/ {printf "%d", $2 / 1048576; exit}')

    ask "Вышла новая версия Claude Desktop.

Сейчас: $(desktop_current)
Новая: $candidate, около $size МБ

Скачать и установить? Claude обновится, когда будет закрыт."
    answer=$?
    if [ "$answer" -eq 3 ]; then
        printf '%s' "$candidate" > "$SKIPPED"
        return 0
    fi
    [ "$answer" -eq 0 ] || return 0

    rm -f "$SKIPPED"
    say "Скачивается обновление Claude" "Можно продолжать работу."
    # shellcheck disable=SC2086
    if apt-get $UPGRADE -d $APT 9>&-; then
        : > "$PENDING"
        pgrep -x claude-desktop >/dev/null && say "Обновление Claude скачано" "Установится, когда вы закроете Claude."
    else
        say "Обновление Claude не скачалось" "Проверьте интернет. Спросим снова при следующей проверке."
    fi
}

sleep "${KOVKOD_UPDATE_DELAY:-60}"
check
apply
