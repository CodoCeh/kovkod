#!/usr/bin/env bash
#
# store/test-store.sh — проверка магазина настоящей установкой (R02, R03i).
#
# Ничего не симулирует. Внутри живого стола (образ из build/image, развёрнутый под
# proot в виртуалке lima) магазин своими же командами ставит программу и каждую
# игру каталога, каждая запускается и должна прожить 15 секунд с окном в списке
# окон. Отдельно проверяется правило отбора каталога — в прямых зависимостях нет
# ни libgl1, ни libgtk-4-1 — и рвётся установка на распаковке: пакетный менеджер
# обязан вернуться в строй.
#
# Что доказывается на каждой позиции, а что один раз: установка и живое окно —
# на каждой игре, иначе строка в каталоге ничего не значит; стол, оснастка
# снимков, правило отбора и обрыв установки — один раз на прогон, они про
# магазин, а не про игру.
#
# Проверка умеет краснеть:
#   STOL_STORE_NO_AUTOSTART=1   не поднимать стол самому — нет стола, красное;
#   STOL_STORE_MUTATION=stub-install   установка подменена заглушкой;
#   STOL_STORE_MUTATION=stub-repair    восстановление после обрыва подменено заглушкой;
#   STOL_STORE_MUTATION=bad-catalog    в каталог подложена игра на GTK4.
#
# Переменные окружения: STOL_LIMA_VM (виртуалка, по умолчанию ceho), STOL_ROOTFS,
# STOL_STORE_APP (id из catalog.json), STOL_STORE_GAME (один id вместо всех игр
# каталога), STOL_STORE_BREAK_PKG.

set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STORE_DIR="$REPO/store"
ART="$STORE_DIR/artifacts"

VM="${STOL_LIMA_VM:-ceho}"
WORK_VM="${STOL_STORE_WORK:-/var/tmp/stol-store}"
ROOTFS="${STOL_ROOTFS:-$WORK_VM/rootfs}"
IMAGE_DIR="$REPO/build/image"
APP_ID="${STOL_STORE_APP:-galculator}"
GAME_ID="${STOL_STORE_GAME:-}"
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
    # Стол от прошлого образа гасим по-настоящему. Двух причин достаточно: он
    # пишет в удаляемый каталог и мешает тому опустеть, а главное — Xtigervnc и
    # XFCE переживают удаление своего образа и остались бы столом от прошлой
    # сборки, то есть проверка доказывала бы не то, что развёрнута.
    if vm "test -x '$ROOTFS/opt/stol/stop-gui.sh'" 2>/dev/null; then
        printf '#!/bin/bash\n/opt/stol/stop-gui.sh\n' | put stop.sh
        guest stop.sh >/dev/null 2>&1
    fi
    vm "pkill -9 -x Xtigervnc >/dev/null 2>&1; pkill -9 -f '$ROOTFS' >/dev/null 2>&1; sleep 1; exit 0" >/dev/null 2>&1
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
CATALOG_SRC="$STORE_DIR/catalog.json"
if [ "$MUTATION" = "bad-catalog" ]; then
    # Рычаг для самой проверки: подкладываем в каталог заведомо неподходящую игру —
    # gnome-mines тянет libgtk-4-1 и падает с ошибкой сегментации без ускорения.
    # Правило отбора обязано покраснеть здесь, а не после установки.
    CATALOG_SRC="$ART/catalog-mutated.json"
    mkdir -p "$ART"
    python3 - "$STORE_DIR/catalog.json" "$CATALOG_SRC" <<'MUT' || die "не удалось собрать каталог с мутацией"
import json, sys
cat = json.load(open(sys.argv[1], encoding="utf-8"))
cat.append({
    "id": "мутация-тяжёлая-игра", "name": "Сапёр", "category": "Игры",
    "package": "gnome-mines", "summary": "Игра на GTK4, подложенная мутацией",
    "env": "LIBGL_ALWAYS_SOFTWARE=1",
})
json.dump(cat, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
MUT
fi
vm "install -d '$ROOTFS/opt/stol/store' && cp '$STORE_DIR/backend.py' '$STORE_DIR/codoceh-store.py' '$ROOTFS/opt/stol/store/'" \
    || die "не удалось положить текущий магазин внутрь образа"
limactl shell "$VM" -- sudo tee "$ROOTFS/opt/stol/store/catalog.json" < "$CATALOG_SRC" >/dev/null \
    || die "не удалось положить каталог внутрь образа"
# Строки состояния прошлого прогона стираем: доказательством движения может быть
# только то, что записано сейчас.
vm "rm -f '$ROOTFS'/tmp/progress-*.tsv" >/dev/null 2>&1
ok "внутрь образа положен магазин из репозитория"

# ------------------------------------------------------------------ стол

log "1. Стол на :1 (без него ставить некуда)"

put probe.sh <<'PROBE'
#!/bin/bash
# Стол опознаём по классам окон, а не по заголовкам: образ стал русским вместе с
# офисом, и «Desktop» превратился в «Рабочий стол» — проверка на заголовок молча
# перестала находить поднятый стол.
export DISPLAY=:1
wmctrl -lx > /tmp/probe-win.txt 2>/dev/null || exit 1
grep -q 'Xfce4-panel' /tmp/probe-win.txt || exit 1
grep -q 'Xfdesktop' /tmp/probe-win.txt || exit 1
exit 0
PROBE

put desk.sh <<'DESK'
#!/bin/bash
/opt/stol/start-gui.sh || exit 1
sleep 86400
DESK

# Рычаг «без стола — красное» обязан краснеть и тогда, когда стол остался от
# прошлого прогона. Иначе он доказывает не зависимость проверки от стола, а лишь
# то, что стол кто-то когда-то поднял: прошлый прогон так и прошёл зелёным с
# выключенным подъёмом стола. Поэтому под рычагом стол сначала гасим.
if [ -n "$NO_AUTOSTART" ]; then
    printf '    STOL_STORE_NO_AUTOSTART: гасим стол, оставшийся от прошлого раза\n'
    if vm "test -x '$ROOTFS/opt/stol/stop-gui.sh'" 2>/dev/null; then
        printf '#!/bin/bash\n/opt/stol/stop-gui.sh\n' | put stop.sh
        guest stop.sh >/dev/null 2>&1
    fi
    vm "pkill -9 -x Xtigervnc >/dev/null 2>&1; pkill -9 -f '$ROOTFS' >/dev/null 2>&1; sleep 1; exit 0" >/dev/null 2>&1
    guest probe.sh >/dev/null 2>&1 \
        && die "стол не погас — рычаг «без стола» доказывал бы не то, что обещает"
    die "стола на :1 нет, а STOL_STORE_NO_AUTOSTART запрещает его поднимать — ставить некуда"
fi

if guest probe.sh >/dev/null 2>&1; then
    ok "стол уже поднят"
else
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
# Ставится не здесь, а после первой удавшейся установки: на свежеразвёрнутом
# образе первое «apt-get update» под proot нередко валится проверкой подписи
# (sqv: Operation not permitted), и списки пакетов появляются только со второго
# захода — того самого, который делает сам магазин.
put prep.sh <<'PREP'
#!/bin/bash
command -v import >/dev/null && exit 0
export DEBIAN_FRONTEND=noninteractive
for _ in 1 2 3; do
    apt-get update -y >>/tmp/prep.log 2>&1
    apt-get install -y --no-install-recommends imagemagick >>/tmp/prep.log 2>&1
    command -v import >/dev/null && exit 0
    sleep 2
done
tail -3 /tmp/prep.log
exit 1
PREP

SHOTS=""
ensure_shots() {
    [ -n "$SHOTS" ] && return 0
    if guest prep.sh >/dev/null 2>&1; then
        SHOTS=1
        ok "оснастка снимков экрана готова"
    else
        SHOTS=нет
        bad "ImageMagick не поставился — снимков экрана не будет"
    fi
}

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
    # Ставим тем же кодом, каким это делает окно магазина: список аргументов и
    # поток состояния apt. Строки состояния с метками времени ложатся в файл —
    # по нему потом доказывается, что окно не стоит без признаков жизни.
    rm -f "/tmp/progress-$ITEM_ID.tsv" /tmp/install.log
    if ! python3 backend.py install "$PKG" \
            --progress "/tmp/progress-$ITEM_ID.tsv" --log /tmp/install.log > /tmp/install-out.log 2>&1; then
        echo "установка магазина вернула ошибку:"
        tail -5 /tmp/install.log
        tail -3 /tmp/install-out.log
        exit 5
    fi
    echo "строк состояния за установку: $(wc -l < "/tmp/progress-$ITEM_ID.tsv")"
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

# Список файлов пакета гоняем через файл: у sgt-puzzles сорок ярлыков и больше
# сотни файлов, а «dpkg -L | grep | head -1» закрывает конвейер на первой строке
# и роняет пишущую сторону под proot с «Operation not permitted».
dpkg -L "$PKG" > /tmp/files.txt 2>/dev/null
grep -E '^/usr/share/applications/.*\.desktop$' /tmp/files.txt > /tmp/desktops.txt
DESKTOP="$(head -1 /tmp/desktops.txt)"
[ -n "$DESKTOP" ] || { echo "пакет $PKG не положил ни одного ярлыка"; exit 3; }
EXECLINE="$(grep -m1 '^Exec=' "$DESKTOP" | cut -d= -f2- | sed 's/%[A-Za-z]//g')"
[ -n "$EXECLINE" ] || { echo "в $DESKTOP нет строки Exec"; exit 3; }
BIN="${EXECLINE%% *}"

export DISPLAY=:1
export DBUS_SESSION_BUS_ADDRESS=unix:path=/run/stol/bus
# PATH берём тот, что даёт сам образ, и ничего к нему не дописываем: игры лежат в
# /usr/games, и если образ перестанет их туда пускать, ярлык из меню сломается —
# проверка обязана это поймать, а не обойти.
command -v "$BIN" >/dev/null || { echo "двоичного файла $BIN нет в PATH сессии: $PATH"; exit 4; }
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
    # Сломанным считается не только развал зависимостей. Убитый на распаковке dpkg
    # чаще оставляет пакет распакованным-но-ненастроенным: apt-get check на такое
    # молчит, а установка при этом не доведена, и без вмешательства так и висит.
    # Считать это «прошло бесследно» — значит требовать от проверки редкого случая
    # и краснеть на пустом месте, что и случилось на свежем образе.
    apt-get check >/tmp/chk-broken.log 2>&1 || broke="apt-get check: $(tail -1 /tmp/chk-broken.log)"
    dpkg --audit > /tmp/audit-broken.txt 2>&1
    [ -n "$broke" ] || [ ! -s /tmp/audit-broken.txt ] || broke="dpkg --audit: $(head -1 /tmp/audit-broken.txt)"
    status_broken="$(dpkg-query -W -f='${Status}' "$PKG" 2>/dev/null)"
    case "${broke:-}${status_broken}" in
        ""|"install ok installed") ;;
        *) [ -n "$broke" ] || broke="статус пакета: $status_broken" ;;
    esac
    [ -z "$broke" ] || break
    echo "попытка $attempt: обрыв прошёл бесследно, повторяем"
done
[ -n "$broke" ] || { echo "обрыв не удалось воспроизвести — доказывать нечего"; exit 3; }
echo "после обрыва система нездорова — $broke"
# Вывод складываем в файл и оттуда читаем: под proot ранний обрыв конвейера
# («head») ломает пишущую сторону и сорит ложными ошибками.
dpkg --audit > /tmp/audit-broken.txt 2>&1; head -4 /tmp/audit-broken.txt

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
echo "apt-get check зелёный: $(head -1 /tmp/chk-after.log 2>/dev/null)"

# Спрашиваем с восстановления сразу, не дожидаясь повторной установки: сама
# установка доводит распакованный пакет до конца и тем самым прикрыла бы
# неработающее восстановление. Мутация stub-repair ловится именно здесь.
status_repaired="$(dpkg-query -W -f='${Status}' "$PKG" 2>/dev/null)"
[ "$status_repaired" = "install ok installed" ] \
    || { echo "после восстановления статус $PKG: ${status_repaired:-нет пакета}"; exit 4; }
dpkg --audit > /tmp/audit-repaired.txt 2>&1
[ ! -s /tmp/audit-repaired.txt ] \
    || { echo "после восстановления dpkg --audit не пуст:"; head -4 /tmp/audit-repaired.txt; exit 4; }
echo "восстановление довело установку до конца: статус «$status_repaired», dpkg --audit пуст"

# Одного зелёного apt-get check мало: недоустановленный пакет остаётся лежать
# половиной. Доказательство того, что система не застряла, — та же установка,
# повторённая магазином: она обязана пройти до конца и вычистить полуустановку.
if ! bash /tmp/break-cmd.sh >/tmp/retry.log 2>&1; then
    echo "повторная установка после обрыва не прошла:"
    tail -4 /tmp/retry.log
    exit 5
fi
status="$(dpkg-query -W -f='${Status}' "$PKG" 2>/dev/null)"
[ "$status" = "install ok installed" ] || { echo "после повторной установки статус $PKG: $status"; exit 6; }
dpkg --audit > /tmp/audit-after.txt 2>&1
lines="$(wc -l < /tmp/audit-after.txt)"
[ "$lines" -eq 0 ] || { echo "dpkg --audit не пуст: $lines строк"; head -4 /tmp/audit-after.txt; exit 7; }
echo "повторная установка прошла, $PKG установлен целиком, dpkg --audit пуст"
INTERRUPT

put catalog-rule.sh <<'RULE'
#!/bin/bash
# Правило отбора каталога (R02.1, R02.3i). Признак проверяется по пакетной базе,
# а не на глаз: игра на OpenGL, случайно взлетевшая на программной отрисовке,
# прошла бы вопреки объявленному отбору. libgtk-4-1 отдельной строкой — GTK4 с
# версии 4.14 не откатывается на cairo и падает без ускорения (gnome-mines).
cd /opt/stol/store || exit 2
export DEBIAN_FRONTEND=noninteractive
# Разбор вывода apt требует английского. Образ русский вместе с офисом: там, где
# ждали «Candidate» и «Depends», приходят «Кандидат» и «Зависит», и правило молча
# отказывает всем подряд вместо того, чтобы отбирать.
export LC_ALL=C LANGUAGE=C LANG=C
# Первое обновление списков под proot нестабильно: проверка подписи валится с
# «sqv: Operation not permitted», списки появляются со второго захода.
got=""
for _ in 1 2 3; do
    apt-get update -y >>/tmp/rule-update.log 2>&1 && { got=1; break; }
    sleep 2
done
[ -n "$got" ] || { echo "списки пакетов не обновились, отбор проверять не по чему"; tail -3 /tmp/rule-update.log; exit 3; }

python3 -c "
import backend
for i in backend.load_catalog():
    print('%s\t%s\t%s' % (i['id'], i['package'], i.get('category', '')))
" > /tmp/rule-items.txt || { echo "каталог магазина не читается"; exit 3; }

bad=0
total=0
games=0
while IFS=$'\t' read -r id pkg category; do
    total=$((total + 1))
    apt-cache policy "$pkg" > /tmp/rule-policy.txt 2>&1
    cand="$(awk '/Candidate:/{print $2; exit}' /tmp/rule-policy.txt)"
    if [ -z "$cand" ] || [ "$cand" = "(none)" ]; then
        echo "  ОТКАЗ $id: пакета $pkg нет в пакетной базе"
        bad=$((bad + 1))
        continue
    fi
    # Только прямые зависимости: рекомендации, предложения и обратные связи выключены.
    apt-cache depends --no-recommends --no-suggests --no-conflicts \
        --no-breaks --no-replaces --no-enhances "$pkg" > /tmp/rule-deps.txt 2>&1
    grep -E '^ *\|?(Pre)?Depends: (libgl1|libgtk-4-1)$' /tmp/rule-deps.txt > /tmp/rule-hits.txt
    if [ -s /tmp/rule-hits.txt ]; then
        echo "  ОТКАЗ $id ($pkg $cand): в прямых зависимостях $(tr -s ' \n' ' ' < /tmp/rule-hits.txt)"
        bad=$((bad + 1))
        continue
    fi
    [ "$category" = "Игры" ] && games=$((games + 1))
    echo "  отбор пройден: $id ($pkg $cand)"
done < /tmp/rule-items.txt

echo "позиций в каталоге: $total, из них игр: $games"
[ "$total" -ge 12 ] || { echo "  ОТКАЗ: позиций меньше двенадцати"; bad=$((bad + 1)); }
[ "$bad" -eq 0 ] || exit 1
RULE

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

# ------------------------------------------------------- правило отбора каталога

log "2. Правило отбора каталога: без libgl1 и без libgtk-4-1 (R02.1, R02.3i)"
guest catalog-rule.sh \
    || die "каталог не проходит правило отбора — ставить его позиции незачем, живое окно у тяжёлой игры доказывало бы случайность"
ok "каждая позиция каталога прошла правило отбора, позиций не меньше двенадцати"

# ------------------------------------------------------------------ программа

log "3. Программа из магазина: установка и запуск (R02.1)"
params "$APP_ID"
if guest install.sh; then
    ok "программа $APP_ID поставлена магазином внутрь стола"
    ensure_shots
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

log "4. Игры из магазина на программной отрисовке (R02.2)"
# Список игр берётся из каталога, а не вписан сюда: каталог — данные, и новая
# строка в нём обязана тянуть за собой доказательство, а не тихо проехать мимо.
if [ -n "$GAME_ID" ]; then
    GAME_IDS="$GAME_ID"
else
    GAME_IDS="$(python3 -c "
import json
cat = json.load(open('$CATALOG_SRC', encoding='utf-8'))
print(' '.join(i['id'] for i in cat if i.get('category') == 'Игры'))
")"
fi
[ -n "$GAME_IDS" ] || bad "в каталоге нет ни одной игры — доказывать нечего"

for gid in $GAME_IDS; do
    printf '\n  игра %s\n' "$gid"
    GAME_ENV="$(python3 -c "
import json
cat = json.load(open('$CATALOG_SRC', encoding='utf-8'))
item = [i for i in cat if i['id'] == '$gid']
print(item[0].get('env', '') if item else '')
")"
    case "$GAME_ENV" in
        *LIBGL_ALWAYS_SOFTWARE*) ok "в каталоге у игры $gid стоит программная отрисовка: $GAME_ENV" ;;
        *) bad "у игры $gid в каталоге нет LIBGL_ALWAYS_SOFTWARE — аппаратного ускорения под proot нет" ;;
    esac

    params "$gid"
    if guest install.sh; then
        ok "игра $gid поставлена магазином внутрь стола"
        if guest launch.sh; then
            ok "окно игры $gid живо через $ALIVE_SECONDS секунд"
            fetch_shot "$gid"
        else
            bad "игра $gid не запустилась или окно не прожило $ALIVE_SECONDS секунд"
        fi
    else
        bad "магазин не поставил игру $gid"
    fi
done

# ------------------------------------------------------------------ обрыв

log "5. Обрыв установки не оставляет систему полуустановленной (R02.3)"
params "$APP_ID"
if guest interrupt.sh; then
    ok "после убитого на распаковке dpkg магазин вернул пакетный менеджер в строй"
else
    bad "после обрыва установки пакетный менеджер остался сломанным"
fi

# ------------------------------------------------- окно не выглядит зависшим

log "6. Установка не выглядит зависшей (R10i)"

# 6.1 Движение. Строки состояния сняты с настоящих установок этого прогона —
# теми же метками времени, что видел бы человек в окне. Спрашиваем со всех
# установок сразу: молчание дольше двух секунд хотя бы на одной — уже дефект.
mkdir -p "$ART/progress"
rm -f "$ART/progress"/*.tsv
NAMES="$(vm "cd '$ROOTFS/tmp' && ls progress-*.tsv 2>/dev/null" 2>/dev/null | tr -d '\r')"
for n in $NAMES; do
    limactl shell "$VM" -- sudo cat "$ROOTFS/tmp/$n" > "$ART/progress/$n" 2>/dev/null
done
if [ -n "$NAMES" ]; then
    if python3 - "$ART/progress" <<'CHECK'; then
import glob
import os
import re
import sys

files = sorted(glob.glob(os.path.join(sys.argv[1], "progress-*.tsv")))
if not files:
    print("  строк состояния нет вовсе")
    sys.exit(1)

bad_count = 0
best = None
for path in files:
    lines = [l.rstrip("\n") for l in open(path, encoding="utf-8") if l.strip()]
    name = os.path.basename(path)[len("progress-"):-len(".tsv")]
    if len(lines) < 2:
        print("  ПЛОХО %s: строк состояния всего %d" % (name, len(lines)))
        bad_count += 1
        continue
    stamps, texts = [], []
    for line in lines:
        stamp, _, text = line.partition("\t")
        stamps.append(float(stamp))
        texts.append(text)
    worst = max(b - a for a, b in zip(stamps, stamps[1:]))
    span = stamps[-1] - stamps[0]
    no_number = [t for t in texts if not re.search(r"\d", t)]
    shares = {m.group(1) for t in texts for m in [re.search(r"(\d+)%", t)] if m}
    print("  %-14s строк %2d, шла %4.1f с, худший разрыв %.2f с, разных долей %d"
          % (name, len(lines), span, worst, len(shares)))
    if worst >= 2.0:
        print("    ПЛОХО: строка состояния молчала %.2f с — больше двух секунд" % worst)
        bad_count += 1
    if no_number:
        print("    ПЛОХО: %d строк без единого числа, например: %s" % (len(no_number), no_number[0]))
        bad_count += 1
    if best is None or (len(shares), span) > best[0]:
        best = ((len(shares), span), name, texts)

if best is None:
    print("  ни одной пригодной записи о движении")
    sys.exit(1)
kinds, name, texts = best[0][0], best[1], best[2]
print("  самая говорливая установка: %s — %d разных долей" % (name, kinds))
print("    первая:     %s" % texts[0])
print("    последняя:  %s" % texts[-1])
if kinds < 3:
    print("    ПЛОХО: ни на одной установке доля не двигалась — число не из вывода apt")
    bad_count += 1
sys.exit(1 if bad_count else 0)
CHECK
        ok "на каждой установке строка состояния менялась чаще раза в 2 секунды и несла число (R10i.1)"
    else
        bad "движение установки не доказано (R10i.1)"
    fi
else
    bad "магазин не оставил ни одной строки состояния за установку (R10i.1)"
fi

# Настоящие установки в виртуалке коротки: пакеты рядом, сеть быстрая. Обещание
# же дано про долгую установку на устройстве, где apt молчит десятками секунд.
# Поэтому отдельно спрашиваем с самой подачи: подставной apt выдаёт три доли и
# замолкает на шесть секунд, а строка обязана продолжать меняться и не выдумать
# ни одной доли, которой apt не называл.
put silence.sh <<'SILENCE'
#!/bin/bash
cd /opt/stol/store || exit 2
python3 - <<'PY'
import sys
import time

sys.path.insert(0, "/opt/stol/store")
import backend

# Подставной apt: три настоящие доли, потом долгое молчание — ровно то, как
# ведёт себя настоящая установка на распаковке большого пакета.
STAND_IN = """
import os, sys, time
fd = int([a for a in sys.argv if a.startswith("APT::Status-Fd=")][0].split("=")[1])
w = os.fdopen(fd, "w")
for share in (0.0, 12.0, 40.0):
    w.write("pmstatus:geany:%s:Распаковывается geany\\n" % share)
    w.flush()
    time.sleep(0.5)
time.sleep(6)
"""

seen = []
code = backend._run_apt(
    ["python3", "-c", STAND_IN], "Установка geany",
    lambda text: seen.append((time.monotonic(), text)),
    open("/dev/null", "wb"),
)
if code != 0:
    print("подставной apt вернул %d" % code)
    sys.exit(3)

worst = max(b[0] - a[0] for a, b in zip(seen, seen[1:]))
shares = sorted({t.split("—")[1].split("%")[0].strip() for _, t in seen})
print("строк за 8 секунд молчания: %d, худший разрыв %.2f с, доли: %s"
      % (len(seen), worst, ", ".join(shares)))
if len(seen) < 8:
    print("строк слишком мало — при молчании apt окно замирает")
    sys.exit(4)
if worst >= 2.0:
    print("строка молчала %.2f с" % worst)
    sys.exit(5)
if not set(shares) <= {"0", "12", "40", "100"}:
    print("показаны доли, которых apt не называл: %s" % shares)
    sys.exit(6)
if any(not any(c.isdigit() for c in t) for _, t in seen):
    print("есть строка без единого числа")
    sys.exit(7)
PY
SILENCE
if guest silence.sh; then
    ok "при молчании apt строка всё равно меняется и не выдумывает долю (R10i.1)"
else
    bad "при молчании apt окно замирает или рисует несуществующее движение (R10i.1)"
fi

# 6.2 Блокировка кнопок. Окно поднимается настоящее, на том же столе :1; сама
# установка подменяется, потому что доказывается не apt, а поведение кнопок.
put lock.sh <<'LOCK'
#!/bin/bash
export DISPLAY=:1
cd /opt/stol/store || exit 2
python3 - <<'PY'
import importlib.util
import sys
import time
import tkinter as tk

sys.path.insert(0, "/opt/stol/store")
import backend

spec = importlib.util.spec_from_file_location("store_ui", "/opt/stol/store/codoceh-store.py")
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)

starts = []


def fake_install(package, on_progress=None, log_path=None):
    # Подменяется apt, а не окно: доказывается поведение кнопок, а не установка.
    starts.append(package)
    for i in range(1, 8):
        if on_progress:
            on_progress("Установка %s — %d%%" % (package, i * 13))
        time.sleep(0.3)
    return 0


backend.run_install = fake_install

root = tk.Tk()
app = ui.StoreApp(root)
outcome = {"code": 0}
seen = set()


def stop(code, message):
    outcome["code"] = code
    if message:
        print(message)
    root.quit()


def watch():
    seen.add(app.status_var.get())
    root.after(100, watch)


def step_click():
    btn = next((b for b in app.action_buttons if str(b.cget("text")) == "Поставить"), None)
    if btn is None:
        return stop(3, "в окне нет ни одной кнопки «Поставить» — блокировать нечего")
    if "disabled" in btn.state():
        return stop(3, "кнопка заблокирована ещё до всякой установки")
    btn.invoke()
    root.after(400, step_second, btn)


def step_second(btn):
    if "disabled" not in btn.state():
        return stop(4, "после нажатия кнопка осталась доступной")
    free = [b for b in app.action_buttons if "disabled" not in b.state()]
    if free:
        return stop(4, "во время установки доступны %d кнопок" % len(free))
    # Второй клик — и по самой кнопке, и мимо неё прямым вызовом: так поступила
    # бы клавиатура или повторно доставленное событие.
    btn.invoke()
    if app.install_pkg("mousepad") is not False:
        return stop(5, "второй вызов установки не был отклонён")
    root.after(3000, step_check)


def step_check():
    if len(starts) != 1:
        return stop(6, "установок запущено: %d, ожидалась одна" % len(starts))
    if len(seen) < 3:
        return stop(7, "строка состояния в окне приняла всего %d значений" % len(seen))
    print("установок запущено: 1, кнопок заблокировано: %d, разных строк состояния в окне: %d"
          % (len(app.action_buttons), len(seen)))
    stop(0, "")


root.after(100, watch)
root.after(300, step_click)
root.mainloop()
sys.exit(outcome["code"])
PY
LOCK
if guest lock.sh; then
    ok "во время установки кнопки заблокированы, второй apt-get не запускается (R10i.2)"
else
    bad "кнопки не блокируются — второй клик запускает второй apt-get (R10i.2)"
fi

# 6.3 Гейт безопасности. Пока имена берутся из своего каталога, подстановка
# ничем не грозит; поиск по apt превратит её в чужую команду, поэтому имя
# обязано отвергаться до всякого запуска.
put gate.sh <<'GATE'
#!/bin/bash
cd /opt/stol/store || exit 2
rm -f /tmp/stol-injection
python3 - <<'PY'
import os
import sys

sys.path.insert(0, "/opt/stol/store")
import backend

MARK = "/tmp/stol-injection"
BAD = "galculator; touch %s" % MARK

src = open("/opt/stol/store/backend.py", encoding="utf-8").read()
if "shell=True" in src:
    print("в backend.py остался shell=True")
    sys.exit(3)

checks = (
    ("install_argv", lambda: backend.install_argv(BAD)),
    ("install_command", lambda: backend.install_command(BAD)),
    ("remove_argv", lambda: backend.remove_argv(BAD)),
    ("remove_command", lambda: backend.remove_command(BAD)),
    ("is_installed", lambda: backend.is_installed(BAD)),
    ("run_install", lambda: backend.run_install(BAD)),
)
for name, call in checks:
    try:
        call()
    except backend.UnsafePackageName:
        continue
    print("%s принял имя пакета с точкой с запятой" % name)
    sys.exit(4)

if os.path.exists(MARK):
    print("подставленная команда всё-таки выполнилась: есть %s" % MARK)
    sys.exit(5)
print("отвергнуто вызовов с подставленной командой: %d, следа нет" % len(checks))
PY
rc=$?
[ $rc -eq 0 ] || exit $rc

# Тот же удар по командной строке магазина — тем путём, каким его зовёт проверка.
python3 backend.py install 'galculator; touch /tmp/stol-injection' >/tmp/gate-cli.log 2>&1
cli=$?
[ "$cli" -eq 3 ] || { echo "командная строка вернула $cli вместо отказа"; exit 6; }
[ ! -e /tmp/stol-injection ] || { echo "командная строка выполнила подставленное"; exit 7; }
echo "командная строка отказала: код $cli, следа нет"
GATE
if guest gate.sh; then
    ok "имя пакета с точкой с запятой отвергается, команда не выполняется (R10i.3)"
else
    bad "имя пакета с точкой с запятой проходит на путь установки (R10i.3)"
fi

grep -q 'shell=True' "$STORE_DIR/backend.py" \
    && bad "в store/backend.py остался shell=True на пути установки (R10i.3)" \
    || ok "в store/backend.py нет shell=True (R10i.3)"

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
