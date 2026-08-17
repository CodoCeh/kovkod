#!/bin/bash
#
# /opt/stol/start-gui.sh — поднимает стол внутри образа и сразу возвращается.
#
# Порядок: свой machine-id → сессионная шина dbus → Xtigervnc на :1 → XFCE →
# маячок готовности (desktop-ready.sh). Скрипт НЕ ждёт, пока стол отрисуется:
# ждать умеет маячок, и приложение подключается к 5910, а не к 5901 (шов 3).
#
# Сервер слушает только 127.0.0.1, тип безопасности None, версия протокола 3.8 —
# наш клиент намеренно не торгуется о версии (R15.1, R15.2).
#
# Повторный запуск второго сервера не поднимает: если стол уже живой, скрипт
# только доводит недостающее (маячок) и выходит с нулём (R15.3).
#
# Переменные окружения — только для прогона проверки на чужой машине; в образе
# все значения по умолчанию и есть боевые.

set -u

STOL_DISPLAY="${STOL_DISPLAY:-:1}"
STOL_GEOMETRY="${STOL_GEOMETRY:-1600x1000}"
STOL_RUN_DIR="${STOL_RUN_DIR:-/run/stol}"
STOL_READY_PORT="${STOL_READY_PORT:-5910}"
# Приставка к системным путям. В образе пустая; проверке нужна, чтобы не писать
# в /etc машины, на которой она запущена.
STOL_SYSROOT="${STOL_SYSROOT:-}"

HERE="$(cd "$(dirname "$0")" && pwd)"
RFB_PORT=$((5900 + ${STOL_DISPLAY#:}))
LOG="$STOL_RUN_DIR/start.log"

mkdir -p "$STOL_RUN_DIR" || exit 1

log() { printf '%s  %s\n' "$(date '+%H:%M:%S')" "$*" | tee -a "$LOG" >&2; }
die() { log "ОШИБКА: $*"; exit 1; }

# Живой ли процесс, записанный в файл.
pid_alive() {
    local file="$1" pid=""
    [ -f "$file" ] || return 1
    read -r pid < "$file" 2>/dev/null || return 1
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null
}

# Читает ровно 12 байт баннера RFB и закрывает соединение.
# Читать баннер ЦЕЛИКОМ обязательно: недочитанный сокет Java/bash закрывает через
# RST, TigerVNC считает это обрывом и после нескольких таких заносит 127.0.0.1 в
# чёрный список, после чего отказывает и настоящему клиенту.
#
# Соединение открываем и закрываем в подоболочке: «exec 3>&-» в текущей оболочке
# вместе с «2>/dev/null» навсегда увёл бы весь дальнейший журнал в никуда.
rfb_banner() {
    (
        exec 3<>"/dev/tcp/127.0.0.1/$RFB_PORT" || exit 1
        head -c 12 <&3
    ) 2>/dev/null
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

start_beacon() {
    if pid_alive "$STOL_RUN_DIR/beacon.pid"; then
        log "маячок уже запущен"
        return 0
    fi
    setsid "$HERE/desktop-ready.sh" >>"$STOL_RUN_DIR/beacon.log" 2>&1 </dev/null &
    echo $! > "$STOL_RUN_DIR/beacon.pid"
    log "маячок запущен, откроет порт $STOL_READY_PORT после отрисовки стола"
}

# ---------------------------------------------------------------- стол уже поднят?

if pid_alive "$STOL_RUN_DIR/vnc.pid" && port_open "$RFB_PORT"; then
    log "стол уже поднят на $STOL_DISPLAY, второй сервер не запускаем"
    start_beacon
    exit 0
fi

# ---------------------------------------------------------------- уборка за прошлым разом

log "запуск стола на $STOL_DISPLAY, $STOL_GEOMETRY"

pkill -f "Xtigervnc $STOL_DISPLAY" 2>/dev/null
pkill -f "desktop-ready.sh" 2>/dev/null
rm -f "/tmp/.X${STOL_DISPLAY#:}-lock" "/tmp/.X11-unix/X${STOL_DISPLAY#:}"
rm -f "$STOL_RUN_DIR/vnc.pid" "$STOL_RUN_DIR/beacon.pid" "$STOL_RUN_DIR/xfce.pid"

# ---------------------------------------------------------------- свой machine-id

# В образе machine-id один на всех, кто поставит APK: он прописан на сборке.
# Перевыписываем на первом запуске конкретного телефона и запоминаем это меткой.
if [ ! -f "$STOL_SYSROOT/var/lib/stol/machine-id.done" ]; then
    mkdir -p "$STOL_SYSROOT/var/lib/stol" "$STOL_SYSROOT/var/lib/dbus" "$STOL_SYSROOT/etc"
    command -v dbus-uuidgen >/dev/null 2>&1 \
        || die "в образе нет dbus-uuidgen — шину поднять не на чем"
    rm -f "$STOL_SYSROOT/etc/machine-id" "$STOL_SYSROOT/var/lib/dbus/machine-id"
    dbus-uuidgen > "$STOL_SYSROOT/etc/machine-id" 2>/dev/null \
        || die "не удалось выписать свой machine-id"
    cp "$STOL_SYSROOT/etc/machine-id" "$STOL_SYSROOT/var/lib/dbus/machine-id"
    : > "$STOL_SYSROOT/var/lib/stol/machine-id.done"
    log "выписан свой machine-id: $(cat "$STOL_SYSROOT/etc/machine-id")"
fi

# ---------------------------------------------------------------- сессионная шина

# XFCE без шины не поднимается: и xfconfd, и панель, и xfdesktop ходят через неё.
if pid_alive "$STOL_RUN_DIR/dbus.pid" && [ -S "$STOL_RUN_DIR/bus" ]; then
    log "шина уже поднята"
else
    pkill -f "dbus-daemon.*$STOL_RUN_DIR/bus" 2>/dev/null
    rm -f "$STOL_RUN_DIR/bus"
    dbus-daemon --session --address="unix:path=$STOL_RUN_DIR/bus" \
        --fork --print-pid > "$STOL_RUN_DIR/dbus.pid" 2>>"$LOG" \
        || die "dbus-daemon не запустился, подробности в $LOG"
    log "шина поднята: unix:path=$STOL_RUN_DIR/bus"
fi
export DBUS_SESSION_BUS_ADDRESS="unix:path=$STOL_RUN_DIR/bus"

# Проверяем шину ИМЕННО клиентом на GDBus (xfconf-query), а не dbus-send: libdbus
# и GDBus подключаются по-разному, и ломается обычно второй — а XFCE весь на нём.
if ! timeout 20 xfconf-query -l >/dev/null 2>"$STOL_RUN_DIR/dbus-probe.err"; then
    claimed="$(id -u)"
    real="$(awk '/^Uid:/ {print $2; exit}' /proc/self/status 2>/dev/null)"
    log "шина не принимает подключения. Ответ: $(cat "$STOL_RUN_DIR/dbus-probe.err")"
    if [ -n "$real" ] && [ "$real" != "$claimed" ]; then
        log "Причина: программы внутри образа видят себя пользователем $claimed,"
        log "а ядро видит $real. Подключаясь к шине, они предъявляют ядру номер"
        log "$claimed, и ядро отказывается это заверить — отсюда «Operation not permitted»."
        log "Лечение на стороне приложения: входить в образ без подмены номера"
        log "пользователя (proot без -0) либо подменять на реальный ($real),"
        log "иначе XFCE в этой среде поднять нельзя."
    else
        log "Номера пользователя сходятся ($claimed), значит дело не в подмене."
        log "Смотрите $STOL_RUN_DIR/dbus-probe.err и $LOG."
    fi
    die "сессионная шина не работает — стол не поднять"
fi
log "шина отвечает, XFCE есть на чём поднимать"

# ---------------------------------------------------------------- Xtigervnc

# -nolock обязателен: X-сервер держит дисплей файлом-замком, который создаёт
# жёсткой ссылкой, а под proot жёсткие ссылки эмулируются — сервер падает на
# «Linking lock file in place failed» и до сети не доходит. Замок нам и не нужен:
# второй сервер на этом дисплее всё равно не сможет занять порт, а от повторного
# запуска стол защищён проверкой выше.
setsid Xtigervnc "$STOL_DISPLAY" \
    -geometry "$STOL_GEOMETRY" -depth 24 \
    -rfbport "$RFB_PORT" \
    -localhost=1 \
    -SecurityTypes None \
    -AlwaysShared=1 \
    -nolock \
    -desktop "KodoCeh Stol" \
    >>"$LOG" 2>&1 </dev/null &
echo $! > "$STOL_RUN_DIR/vnc.pid"

banner=""
for _ in $(seq 1 60); do
    if port_open "$RFB_PORT"; then
        banner="$(rfb_banner)"
        [ -n "$banner" ] && break
    fi
    pid_alive "$STOL_RUN_DIR/vnc.pid" || die "Xtigervnc умер на старте, подробности в $LOG"
    sleep 0.5
done
[ -n "$banner" ] || die "порт $RFB_PORT не ответил баннером RFB за 30 секунд"

case "$banner" in
    "RFB 003.008"*) log "сервер отвечает: $(printf '%s' "$banner" | tr -d '\n')" ;;
    *) die "сервер представился как «$(printf '%s' "$banner" | tr -d '\n')», а клиент ждёт RFB 003.008" ;;
esac

# ---------------------------------------------------------------- XFCE

setsid env DISPLAY="$STOL_DISPLAY" \
    DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" \
    HOME="${HOME:-/root}" \
    startxfce4 >>"$LOG" 2>&1 </dev/null &
echo $! > "$STOL_RUN_DIR/xfce.pid"
log "XFCE запущен"

# ---------------------------------------------------------------- маячок

start_beacon

log "готово: сервер на $RFB_PORT, ждите порт $STOL_READY_PORT"
exit 0
