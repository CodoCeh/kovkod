#!/bin/bash
#
# /opt/stol/start-gui.sh — поднимает стол внутри образа и сразу возвращается.
#
# Порядок: свой machine-id → сессионная шина dbus → Xtigervnc на :1 → части стола →
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
# Размер стола назначает приложение: он равен виду, в котором стол показан, точка в
# точку. Своего значения у образа быть не должно — жёсткий размер и был причиной
# бледной размытой картинки, — но остаться оно обязано: скрипт запускают и руками, и
# проверками, где вида нет вовсе.
STOL_GEOMETRY="${STOL_GEOMETRY:-1600x1000}"
# Масштаб интерфейса XFCE (GDK_SCALE — целый множитель, без него дробный масштаб
# вернул бы ту самую интерполяцию, см. DesktopFit). Своего значения у образа быть
# не должно по той же причине, что и у размера — назначает приложение.
STOL_UI_SCALE="${STOL_UI_SCALE:-1}"
STOL_RUN_DIR="${STOL_RUN_DIR:-/run/stol}"
STOL_READY_PORT="${STOL_READY_PORT:-5910}"
# Приставка к системным путям. В образе пустая; проверке нужна, чтобы не писать
# в /etc машины, на которой она запущена.
STOL_SYSROOT="${STOL_SYSROOT:-}"

HERE="$(cd "$(dirname "$0")" && pwd)"
RFB_PORT=$((5900 + ${STOL_DISPLAY#:}))
LOG="$STOL_RUN_DIR/start.log"

mkdir -p "$STOL_RUN_DIR" || exit 1

# GPU-ускорение Adreno — только через /opt/stol/gpu/gl-run, не глобально
# (глобальный экспорт ломал xfwm4 на бете turnip a8xx). Запуск: gl-run glmark2

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
pkill -f "stol-bridge.sh" 2>/dev/null
pkill -f "window-memory.py" 2>/dev/null
rm -f "/tmp/.X${STOL_DISPLAY#:}-lock" "/tmp/.X11-unix/X${STOL_DISPLAY#:}"
rm -f "$STOL_RUN_DIR/vnc.pid" "$STOL_RUN_DIR/beacon.pid" "$STOL_RUN_DIR/xfce.pids"

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

# ---------------------------------------------------------------- предустановленные лаунчеры

mkdir -p "$STOL_SYSROOT/usr/local/bin" "$STOL_SYSROOT/root/Desktop"

cat > "$STOL_SYSROOT/usr/local/bin/claude-desktop" <<'EOF'
#!/bin/sh
if [ -x /usr/bin/claude-desktop ] && [ ! -L /usr/bin/claude-desktop ] && [ "$(realpath /usr/bin/claude-desktop 2>/dev/null)" != "$(realpath "$0" 2>/dev/null)" ]; then
    exec /usr/bin/claude-desktop --no-sandbox --user-data-dir=/root/.config/Claude --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
elif [ -x /usr/lib/claude-desktop/claude-desktop ]; then
    exec /usr/lib/claude-desktop/claude-desktop --no-sandbox --user-data-dir=/root/.config/Claude --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
elif [ -x /opt/claude-desktop/claude-desktop ]; then
    exec /opt/claude-desktop/claude-desktop --no-sandbox --user-data-dir=/root/.config/Claude --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
else
    python3 /opt/stol/store/backend.py install claude
    if [ -x /usr/bin/claude-desktop ]; then
        exec /usr/bin/claude-desktop --no-sandbox --user-data-dir=/root/.config/Claude --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

cat > "$STOL_SYSROOT/usr/local/bin/code" <<'EOF'
#!/bin/sh
if [ -x /usr/share/code/code ]; then
    exec /usr/share/code/code --no-sandbox --user-data-dir=/root/.config/Code --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
elif [ -x /usr/bin/code ] && [ ! -L /usr/bin/code ] && [ "$(realpath /usr/bin/code 2>/dev/null)" != "$(realpath "$0" 2>/dev/null)" ]; then
    exec /usr/bin/code --no-sandbox --user-data-dir=/root/.config/Code --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
else
    python3 /opt/stol/store/backend.py install code
    if [ -x /usr/share/code/code ]; then
        exec /usr/share/code/code --no-sandbox --user-data-dir=/root/.config/Code --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

cat > "$STOL_SYSROOT/usr/local/bin/antigravity" <<'EOF'
#!/bin/sh
if [ -x /opt/Antigravity-arm64/antigravity ]; then
    exec /opt/Antigravity-arm64/antigravity --no-sandbox --user-data-dir=/root/.config/Antigravity --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
elif [ -x "/opt/Antigravity IDE/antigravity-ide" ]; then
    exec "/opt/Antigravity IDE/antigravity-ide" --no-sandbox --user-data-dir=/root/.config/Antigravity --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
elif [ -x /opt/Antigravity-arm64/AppRun ]; then
    exec /opt/Antigravity-arm64/AppRun --no-sandbox --user-data-dir=/root/.config/Antigravity --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
else
    python3 /opt/stol/store/backend.py install antigravity2
    if [ -x /opt/Antigravity-arm64/antigravity ]; then
        exec /opt/Antigravity-arm64/antigravity --no-sandbox --user-data-dir=/root/.config/Antigravity --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

cat > "$STOL_SYSROOT/usr/local/bin/antigravity-ide" <<'EOF'
#!/bin/sh
exec /usr/local/bin/antigravity "$@"
EOF

cat > "$STOL_SYSROOT/usr/local/bin/obsidian" <<'EOF'
#!/bin/sh
if [ -x /opt/obsidian-app/AppRun ]; then
    exec /opt/obsidian-app/AppRun --no-sandbox --user-data-dir=/root/.config/Obsidian --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage --lang=ru "$@"
elif [ -x /opt/obsidian-app/obsidian ]; then
    exec /opt/obsidian-app/obsidian --no-sandbox --user-data-dir=/root/.config/Obsidian --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage --lang=ru "$@"
else
    python3 /opt/stol/store/backend.py install obsidian
    if [ -x /opt/obsidian-app/AppRun ]; then
        exec /opt/obsidian-app/AppRun --no-sandbox --user-data-dir=/root/.config/Obsidian --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage --lang=ru "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

cat > "$STOL_SYSROOT/usr/local/bin/zcode" <<'EOF'
#!/bin/sh
if [ -x /opt/zcode-app/AppRun ]; then
    export APPDIR=/opt/zcode-app
    exec /opt/zcode-app/AppRun --no-sandbox --user-data-dir=/root/.config/ZCode --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
elif [ -x /opt/zcode-app/zcode ]; then
    export APPDIR=/opt/zcode-app
    exec /opt/zcode-app/zcode --no-sandbox --user-data-dir=/root/.config/ZCode --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
else
    python3 /opt/stol/store/backend.py install zcode
    if [ -x /opt/zcode-app/AppRun ]; then
        export APPDIR=/opt/zcode-app
        exec /opt/zcode-app/AppRun --no-sandbox --user-data-dir=/root/.config/ZCode --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

cat > "$STOL_SYSROOT/usr/local/bin/chatgpt" <<'EOF'
#!/bin/sh
# -f, не -x: под proot [ -x ] на распакованный своими силами (не dpkg) бинарь
# лжёт (тот же обман, что описан в AGENTS.md про [ -r ]/[ -x ]), хотя сам файл
# запускается нормально.
if [ -f /usr/lib/chatgpt/ChatGPT ]; then
    exec /usr/lib/chatgpt/ChatGPT --no-sandbox --user-data-dir=/root/.config/ChatGPT --disable-gpu --enable-unsafe-swiftshader --use-gl=angle --use-angle=swiftshader --disable-dev-shm-usage "$@"
else
    python3 /opt/stol/store/backend.py install codex
    if [ -f /usr/lib/chatgpt/ChatGPT ]; then
        exec /usr/lib/chatgpt/ChatGPT --no-sandbox --user-data-dir=/root/.config/ChatGPT --disable-gpu --enable-unsafe-swiftshader --use-gl=angle --use-angle=swiftshader --disable-dev-shm-usage "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

cat > "$STOL_SYSROOT/usr/local/bin/opencode-desktop" <<'EOF'
#!/bin/sh
# -f, не -x: та же ложь proot на файлах, распакованных в обход dpkg (см. chatgpt).
if [ -f /opt/OpenCode/ai.opencode.desktop ]; then
    exec /opt/OpenCode/ai.opencode.desktop --no-sandbox --user-data-dir=/root/.config/OpenCode --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
else
    python3 /opt/stol/store/backend.py install opencode
    if [ -f /opt/OpenCode/ai.opencode.desktop ]; then
        exec /opt/OpenCode/ai.opencode.desktop --no-sandbox --user-data-dir=/root/.config/OpenCode --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

cat > "$STOL_SYSROOT/usr/local/bin/cursor" <<'EOF'
#!/bin/sh
# Бинарь и его библиотеки (libffmpeg.so, swiftshader) лежат в usr/share/cursor —
# запускать надо оттуда, не из usr/bin/cursor (копия без библиотек рядом) и не
# через AppRun (он зовёт тот же usr/bin/cursor). Флаги GPU как у chatgpt: свежий
# Electron без --enable-unsafe-swiftshader виснет насмерть. -f, не -x: ложь proot.
if [ -f /opt/cursor-app/usr/share/cursor/cursor ]; then
    exec /opt/cursor-app/usr/share/cursor/cursor --no-sandbox --user-data-dir=/root/.config/Cursor --disable-gpu --enable-unsafe-swiftshader --use-gl=angle --use-angle=swiftshader --disable-dev-shm-usage "$@"
else
    python3 /opt/stol/store/backend.py install cursor
    if [ -f /opt/cursor-app/usr/share/cursor/cursor ]; then
        exec /opt/cursor-app/usr/share/cursor/cursor --no-sandbox --user-data-dir=/root/.config/Cursor --disable-gpu --enable-unsafe-swiftshader --use-gl=angle --use-angle=swiftshader --disable-dev-shm-usage "$@"
    else
        python3 /opt/stol/store/codoceh-store.py &
    fi
fi
EOF

chmod 0755 "$STOL_SYSROOT/usr/local/bin/claude-desktop" \
           "$STOL_SYSROOT/usr/local/bin/code" \
           "$STOL_SYSROOT/usr/local/bin/antigravity" \
           "$STOL_SYSROOT/usr/local/bin/antigravity-ide" \
           "$STOL_SYSROOT/usr/local/bin/obsidian" \
           "$STOL_SYSROOT/usr/local/bin/zcode" \
           "$STOL_SYSROOT/usr/local/bin/chatgpt" \
           "$STOL_SYSROOT/usr/local/bin/opencode-desktop" \
           "$STOL_SYSROOT/usr/local/bin/cursor" 2>/dev/null || true

# ---------------------------------------------------------------- сессионная шина

# XFCE без шины не поднимается: и xfconfd, и панель, и xfdesktop ходят через неё.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games:${PATH:-}"
export DISPLAY="$STOL_DISPLAY"
export HOME="${HOME:-/root}"
# Без UTF-8 локали (по умолчанию тут голый POSIX) Wine ломает не-ASCII пути:
# кириллическое имя базы 1С выглядит как «отсутствует», хотя файл на месте.
export LANG="${LANG:-ru_RU.utf8}"
export LC_ALL="${LC_ALL:-ru_RU.utf8}"
export DBUS_SESSION_BUS_ADDRESS="unix:path=$STOL_RUN_DIR/bus"
# GDK_SCALE рисует весь интерфейс GTK крупнее в настоящих точках, а не растягивает
# готовую картинку (дробного масштаба у него нет, поэтому GDK_DPI_SCALE=1 — иначе
# шрифты укрупнились бы дважды, GDK_SCALE'ом и своей DPI-поправкой).
export GDK_SCALE="$STOL_UI_SCALE"
export GDK_DPI_SCALE=1

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

# Части стола поднимаем сами и разом. `startxfce4` заводит их по очереди, дожидаясь
# каждой, и под proot это складывается в десятки секунд: замер на живом образе дал
# xfwm4 на 6-й секунде, xfsettingsd на 13-й, панель на 21-й, стол на 37-й — всего
# 39 секунд до готовности. Те же части, запущенные одновременно, дают 6 секунд.
#
# Вместе с сессией уходит и выход из неё: гасит стол само приложение, поэтому
# кнопка выхода на панели убрана (reference/xfconf/xfce4-panel.txt).
: > "$STOL_RUN_DIR/xfce.pids"
for part in xfwm4 xfsettingsd xfce4-panel xfdesktop; do
    setsid env DISPLAY="$STOL_DISPLAY" \
        DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" \
        HOME="${HOME:-/root}" \
        "$part" >>"$LOG" 2>&1 </dev/null &
    echo $! >> "$STOL_RUN_DIR/xfce.pids"
done

# Служба файлового менеджера. Без неё ярлык со стола не открывается вовсе: xfdesktop
# отдаёт запуск .desktop службе org.xfce.FileManager и без неё на двойной клик
# отвечает окном «Не удалось запустить… требуется служба файлового менеджера».
# Проверено на живом устройстве: без этой строки не открывается ни магазин, ни офис.
setsid env DISPLAY="$STOL_DISPLAY" \
    DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" \
    HOME="${HOME:-/root}" \
    thunar --daemon >>"$LOG" 2>&1 </dev/null &
echo $! >> "$STOL_RUN_DIR/xfce.pids"

setsid env DISPLAY="$STOL_DISPLAY" \
    DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" \
    HOME="${HOME:-/root}" STOL_RUN_DIR="$STOL_RUN_DIR" \
    "$HERE/stol-bridge.sh" >>"$LOG" 2>&1 </dev/null &
echo $! >> "$STOL_RUN_DIR/xfce.pids"

setsid env DISPLAY="$STOL_DISPLAY" \
    DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" \
    HOME="${HOME:-/root}" \
    python3 "$HERE/window-memory.py" >>"$LOG" 2>&1 </dev/null &
echo $! >> "$STOL_RUN_DIR/xfce.pids"

setsid env HOME="${HOME:-/root}" "$HERE/sound.sh" >>"$LOG" 2>&1 </dev/null &
echo $! >> "$STOL_RUN_DIR/xfce.pids"

log "части стола запущены разом: xfwm4, xfsettingsd, панель, рабочий стол, файловая служба, мост, память окон, звук"

# ---------------------------------------------------------------- маячок

start_beacon

log "готово: сервер на $RFB_PORT, ждите порт $STOL_READY_PORT"
exit 0
