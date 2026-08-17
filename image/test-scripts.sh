#!/bin/bash
#
# image/test-scripts.sh — проверка скриптов стола. Гоняется на Маке, без телефона
# и без образа: настоящие скрипты запускаются как есть, а окружение образа
# (Xtigervnc, XFCE, wmctrl, шина) подменено поддельными командами в своём PATH.
#
# Проверяется ПОВЕДЕНИЕ, а не наличие файлов:
#   1. маячок не открывает 5910, пока в списке окон нет И панели, И стола;
#   2. не дождавшись стола, маячок порт не открывает вовсе и говорит об этом;
#   3. повторный запуск не поднимает второго сервера;
#   4. остановка не оставляет ни процессов, ни открытых портов;
#   5. три цикла запуск-остановка держат ровно один сервер (R15.3).
#
# В конце проверка проверяет саму себя: те же тесты прогоняются по намеренно
# испорченным копиям скриптов, и каждый прогон обязан покраснеть.
#
# Настоящий прогон в собранном образе (Xtigervnc + XFCE под proot) подделками не
# заменяется и делается отдельно — см. отчёт задачи 05.

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
MUTATION="${STOL_MUTATION:-}"

# Дисплей :41 вместо боевого :1 — чтобы проверка не сталкивалась ни с чем на машине.
export STOL_DISPLAY=":41"
export STOL_READY_PORT=5951
RFB_PORT=5941

W="$(mktemp -d "${TMPDIR:-/tmp}/stol-scripts.XXXXXX")" || exit 1
export STOL_RUN_DIR="$W/run"
export STOL_SYSROOT="$W/sysroot"
BIN="$W/bin"
SCRIPTS="$W/scripts"
WINDOWS="$W/windows.txt"
CALLS="$W/calls"

mkdir -p "$BIN" "$SCRIPTS" "$CALLS" "$STOL_RUN_DIR"
: > "$WINDOWS"

PASS=0
FAIL=0
ok()  { PASS=$((PASS + 1)); printf '    \033[32mОК\033[0m    %s\n' "$*"; }
bad() { FAIL=$((FAIL + 1)); printf '    \033[31mПЛОХО\033[0m %s\n' "$*"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$*"; }

listeners() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null; }

cleanup() {
    # Всё поддельное живёт внутри $W, поэтому и убирается по этому пути. Но
    # слушатели портов — это python, запущенный через «python3 -», и его строка
    # запуска пути не содержит; поэтому вторым заходом снимаем по портам. Занятые
    # чем-то посторонним порты отсечены проверкой ниже, до начала прогона.
    pkill -f "$W" >/dev/null 2>&1
    sleep 0.3
    pkill -9 -f "$W" >/dev/null 2>&1
    local p pids
    for p in "$RFB_PORT" "$STOL_READY_PORT"; do
        pids="$(listeners "$p")"
        [ -n "$pids" ] && kill -9 $pids 2>/dev/null
    done
    rm -rf "$W"
}
trap cleanup EXIT INT TERM

for p in "$RFB_PORT" "$STOL_READY_PORT"; do
    if [ -n "$(listeners "$p")" ]; then
        printf 'порт %s уже занят на этой машине — проверка его использует, освободите\n' "$p" >&2
        exit 2
    fi
done

# ---------------------------------------------------------------- подделки

fake() { cat > "$BIN/$1"; chmod 0755 "$BIN/$1"; }

fake wmctrl <<EOF
#!/bin/bash
# Список окон задаёт сама проверка, дописывая строки в файл.
cat "$WINDOWS" 2>/dev/null
exit 0
EOF

fake Xtigervnc <<EOF
#!/bin/bash
# Поддельный сервер: записывает факт запуска и отвечает настоящим баннером RFB
# на том порту, который ему передали ключом -rfbport.
echo "\$*" >> "$CALLS/xtigervnc.calls"
port=""; prev=""
for a in "\$@"; do [ "\$prev" = "-rfbport" ] && port="\$a"; prev="\$a"; done
[ -n "\$port" ] || exit 64
python3 - "\$port" <<'PY' &
import socket, sys
srv = socket.socket()
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", int(sys.argv[1])))
srv.listen(8)
while True:
    try:
        conn, _ = srv.accept()
    except OSError:
        break
    try:
        conn.sendall(b"RFB 003.008\n")
    except OSError:
        pass
    conn.close()
PY
child=\$!
trap 'kill \$child 2>/dev/null; exit 0' TERM INT
wait \$child
EOF

fake startxfce4 <<EOF
#!/bin/bash
echo "\$*" >> "$CALLS/startxfce4.calls"
while true; do sleep 1; done
EOF

fake dbus-daemon <<EOF
#!/bin/bash
# Поддельная шина: настоящий сокет по заказанному пути, чтобы start-gui.sh видел
# его так же, как настоящий dbus-daemon --fork --print-pid.
addr=""
for a in "\$@"; do case "\$a" in --address=unix:path=*) addr="\${a#--address=unix:path=}" ;; esac; done
[ -n "\$addr" ] || exit 64
python3 - "\$addr" <<'PY' &
import os, socket, sys
path = sys.argv[1]
try:
    os.unlink(path)
except OSError:
    pass
srv = socket.socket(socket.AF_UNIX)
srv.bind(path)
srv.listen(8)
while True:
    try:
        conn, _ = srv.accept()
    except OSError:
        break
    conn.close()
PY
pid=\$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -S "\$addr" ] && break; sleep 0.2; done
echo "\$pid"
exit 0
EOF

fake xfconf-query <<'EOF'
#!/bin/bash
echo "xsettings"
exit 0
EOF

fake dbus-uuidgen <<'EOF'
#!/bin/bash
python3 -c "import uuid; print(uuid.uuid4().hex)"
EOF

fake setsid <<'EOF'
#!/bin/bash
# Настоящая новая сессия, а не заглушка: без неё групповая остановка не проверится.
exec python3 -c 'import os, sys
os.setsid()
os.execvp(sys.argv[1], sys.argv[1:])' "$@"
EOF

fake timeout <<'EOF'
#!/bin/bash
shift
exec "$@"
EOF

export PATH="$BIN:$PATH"

# ---------------------------------------------------------------- копии скриптов

for f in start-gui.sh stop-gui.sh desktop-ready.sh; do
    [ -f "$HERE/$f" ] || { printf 'нет файла image/%s\n' "$f" >&2; exit 1; }
    cp "$HERE/$f" "$SCRIPTS/$f"
    chmod 0755 "$SCRIPTS/$f"
done

mutate() {
    local file="$1"
    shift
    sed "$@" "$SCRIPTS/$file" > "$SCRIPTS/$file.tmp" && mv "$SCRIPTS/$file.tmp" "$SCRIPTS/$file"
    chmod 0755 "$SCRIPTS/$file"
}

case "$MUTATION" in
    "") ;;
    beacon-early)
        # маячок больше не ждёт окон
        mutate desktop-ready.sh -e 's|^ready=0$|ready=1|' -e 's|^while \[ "\$tick" -lt "\$ticks" \]; do$|while false; do|'
        ;;
    beacon-no-timeout)
        # маячок открывает порт даже не дождавшись стола
        mutate desktop-ready.sh -e 's|^    exit 1$|    ready=1|'
        ;;
    no-guard)
        # запуск перестал замечать уже поднятый стол
        mutate start-gui.sh -e 's|^if pid_alive "\$STOL_RUN_DIR/vnc.pid" && port_open "\$RFB_PORT"; then$|if false; then|'
        ;;
    stop-nothing)
        # остановка перестала кого-либо гасить
        mutate stop-gui.sh -e 's|^\( *\)kill "-.signal".*|\1true|' -e 's|pkill -TERM -f|true|' -e 's|pkill -KILL -f|true|'
        ;;
    *) printf 'неизвестная мутация: %s\n' "$MUTATION" >&2; exit 2 ;;
esac

# ---------------------------------------------------------------- мелкая утварь

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# Ждёт открытия порта не дольше N половинок секунды.
wait_port_open() {
    local port="$1" ticks="$2" i=0
    while [ "$i" -lt "$ticks" ]; do
        port_open "$port" && return 0
        sleep 0.5
        i=$((i + 1))
    done
    return 1
}

# Убеждается, что порт не открылся ни разу за N половинок секунды.
port_stays_closed() {
    local port="$1" ticks="$2" i=0
    while [ "$i" -lt "$ticks" ]; do
        port_open "$port" && return 1
        sleep 0.5
        i=$((i + 1))
    done
    return 0
}

calls() { [ -f "$CALLS/$1" ] && wc -l < "$CALLS/$1" | tr -d ' ' || echo 0; }

running() { pgrep -f "$1" >/dev/null 2>&1; }

PANEL='0x00800003 -1 xfce4-panel.Xfce4-panel  telefon xfce4-panel'
DESKTOP='0x0100002c -1 xfdesktop.Xfdesktop   telefon Desktop'

# ---------------------------------------------------------------- 1. порядок маячка

head_ "1. Маячок открывает 5910 только после панели И стола"

: > "$WINDOWS"
STOL_READY_TIMEOUT=90 "$SCRIPTS/desktop-ready.sh" >>"$W/beacon.log" 2>&1 &
BEACON=$!

if port_stays_closed "$STOL_READY_PORT" 6; then
    ok "окон нет — порт закрыт (3 с)"
else
    bad "порт открылся, хотя окон нет вообще"
fi

printf '%s\n' "$PANEL" > "$WINDOWS"
if port_stays_closed "$STOL_READY_PORT" 6; then
    ok "есть только панель — порт закрыт (3 с)"
else
    bad "порт открылся по одной панели, стола ещё нет"
fi

printf '%s\n' "$DESKTOP" > "$WINDOWS"
if port_stays_closed "$STOL_READY_PORT" 6; then
    ok "есть только стол — порт закрыт (3 с)"
else
    bad "порт открылся по одному столу, панели ещё нет"
fi

printf '%s\n%s\n' "$PANEL" "$DESKTOP" > "$WINDOWS"
if wait_port_open "$STOL_READY_PORT" 20; then
    ok "появились оба окна — порт открылся"
else
    bad "оба окна на месте, а порт так и не открылся"
fi

kill "$BEACON" 2>/dev/null
pkill -f "$SCRIPTS/desktop-ready.sh" 2>/dev/null
pkill -f "$W/bin" 2>/dev/null
sleep 0.5

# ---------------------------------------------------------------- 2. честный таймаут

head_ "2. Не дождавшись стола, маячок порт не открывает"

: > "$WINDOWS"
STOL_READY_TIMEOUT=2 "$SCRIPTS/desktop-ready.sh" >"$W/beacon-timeout.log" 2>&1
rc=$?
if [ "$rc" != 0 ]; then
    ok "маячок вышел с ненулевым кодом ($rc)"
else
    bad "маячок отчитался об успехе, хотя стола не было"
fi
if port_open "$STOL_READY_PORT"; then
    bad "порт открыт после неудачи маячка"
else
    ok "порт закрыт"
fi
if grep -q "не открываем" "$W/beacon-timeout.log"; then
    ok "в журнале сказано, что порт не открыт и почему"
else
    bad "маячок промолчал о причине"
fi

# ---------------------------------------------------------------- 3. один сервер

head_ "3. Повторный запуск не поднимает второго сервера"

: > "$WINDOWS"
"$SCRIPTS/start-gui.sh" >"$W/start1.log" 2>&1
rc=$?
if [ "$rc" = 0 ]; then ok "первый запуск прошёл"; else bad "первый запуск упал (код $rc), журнал: $W/start1.log"; fi
if [ "$(calls xtigervnc.calls)" = 1 ]; then ok "сервер запущен один раз"; else bad "серверов запущено: $(calls xtigervnc.calls)"; fi
if port_open "$RFB_PORT"; then ok "порт $RFB_PORT отвечает"; else bad "порт $RFB_PORT молчит"; fi
if grep -q "RFB 003.008" "$W/start1.log"; then ok "проверен баннер RFB 003.008"; else bad "баннер RFB не проверен"; fi

id1=""
[ -f "$STOL_SYSROOT/etc/machine-id" ] && id1="$(cat "$STOL_SYSROOT/etc/machine-id")"
if [ -n "$id1" ]; then ok "machine-id перевыписан на первом запуске"; else bad "machine-id не перевыписан"; fi

"$SCRIPTS/start-gui.sh" >"$W/start2.log" 2>&1
rc=$?
if [ "$rc" = 0 ]; then ok "повторный запуск прошёл"; else bad "повторный запуск упал (код $rc)"; fi
if [ "$(calls xtigervnc.calls)" = 1 ]; then ok "второго сервера не появилось"; else bad "серверов стало: $(calls xtigervnc.calls)"; fi

id2=""
[ -f "$STOL_SYSROOT/etc/machine-id" ] && id2="$(cat "$STOL_SYSROOT/etc/machine-id")"
if [ "$id1" = "$id2" ]; then ok "machine-id на повторном запуске не менялся"; else bad "machine-id переписался второй раз"; fi

# ---------------------------------------------------------------- 4. остановка

head_ "4. Остановка не оставляет ни процессов, ни портов"

printf '%s\n%s\n' "$PANEL" "$DESKTOP" > "$WINDOWS"
if wait_port_open "$STOL_READY_PORT" 20; then
    ok "маячок поднялся вместе со столом"
else
    bad "маячок не открыл порт при живом столе"
fi

"$SCRIPTS/stop-gui.sh" >"$W/stop.log" 2>&1
rc=$?
if [ "$rc" = 0 ]; then ok "остановка отчиталась успехом"; else bad "остановка вернула код $rc, журнал: $W/stop.log"; fi

sleep 0.5
for what in "Xtigervnc $STOL_DISPLAY" "$SCRIPTS/startxfce4" "$SCRIPTS/desktop-ready.sh"; do
    if running "$what"; then bad "остался процесс: $what"; else ok "процессов «$what» нет"; fi
done
if running "$BIN/startxfce4"; then bad "осталась сессия XFCE"; else ok "сессии XFCE нет"; fi
if port_open "$RFB_PORT"; then bad "порт $RFB_PORT всё ещё открыт"; else ok "порт $RFB_PORT закрыт"; fi
if port_open "$STOL_READY_PORT"; then bad "порт $STOL_READY_PORT всё ещё открыт"; else ok "порт $STOL_READY_PORT закрыт"; fi

# ---------------------------------------------------------------- 5. три цикла

head_ "5. Три цикла запуск-остановка держат ровно один сервер (R15.3)"

: > "$CALLS/xtigervnc.calls"
cycles_ok=1
for c in 1 2 3; do
    : > "$WINDOWS"
    "$SCRIPTS/start-gui.sh" >"$W/cycle-$c-start.log" 2>&1 || cycles_ok=0
    if [ "$(calls xtigervnc.calls)" != "$c" ]; then
        bad "цикл $c: серверов запущено всего $(calls xtigervnc.calls), ожидалось $c"
        cycles_ok=0
    fi
    n=$(pgrep -f "Xtigervnc $STOL_DISPLAY" | wc -l | tr -d ' ')
    if [ "$n" != 1 ]; then bad "цикл $c: живых серверов $n, а не один"; cycles_ok=0; fi
    "$SCRIPTS/stop-gui.sh" >"$W/cycle-$c-stop.log" 2>&1 || { bad "цикл $c: остановка вернула ошибку"; cycles_ok=0; }
done
if [ "$cycles_ok" = 1 ]; then ok "три цикла прошли, каждый раз ровно один сервер"; fi
n=$(pgrep -f "Xtigervnc $STOL_DISPLAY" | wc -l | tr -d ' ')
if [ "$n" = 0 ]; then ok "после трёх циклов серверов не осталось"; else bad "после трёх циклов живых серверов: $n"; fi

# ---------------------------------------------------------------- проверка проверки

if [ -z "$MUTATION" ]; then
    head_ "6. Проверка умеет краснеть: те же тесты по испорченным копиям"
    for m in beacon-early beacon-no-timeout no-guard stop-nothing; do
        if STOL_MUTATION="$m" bash "$0" >"$W/mutation-$m.log" 2>&1; then
            bad "мутация «$m» прошла проверку — значит проверка её не ловит"
        else
            ok "мутация «$m» поймана"
        fi
    done
fi

# ---------------------------------------------------------------- итог

printf '\n'
if [ "$FAIL" = 0 ]; then
    printf '\033[32mЗЕЛЁНО\033[0m  проверок пройдено: %s\n' "$PASS"
    exit 0
fi
printf '\033[31mКРАСНО\033[0m  пройдено %s, провалено %s\n' "$PASS" "$FAIL"
exit 1
