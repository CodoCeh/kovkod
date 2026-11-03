#!/bin/sh
#
# /opt/stol/remote.sh on ПАРОЛЬ | off — стол в браузере другого устройства в той же сети.

set -u

RUN="${STOL_RUN_DIR:-/run/stol}"
PORT=6080

stop() {
    [ -f "$RUN/remote.pid" ] && kill "$(cat "$RUN/remote.pid")" 2>/dev/null
    rm -f "$RUN/remote.pid"
}

case "${1:-}" in
    on)
        stop
        [ "${#2}" -ge 6 ] || exit 2
        setsid websockify --web /usr/share/novnc --web-auth \
            --auth-plugin websockify.auth_plugins.BasicHTTPAuth --auth-source "kovkod:$2" \
            "0.0.0.0:$PORT" 127.0.0.1:5901 >>"$RUN/remote.log" 2>&1 </dev/null &
        echo $! > "$RUN/remote.pid"
        ;;
    off)
        stop
        ;;
    *)
        echo "usage: $0 on PASSWORD|off" >&2
        exit 2
        ;;
esac
