#!/bin/sh
#
# /opt/stol/android-open.sh ПАКЕТ — открыть приложение Android рядом со столом.

set -u

case "${1:-}" in
    ''|*[!A-Za-z0-9._]*) exit 2 ;;
esac
out="${STOL_RUN_DIR:-/run/stol}/bridge/out"
name="$out/$(date +%s%N).app"
printf '%s\n' "$1" > "$name.tmp" && mv "$name.tmp" "$name"
