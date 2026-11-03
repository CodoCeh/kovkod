#!/bin/sh
#
# /opt/stol/kovkod-pin.sh ЯРЛЫК.desktop — значок программы на главный экран телефона.

set -u

out="${STOL_RUN_DIR:-/run/stol}/bridge/out"
for f in "$@"; do
    case "$f" in
        *.desktop) ;;
        *) continue ;;
    esac
    path=$(realpath "$f") || continue
    name="$out/$(date +%s%N).pin"
    printf '%s\n' "$path" > "$name.tmp" && mv "$name.tmp" "$name"
done
