#!/bin/bash
#
# /opt/stol/desktop-ready.sh — маячок готовности стола.
#
# Xtigervnc отвечает на 5901 сразу, как только поднялся X: к готовности стола это
# отношения не имеет. Между «порт ответил» и «XFCE нарисовал панель и стол»
# проходит ещё десяток секунд, и всё это время клиент показывает чёрный кадр.
#
# Поэтому здесь: ждём, пока в списке окон появятся И панель, И стол, и только
# после этого открываем порт 5910. Клиент подключается после 5910 (шов 3, R03.3).
# Не дождались — порт не открываем вовсе: лучше честный таймаут, чем чёрный экран.
#
# Ищем по классу окна (wmctrl -lx), а не по заголовку: интерфейс стола русский, и
# заголовок «Desktop» на нём не появится, а класс xfdesktop.Xfdesktop — появится.

set -u

STOL_DISPLAY="${STOL_DISPLAY:-:1}"
STOL_RUN_DIR="${STOL_RUN_DIR:-/run/stol}"
STOL_READY_PORT="${STOL_READY_PORT:-5910}"
STOL_READY_TIMEOUT="${STOL_READY_TIMEOUT:-180}"

export DISPLAY="$STOL_DISPLAY"

mkdir -p "$STOL_RUN_DIR" || exit 1
LOG="$STOL_RUN_DIR/beacon.log"

log() { printf '%s  %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG" >&2; }

# wmctrl на свежесозданном дисплее умеет зависнуть намертво (гонка с ещё не
# доинициализированным xfwm4) — поэтому только через timeout, без исключений.
windows() { timeout 5 wmctrl -lx 2>/dev/null; }

ticks=$((STOL_READY_TIMEOUT * 2))   # опрашиваем дважды в секунду
tick=0
ready=0
panel=0
desktop=0
while [ "$tick" -lt "$ticks" ]; do
    list="$(windows)"
    panel=0
    desktop=0
    case "$list" in *xfce4-panel*|*Xfce4-panel*) panel=1 ;; esac
    case "$list" in *xfdesktop*|*Xfdesktop*) desktop=1 ;; esac
    if [ "$panel" = 1 ] && [ "$desktop" = 1 ]; then
        ready=1
        break
    fi
    sleep 0.5
    tick=$((tick + 1))
done

if [ "$ready" != 1 ]; then
    log "стол не отрисовался за $STOL_READY_TIMEOUT секунд: панель=$panel, стол=$desktop"
    log "порт $STOL_READY_PORT не открываем — подключаться не к чему"
    exit 1
fi

log "панель и стол на месте, открываем порт $STOL_READY_PORT"

# Слушаем только петлю. Клиенту достаточно самого факта подключения — маячок
# ничего не передаёт и сразу закрывает соединение.
#
# Слушателя держим отдельным процессом и снимаем по сигналу сами: иначе TERM,
# посланный этому скрипту, оставил бы порт открытым, а закрытый порт — половина
# требования об остановке (R03.4).
python3 - "$STOL_READY_PORT" <<'PY' &
import socket, sys

port = int(sys.argv[1])
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", port))
srv.listen(16)
while True:
    try:
        conn, _ = srv.accept()
    except OSError:
        break
    conn.close()
PY

listener=$!
trap 'kill "$listener" 2>/dev/null; exit 0' TERM INT
wait "$listener"
