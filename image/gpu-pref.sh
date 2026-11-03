#!/bin/sh
#
# /opt/stol/gpu-pref.sh on|off ЯРЛЫК.desktop — запускать программу с ускорением видеокартой или без.

set -eu

WRAP=/opt/stol/gpu/gl-run
mode=$1
shift
for f in "$@"; do
    case "$f" in
        *.desktop) ;;
        *) continue ;;
    esac
    case "$(realpath "$f")" in
        /usr/share/applications/*)
            mkdir -p /root/.local/share/applications
            cp "$f" /root/.local/share/applications/
            f=/root/.local/share/applications/$(basename "$f")
            ;;
    esac
    sed -i "s|^Exec=$WRAP |Exec=|" "$f"
    [ "$mode" = on ] && sed -i "s|^Exec=|Exec=$WRAP |" "$f"
done
