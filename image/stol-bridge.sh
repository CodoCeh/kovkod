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

app_windows() {
    wmctrl -l 2>/dev/null | awk '$2 != -1 {print $1}'
}

shape_new_window() {
    i=0
    while [ $i -lt 60 ]; do
        sleep 0.5
        for id in $(app_windows); do
            case "$1" in *"$id"*) continue ;; esac
            if [ "$2" = full ]; then
                wmctrl -i -r "$id" -b add,fullscreen
            else
                wmctrl -i -r "$id" -b add,maximized_vert,maximized_horz
            fi
            wmctrl -i -a "$id"
            return
        done
        i=$((i + 1))
    done
}

while :; do
    for req in "$BRIDGE"/in/*.req; do
        [ -f "$req" ] || continue
        verb=$(sed -n 1p "$req")
        path=$(sed -n 2p "$req")
        window=$(sed -n 3p "$req")
        rm -f "$req"
        case "$verb" in
            launch)
                [ -e "$path" ] && allowed "$path" "$verb" || continue
                before=$(app_windows)
                setsid exo-open "$path" </dev/null >/dev/null 2>&1 &
                case "$window" in
                    max|full) shape_new_window "$before" "$window" & ;;
                esac
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
