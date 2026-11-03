#!/bin/sh
#
# /opt/stol/stol-bridge.sh — запросы приложения КовКод к живому столу.
#
# Приложение кладёт в $BRIDGE/in файл *.req: первая строка — действие, вторая — путь.
# Стол кладёт в $BRIDGE/out файл *.pin с путём ярлыка, приложение закрепляет его
# на главном экране телефона.

set -u

BRIDGE="${STOL_RUN_DIR:-/run/stol}/bridge"
mkdir -p "$BRIDGE/in" "$BRIDGE/out"

allowed() {
    case "$1" in
        *..*) return 1 ;;
        /root/Desktop/*.desktop|/usr/share/applications/*.desktop) return 0 ;;
        /root/*) [ "$2" = open ] ;;
        *) return 1 ;;
    esac
}

has_handler() {
    python3 -c 'import sys, gi
gi.require_version("Gio", "2.0")
from gi.repository import Gio
kind, _ = Gio.content_type_guess(sys.argv[1], None)
sys.exit(0 if Gio.AppInfo.get_default_for_type(kind, False) else 1)' "$1" 2>/dev/null
}

while :; do
    for req in "$BRIDGE"/in/*.req; do
        [ -f "$req" ] || continue
        verb=$(sed -n 1p "$req")
        path=$(sed -n 2p "$req")
        rm -f "$req"
        case "$verb" in
            launch)
                [ -e "$path" ] && allowed "$path" "$verb" \
                    && setsid exo-open "$path" </dev/null >/dev/null 2>&1 &
                ;;
            open)
                [ -e "$path" ] && allowed "$path" "$verb" || continue
                if [ -f "$path" ] && ! has_handler "$path"; then
                    path=$(dirname "$path")
                fi
                setsid exo-open "$path" </dev/null >/dev/null 2>&1 &
                ;;
            browse)
                case "$path" in
                    http://*|https://*) setsid exo-open --launch WebBrowser "$path" </dev/null >/dev/null 2>&1 & ;;
                esac
                ;;
        esac
    done
    sleep 0.5
done
