#!/usr/bin/env bash
#
# store/test-store.sh — проверка магазина настоящей установкой (R02, R03i).
#
# Ничего не симулирует. Внутри живого стола (образ из build/image, развёрнутый под
# proot в виртуалке lima) магазин своими же командами ставит программу и игру,
# каждая запускается и должна прожить 15 секунд с окном в списке окон. Отдельно
# рвётся установка на распаковке — пакетный менеджер обязан вернуться в строй.
#
# Проверка умеет краснеть:
#   STOL_STORE_NO_AUTOSTART=1   не поднимать стол самому — нет стола, красное;
#   STOL_STORE_MUTATION=stub-install   установка подменена заглушкой;
#   STOL_STORE_MUTATION=stub-repair    восстановление после обрыва подменено заглушкой.
#
# Переменные окружения: STOL_LIMA_VM (виртуалка, по умолчанию ceho), STOL_ROOTFS,
# STOL_STORE_APP и STOL_STORE_GAME (id из catalog.json), STOL_STORE_BREAK_PKG.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORE_DIR="$REPO/store"
ART="$STORE_DIR/artifacts"

VM="${STOL_LIMA_VM:-ceho}"
WORK_VM="${STOL_STORE_WORK:-/var/tmp/stol-store}"
ROOTFS="${STOL_ROOTFS:-$WORK_VM/rootfs}"
IMAGE_DIR="$REPO/build/image"
APP_ID="${STOL_STORE_APP:-galculator}"
GAME_ID="${STOL_STORE_GAME:-aisleriot}"
BREAK_PKG="${STOL_STORE_BREAK_PKG:-geany}"
MUTATION="${STOL_STORE_MUTATION:-}"
NO_AUTOSTART="${STOL_STORE_NO_AUTOSTART:-}"
ALIVE_SECONDS="${STOL_STORE_ALIVE:-15}"

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); printf '    \033[32mОК\033[0m    %s\n' "$*"; }
bad()  { FAIL=$((FAIL + 1)); printf '    \033[31mПЛОХО\033[0m %s\n' "$*"; }
log()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
die()  { printf '\n\033[31mКРАСНО\033[0m  %s\n' "$*" >&2; exit 1; }

vm()    { limactl shell "$VM" -- sudo bash -c "$1"; }
put()   { limactl shell "$VM" -- sudo tee "$ROOTFS/tmp/$1" >/dev/null; }
guest() { limactl shell "$VM" -- sudo "$WORK_VM/login.sh" "$ROOTFS" "/tmp/$1"; }

# ------------------------------------------------------------------ окружение

log "0. Живая система, на которой ставим"

command -v limactl >/dev/null || die "нет limactl: настоящую установку ставить некуда"
limactl list --format '{{.Name}}' 2>/dev/null | grep -qx "$VM" \
    || die "виртуалки lima '$VM' нет — поднимите её или задайте STOL_LIMA_VM"
[ "$(limactl list --format '{{.Status}}' "$VM" 2>/dev/null)" = "Running" ] \
    || die "виртуалка '$VM' не запущена: limactl start $VM"
[ "$(limactl shell "$VM" -- uname -m 2>/dev/null)" = "aarch64" ] \
    || die "виртуалка '$VM' не aarch64 — образ arm64 там не запустится"
ok "виртуалка $VM жива, aarch64"

[ -f "$IMAGE_DIR/rootfs.tar.zst" ] || die "нет образа $IMAGE_DIR/rootfs.tar.zst — соберите tools/mkimage.sh"
WANT_SHA="$(cut -d' ' -f1 < "$IMAGE_DIR/rootfs.tar.zst.sha256")"

vm "mkdir -p '$WORK_VM'" || die "не получается писать в $WORK_VM внутри виртуалки"
# proot-вход, повторяющий то, что делает APK: тот же rootfs, тот же -0.
put_login() {
    limactl shell "$VM" -- sudo tee "$WORK_VM/login.sh" >/dev/null <<'LOGIN'
#!/bin/bash
# Вход в образ, как это делает APK: proot с подменой root. PROOT_NO_SECCOMP —
# обход краха proot 5.4.0 на ядре виртуалки, к образу отношения не имеет.
R="$1"; S="$2"
export PROOT_NO_SECCOMP=1
exec proot --kill-on-exit --link2symlink -0 -r "$R" \
    -b /dev -b /proc -b /sys -b "$R/root:/dev/shm" -w /root \
    /usr/bin/env -i HOME=/root TERM=xterm \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LANG=C.UTF-8 /bin/bash -l "$S"
LOGIN
    vm "chmod +x '$WORK_VM/login.sh'"
}
put_login || die "не удалось положить proot-вход в виртуалку"
limactl shell "$VM" -- command -v proot >/dev/null \
    || die "в виртуалке нет proot — установите: sudo apt-get install -y proot"

HAVE_SHA="$(vm "cat '$WORK_VM/deployed.sha' 2>/dev/null" 2>/dev/null | tr -d '\r\n')"
if ! vm "test -x '$ROOTFS/opt/stol/start-gui.sh'" 2>/dev/null || [ "$HAVE_SHA" != "$WANT_SHA" ]; then
    printf '    разворачиваем образ %s внутрь виртуалки, это около минуты\n' "$(basename "$IMAGE_DIR")"
    # Живой стол в старом образе пишет в него прямо во время удаления и мешает
    # каталогу опустеть — сначала гасим всё, что там запущено.
    vm "pkill -9 -f '$ROOTFS' >/dev/null 2>&1; sleep 1; exit 0" >/dev/null 2>&1
    vm "set -e
        rm -rf '$ROOTFS'; mkdir -p '$ROOTFS'
        zstd -dc '$IMAGE_DIR/rootfs.tar.zst' | tar -C '$ROOTFS' -xf - 2>/dev/null
        printf '%s' '$WANT_SHA' > '$WORK_VM/deployed.sha'" \
        || die "образ не развернулся в виртуалке"
    ok "образ развёрнут заново ($WANT_SHA)"
else
    ok "образ уже развёрнут и совпадает с build/image ($WANT_SHA)"
fi

# Магазин внутри образа подменяем на текущий из репозитория: проверяем тот код,
# который лежит здесь, а не тот, что вмёрз в образ при прошлой сборке.
vm "install -d '$ROOTFS/opt/stol/store' && cp '$STORE_DIR/backend.py' '$STORE_DIR/catalog.json' '$STORE_DIR/codoceh-store.py' '$ROOTFS/opt/stol/store/'" \
    || die "не удалось положить текущий магазин внутрь образа"
ok "внутрь образа положен магазин из репозитория"

# ------------------------------------------------------------------ стол

log "1. Стол на :1 (без него ставить некуда)"

put probe.sh <<'PROBE'
#!/bin/bash
export DISPLAY=:1
list="$(wmctrl -l 2>/dev/null)" || exit 1
printf '%s\n' "$list" | grep -q xfce4-panel || exit 1
printf '%s\n' "$list" | grep -q Desktop || exit 1
exit 0
PROBE

put desk.sh <<'DESK'
#!/bin/bash
/opt/stol/start-gui.sh || exit 1
sleep 86400
DESK

if guest probe.sh >/dev/null 2>&1; then
    ok "стол уже поднят"
else
    if [ -n "$NO_AUTOSTART" ]; then
        die "стола на :1 нет, а STOL_STORE_NO_AUTOSTART запрещает его поднимать — ставить некуда"
    fi
    printf '    поднимаем стол внутри образа\n'
    vm "setsid '$WORK_VM/login.sh' '$ROOTFS' /tmp/desk.sh </dev/null >'$WORK_VM/desk.log' 2>&1 & disown; exit 0" >/dev/null 2>&1
    up=""
    for _ in $(seq 1 45); do
        sleep 2
        if guest probe.sh >/dev/null 2>&1; then up=1; break; fi
    done
    [ -n "$up" ] || die "стол не поднялся за 90 секунд, журнал: $WORK_VM/desk.log в виртуалке"
    ok "стол поднят"
fi

# Инструмент снимков экрана. В образ ImageMagick не входит и входить не должен —
# это оснастка проверки, поэтому ставится отдельно и в доказательство не идёт.
put prep.sh <<'PREP'
#!/bin/bash
command -v import >/dev/null && exit 0
export DEBIAN_FRONTEND=noninteractive
# На свежеразвёрнутом образе списки пакетов пусты, и первое обновление иногда не
# успевает за сетью виртуалки — поэтому вторая попытка, а не одна.
for _ in 1 2; do
    apt-get update -y >>/tmp/prep.log 2>&1
    apt-get install -y --no-install-recommends imagemagick >>/tmp/prep.log 2>&1
    command -v import >/dev/null && exit 0
done
tail -3 /tmp/prep.log
exit 1
PREP
if guest prep.sh >/dev/null 2>&1; then
    ok "оснастка снимков экрана готова"
else
    bad "ImageMagick не поставился — снимков экрана не будет"
fi

mkdir -p "$ART"

# ------------------------------------------------------------------ полезные части

put install.sh <<'INSTALL'
#!/bin/bash
# Ставит пакет ровно теми командами, которые выдаёт сам магазин.
. /tmp/params.sh
cd /opt/stol/store || exit 2

PKG="$(python3 -c "
import backend, sys
item = [i for i in backend.load_catalog() if i['id'] == '$ITEM_ID']
if not item:
    sys.exit(3)
print(item[0]['package'])
")" || { echo "в каталоге магазина нет позиции $ITEM_ID"; exit 3; }
echo "позиция каталога: $ITEM_ID -> пакет $PKG"

export DEBIAN_FRONTEND=noninteractive
installed() { python3 -c "import backend, sys; sys.exit(0 if backend.is_installed('$PKG') else 1)"; }
purge() { apt-get remove -y --purge "$PKG" >/dev/null 2>&1; apt-get autoremove -y >/dev/null 2>&1; }

purge
if installed; then
    # Пакетный менеджер мог остаться сломанным после прошлого прогона с обрывом:
    # чиним тем же, чем чинит магазин, и пробуем убрать пакет ещё раз.
    python3 -c "import backend, sys; sys.stdout.write(backend.repair_command(''))" > /tmp/pre-repair.sh
    bash /tmp/pre-repair.sh >/tmp/pre-repair.log 2>&1
    purge
fi
if installed; then
    echo "пакет $PKG не удалось убрать перед проверкой — установка была бы мнимой"
    exit 4
fi
echo "перед установкой пакета нет"

if [ "${MUTATION:-}" = "stub-install" ]; then
    echo "МУТАЦИЯ stub-install: установка подменена заглушкой"
    true
else
    python3 -c "import backend, sys; sys.stdout.write(backend.install_command('$PKG'))" > /tmp/install-cmd.sh
    if ! bash /tmp/install-cmd.sh > /tmp/install.log 2>&1; then
        echo "команда установки магазина вернула ошибку:"
        tail -5 /tmp/install.log
        exit 5
    fi
fi

if ! python3 -c "import backend, sys; sys.exit(0 if backend.is_installed('$PKG') else 1)"; then
    echo "после установки магазин считает $PKG неустановленным"
    exit 6
fi
dpkg-query -W -f='установлено: ${Package} ${Version} (${Status})\n' "$PKG"
INSTALL

put launch.sh <<'LAUNCH'
#!/bin/bash
# Запускает установленное так же, как это сделал бы ярлык на столе: строкой Exec
# из .desktop, положенного пакетом, и с переменными окружения из каталога.
. /tmp/params.sh
cd /opt/stol/store || exit 2

read -r PKG ENVLINE <<< "$(python3 -c "
import backend
item = [i for i in backend.load_catalog() if i['id'] == '$ITEM_ID'][0]
print(item['package'], item.get('env', ''))
")"

DESKTOP="$(dpkg -L "$PKG" | grep -E '^/usr/share/applications/.*\.desktop$' | head -1)"
[ -n "$DESKTOP" ] || { echo "пакет $PKG не положил ни одного ярлыка"; exit 3; }
EXECLINE="$(grep -m1 '^Exec=' "$DESKTOP" | cut -d= -f2- | sed 's/%[A-Za-z]//g')"
[ -n "$EXECLINE" ] || { echo "в $DESKTOP нет строки Exec"; exit 3; }
BIN="${EXECLINE%% *}"

export DISPLAY=:1
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/stol/bus
# Игры Debian кладёт в /usr/games, а в PATH сессии его нет (см. отчёт задачи).
command -v "$BIN" >/dev/null || export PATH="$PATH:/usr/games:/usr/local/games"
command -v "$BIN" >/dev/null || { echo "двоичного файла $BIN нет даже с /usr/games"; exit 4; }
if [ -n "$ENVLINE" ]; then
    export $ENVLINE
    echo "окружение из каталога: $ENVLINE"
fi
echo "запуск: $EXECLINE"

wmctrl -lp > /tmp/win-before.txt 2>/dev/null
$EXECLINE > "/tmp/app-$ITEM_ID.log" 2>&1 &
PID=$!

# Список окон всегда пишем в файл и читаем оттуда: под proot ранний выход из
# конвейера (тот же «grep -q») роняет wmctrl по SIGPIPE и превращается в
# «Operation not permitted» — проверка начинает врать.
WID=""
for _ in $(seq 1 40); do
    sleep 1
    kill -0 $PID 2>/dev/null || { echo "процесс умер, не показав окна:"; tail -3 "/tmp/app-$ITEM_ID.log"; exit 5; }
    wmctrl -lp > /tmp/win-now.txt 2>/dev/null
    while read -r id desk wpid rest; do
        grep -q "^$id " /tmp/win-before.txt && continue
        [ "$wpid" = "$PID" ] || continue
        WID="$id"; TITLE="$rest"
    done < /tmp/win-now.txt
    [ -n "$WID" ] && break
done
[ -n "$WID" ] || { echo "окно не появилось за 40 секунд"; tail -3 "/tmp/app-$ITEM_ID.log"; exit 6; }
echo "окно $WID появилось: $TITLE"

sleep "$ALIVE_SECONDS"
kill -0 $PID 2>/dev/null || { echo "процесс умер за $ALIVE_SECONDS секунд"; exit 7; }
wmctrl -lp > /tmp/win-after.txt 2>/dev/null
grep -q "^$WID " /tmp/win-after.txt || { echo "окно $WID пропало за $ALIVE_SECONDS секунд"; exit 8; }
echo "окно $WID живо через $ALIVE_SECONDS секунд"
grep "^$WID " /tmp/win-after.txt

import -window root "/tmp/shot-$ITEM_ID.png" 2>/dev/null && echo "снимок: /tmp/shot-$ITEM_ID.png"
kill $PID 2>/dev/null
LAUNCH

put interrupt.sh <<'INTERRUPT'
#!/bin/bash
# Рвёт настоящую установку на распаковке и требует, чтобы магазин вернул
# пакетный менеджер в рабочее состояние.
. /tmp/params.sh
cd /opt/stol/store || exit 2
PKG="$BREAK_PKG"
export DEBIAN_FRONTEND=noninteractive

python3 -c "import backend, sys; sys.stdout.write(backend.install_command('$PKG'))" > /tmp/break-cmd.sh

broke=""
for attempt in 1 2 3; do
    apt-get remove -y --purge "$PKG" >/dev/null 2>&1
    bash /tmp/break-cmd.sh >/tmp/break.log 2>&1 &
    AP=$!
    # Ждём не «появился процесс dpkg», а состояние самого пакета: как только он
    # стал half-installed или unpacked, транзакция дошла до середины, и убийство
    # dpkg оставляет ровно то полуустановленное состояние, ради которого всё это.
    seen=""
    for _ in $(seq 1 4000); do
        case "$(dpkg-query -W -f='${Status}' "$PKG" 2>/dev/null)" in
            *half-installed*|*unpacked*|*half-configured*)
                seen=1
                pkill -9 -x dpkg
                kill -9 $AP 2>/dev/null
                break
                ;;
        esac
        kill -0 $AP 2>/dev/null || break
        sleep 0.05
    done
    [ -n "$seen" ] || { echo "попытка $attempt: установка закончилась раньше, чем dpkg начал транзакцию"; continue; }
    sleep 1
    if ! apt-get check >/tmp/chk-broken.log 2>&1; then broke=1; break; fi
    echo "попытка $attempt: обрыв прошёл бесследно, повторяем"
done
[ -n "$broke" ] || { echo "обрыв не удалось воспроизвести — доказывать нечего"; exit 3; }
echo "после обрыва apt-get check красный: $(tail -1 /tmp/chk-broken.log)"
dpkg --audit | head -4

if [ "${MUTATION:-}" = "stub-repair" ]; then
    echo "МУТАЦИЯ stub-repair: восстановление подменено заглушкой"
    true
else
    python3 -c "import backend, sys; sys.stdout.write(backend.repair_command(''))" > /tmp/repair.sh
    bash /tmp/repair.sh >/tmp/repair.log 2>&1
    echo "восстановление магазина отработало"
fi

if ! apt-get check >/tmp/chk-after.log 2>&1; then
    echo "пакетный менеджер так и остался сломанным: $(tail -1 /tmp/chk-after.log)"
    exit 4
fi
lines="$(dpkg --audit | wc -l)"
[ "$lines" -eq 0 ] || { echo "dpkg --audit не пуст: $lines строк"; dpkg --audit | head -4; exit 5; }
echo "apt-get check зелёный, dpkg --audit пуст"
INTERRUPT

params() {
    printf 'ITEM_ID=%s\nBREAK_PKG=%s\nMUTATION=%s\nALIVE_SECONDS=%s\n' \
        "$1" "$BREAK_PKG" "$MUTATION" "$ALIVE_SECONDS" | put params.sh
}

fetch_shot() {
    local id="$1" dest="$ART/$1.png"
    if vm "test -f '$ROOTFS/tmp/shot-$id.png'" 2>/dev/null; then
        limactl shell "$VM" -- sudo cat "$ROOTFS/tmp/shot-$id.png" > "$dest" 2>/dev/null
        [ -s "$dest" ] && printf '    снимок экрана: %s\n' "$dest"
    fi
}

# ------------------------------------------------------------------ программа

log "2. Программа из магазина: установка и запуск (R02.1)"
params "$APP_ID"
if guest install.sh; then
    ok "программа $APP_ID поставлена магазином внутрь стола"
    if guest launch.sh; then
        ok "окно программы $APP_ID живо через $ALIVE_SECONDS секунд"
        fetch_shot "$APP_ID"
    else
        bad "программа $APP_ID не запустилась или окно не прожило $ALIVE_SECONDS секунд"
    fi
else
    bad "магазин не поставил программу $APP_ID"
fi

# ------------------------------------------------------------------ игра

log "3. Игра из магазина на программной отрисовке (R02.2)"
GAME_ENV="$(python3 -c "
import json, sys
cat = json.load(open('$STORE_DIR/catalog.json', encoding='utf-8'))
item = [i for i in cat if i['id'] == '$GAME_ID']
print(item[0].get('env', '') if item else '')
")"
case "$GAME_ENV" in
    *LIBGL_ALWAYS_SOFTWARE*) ok "в каталоге у игры $GAME_ID стоит программная отрисовка: $GAME_ENV" ;;
    *) bad "у игры $GAME_ID в каталоге нет LIBGL_ALWAYS_SOFTWARE — аппаратного ускорения под proot нет" ;;
esac

params "$GAME_ID"
if guest install.sh; then
    ok "игра $GAME_ID поставлена магазином внутрь стола"
    if guest launch.sh; then
        ok "окно игры $GAME_ID живо через $ALIVE_SECONDS секунд"
        fetch_shot "$GAME_ID"
    else
        bad "игра $GAME_ID не запустилась или окно не прожило $ALIVE_SECONDS секунд"
    fi
else
    bad "магазин не поставил игру $GAME_ID"
fi

# ------------------------------------------------------------------ обрыв

log "4. Обрыв установки не оставляет систему полуустановленной (R02.3)"
params "$APP_ID"
if guest interrupt.sh; then
    ok "после убитого на распаковке dpkg магазин вернул пакетный менеджер в строй"
else
    bad "после обрыва установки пакетный менеджер остался сломанным"
fi

# ------------------------------------------------------------------ итог

log "Итог"
printf '    пройдено: %d, провалено: %d\n' "$PASS" "$FAIL"
[ -n "$MUTATION" ] && printf '    прогон шёл с мутацией: %s\n' "$MUTATION"
if [ "$FAIL" -eq 0 ]; then
    printf '\n\033[32mЗЕЛЁНО\033[0m  проверок пройдено: %d\n' "$PASS"
    exit 0
fi
printf '\n\033[31mКРАСНО\033[0m  провалено проверок: %d\n' "$FAIL"
exit 1
