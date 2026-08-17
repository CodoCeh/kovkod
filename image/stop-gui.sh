#!/bin/bash
#
# /opt/stol/stop-gui.sh — гасит стол и маячок.
#
# Требование простое и проверяемое (R03.4): после остановки в образе не остаётся
# ни Xtigervnc, ни сессии XFCE, ни маячка, а порты 5901 и 5910 закрыты. Поэтому
# скрипт не только шлёт сигналы, но и ПРОВЕРЯЕТ результат, а не верит в него:
# если кто-то выжил, выход ненулевой и в журнале написано, кто именно.
#
# Дважды подряд запускать безопасно: гасить нечего — выходит с нулём.

set -u

STOL_DISPLAY="${STOL_DISPLAY:-:1}"
STOL_RUN_DIR="${STOL_RUN_DIR:-/run/stol}"
STOL_READY_PORT="${STOL_READY_PORT:-5910}"

RFB_PORT=$((5900 + ${STOL_DISPLAY#:}))
LOG="$STOL_RUN_DIR/stop.log"

mkdir -p "$STOL_RUN_DIR" || exit 1

log() { printf '%s  %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG" >&2; }

# Всё, что поднял start-gui.sh, живёт своими группами процессов (setsid), поэтому
# гасим группой: иначе переживут внуки — панель, xfwm4, Thunar и прочее.
kill_group() {
    local file="$1" signal="$2" pid=""
    [ -f "$file" ] || return 0
    read -r pid < "$file" 2>/dev/null || return 0
    [ -n "$pid" ] || return 0
    kill "-$signal" "-$pid" 2>/dev/null || kill "-$signal" "$pid" 2>/dev/null
    return 0
}

# Что считаем остатками стола. Шаблоны привязаны к нашему дисплею и нашему
# каталогу, чтобы не задеть ничего постороннего.
patterns() {
    printf '%s\n' \
        "Xtigervnc $STOL_DISPLAY" \
        "desktop-ready.sh" \
        "startxfce4" \
        "xfce4-session" \
        "xfsettingsd" \
        "xfconfd" \
        "dbus-daemon.*$STOL_RUN_DIR/bus"
}

survivors() {
    local p
    patterns | while read -r p; do
        pgrep -f "$p" >/dev/null 2>&1 && printf '%s\n' "$p"
    done
}

port_open() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null || return 1
    exec 3>&- 2>/dev/null
    exec 3<&- 2>/dev/null
    return 0
}

log "останавливаем стол на $STOL_DISPLAY"

# ---------------------------------------------------------------- по-хорошему

kill_group "$STOL_RUN_DIR/beacon.pid" TERM
kill_group "$STOL_RUN_DIR/xfce.pid" TERM
kill_group "$STOL_RUN_DIR/vnc.pid" TERM
kill_group "$STOL_RUN_DIR/dbus.pid" TERM

patterns | while read -r p; do pkill -TERM -f "$p" 2>/dev/null; done

for _ in 1 2 3 4 5 6 7 8 9 10; do
    [ -z "$(survivors)" ] && break
    sleep 0.5
done

# ---------------------------------------------------------------- по-плохому

if [ -n "$(survivors)" ]; then
    log "часть процессов не ушла по TERM, добиваем"
    kill_group "$STOL_RUN_DIR/beacon.pid" KILL
    kill_group "$STOL_RUN_DIR/xfce.pid" KILL
    kill_group "$STOL_RUN_DIR/vnc.pid" KILL
    kill_group "$STOL_RUN_DIR/dbus.pid" KILL
    patterns | while read -r p; do pkill -KILL -f "$p" 2>/dev/null; done
    for _ in 1 2 3 4 5 6; do
        [ -z "$(survivors)" ] && break
        sleep 0.5
    done
fi

# ---------------------------------------------------------------- следы на диске

rm -f "/tmp/.X${STOL_DISPLAY#:}-lock" "/tmp/.X11-unix/X${STOL_DISPLAY#:}"
rm -f "$STOL_RUN_DIR/vnc.pid" "$STOL_RUN_DIR/xfce.pid" \
      "$STOL_RUN_DIR/beacon.pid" "$STOL_RUN_DIR/dbus.pid" "$STOL_RUN_DIR/bus"

# ---------------------------------------------------------------- проверка

failed=0

left="$(survivors)"
if [ -n "$left" ]; then
    log "ОШИБКА: остались процессы по шаблонам:"
    printf '%s\n' "$left" | while read -r p; do log "  $p"; done
    failed=1
fi

for port in "$RFB_PORT" "$STOL_READY_PORT"; do
    if port_open "$port"; then
        log "ОШИБКА: порт $port всё ещё принимает подключения"
        failed=1
    fi
done

if [ "$failed" != 0 ]; then
    exit 1
fi

log "стол остановлен: процессов нет, порты $RFB_PORT и $STOL_READY_PORT закрыты"
exit 0
