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

while :; do
    for req in "$BRIDGE"/in/*.req; do
        [ -f "$req" ] || continue
        verb=$(sed -n 1p "$req")
        path=$(sed -n 2p "$req")
        rm -f "$req"
        [ -e "$path" ] || continue
        case "$verb" in
            launch|open)
                allowed "$path" "$verb" && setsid exo-open "$path" </dev/null >/dev/null 2>&1 &
                ;;
        esac
    done
    sleep 0.5
done
