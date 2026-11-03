#!/usr/bin/env bash
#
# tools/mkimage.sh — собирает чистый rootfs Debian 13 trixie (arm64) для КодоЦех Стол.
#
# Образ строится debootstrap-ом с нуля: домашний каталог владельца не читается и не
# копируется (R05.1). Внутрь кладутся XFCE, Xtigervnc (R15.1), Chromium, шрифты, обои и
# тема из reference/ (R07, R08), офис и ярлыки стола. Проприетарные программы (VS Code,
# Obsidian, Antigravity, Antigravity 2.0, Claude, ZCode) в образ не кладутся — их ставит
# мастер на телефоне.
#
# Рядом с архивом пишется manifest.txt — белый список путей в /root и /home. Список
# объявлен в этом скрипте, а собранное дерево с ним сверяется: любой лишний путь валит
# сборку. По этому же файлу работает tools/checkimage.sh (задача 04).
#
# Образ разворачивается под proot без root: ни systemd, ни рабочего dbus-демона там нет,
# поэтому внутри нет ни одного юнита — всё поднимает /opt/stol/start-gui.sh (задача 05).
#
# Использование:
#   bash tools/mkimage.sh            полная сборка   → build/image/
#   bash tools/mkimage.sh --smoke    короткий прогон → build/image-smoke/
#
# На macOS сборка сама уходит в arm64-Linux: виртуалку lima `ceho`. На arm64-Linux
# скрипт собирает на месте (нужен root).

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

IMAGE_VERSION="2026.11.3"
MIN_ANDROID=28
SUITE="trixie"
ARCH="arm64"
MIRROR="${STOL_MIRROR:-http://deb.debian.org/debian}"
LIMA_VM="${STOL_LIMA_VM:-ceho}"
WORK="${STOL_WORK:-/var/tmp/codoceh-stol}"

# Полный состав образа. Recommends выключены осознанно: под proot нет systemd, и
# gvfs/udisks/policykit из рекомендаций всё равно не работают, а вес дают.
# python3-tk — окно магазина: без него codoceh-store.py падает на import tkinter,
# ярлык со стола ничего не открывает, а установка программ остаётся только в коде.
#
# Офис — три программы из четырёх: Base и Math столу не заказаны, а Draw всё равно
# приезжает жёсткой зависимостью Impress (её не обойти) — но ярлыка не получает.
# libreoffice-gtk3 берём не для красоты: без плагина VCL офис рисуется древним
# бэкендом X11 и выпадает из темы стола. libreoffice-l10n-ru и locales — русский
# интерфейс: язык офис берёт из локали сессии, см. step_system.
#
# Набор значков yaru-theme-icon убран: 109 МБ развёрнутыми ради имён, которые
# adwaita-icon-theme-legacy отдаёт за 3 МБ (Adwaita наследует его — Inherits в
# index.theme). Тема GTK yaru-theme-gtk ушла следом. Стол одет в Fluent-Dark (вид
# Windows 11): её в Debian нет, она едет собранной из tools/artifacts/fluent/ — см. step_brand.
#
# xfce4-whiskermenu-plugin — кнопка «Пуск» с поиском (R03.2). Свои настройки плагин
# держит не в своём файле, а в канале xfce4-panel — поэтому и кнопки выключения
# гасятся тем же дампом, что и вся панель (R03.6).
#
# tigervnc-standalone-server и tigervnc-common сюда не входят: они ставятся из
# committed .deb в tools/artifacts/tigervnc/ (патч DIB/image в RFB Extended
# Clipboard, R08) — см. step_packages.
PACKAGES_FULL="
xfce4-session xfwm4 xfdesktop4 xfce4-panel xfce4-settings xfce4-appfinder
xfce4-terminal thunar exo-utils xfconf libxfconf-0-3
chromium
dbus-x11 x11-xserver-utils x11-utils wmctrl
xfce4-whiskermenu-plugin xfce4-docklike-plugin
adwaita-icon-theme adwaita-icon-theme-legacy hicolor-icon-theme
librsvg2-common
fonts-dejavu-core fonts-liberation2 fonts-noto-core fonts-noto-color-emoji
procps psmisc ca-certificates
python3-tk python3-gi gir1.2-gtk-3.0 gir1.2-wnck-3.0
novnc python3-websockify
pulseaudio pulseaudio-utils
libreoffice-writer libreoffice-calc libreoffice-impress libreoffice-gtk3
libreoffice-l10n-ru locales
"

# Смоук: тот же путь сборки на минимуме пакетов. xfconf и dbus-x11 берутся не для веса —
# без них не проверить обратным чтением, что тема легла как в эталоне (R08.1).
PACKAGES_SMOKE="xfconf libxfconf-0-3 dbus-x11"

XFCONF_CHANNELS="xsettings xfwm4 xfce4-desktop xfce4-panel xfce4-keyboard-shortcuts"

DESKTOP_ENTRIES="antigravity antigravity2 claude code obsidian zcode"

# Ярлыки офиса в reference/ не лежат: на эталонном столе офиса не было. Берём те,
# что кладёт сам Debian в /usr/share/applications, — Exec и значок тогда совпадают с
# установленной версией, а не с нашей догадкой о ней.
OFFICE_ENTRIES="libreoffice-writer libreoffice-calc libreoffice-impress"

# Проприетарное, чего в образе быть не должно (R12i.1) — грубая проверка на своей стороне,
# полноценную белосписочную делает tools/checkimage.sh.
FORBIDDEN_PATHS="
/usr/share/code
/opt/Antigravity IDE
/opt/Antigravity-arm64
/opt/obsidian-app
/opt/zcode-app
/usr/bin/claude-desktop
"

MODE="full"
LOCAL=0
OUT=""

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
skip() { printf '    --  %s\n' "$*"; }
die()  { printf '\033[31mmkimage: %s\033[0m\n' "$*" >&2; exit 1; }

usage() {
    awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --smoke) MODE="smoke" ;;
        --local) LOCAL=1 ;;
        --out)   OUT="$2"; shift ;;
        -h|--help) usage ;;
        *) die "неизвестный ключ: $1" ;;
    esac
    shift
done

if [ "$MODE" = "smoke" ]; then
    PACKAGES="$PACKAGES_SMOKE"
    ZSTD_LEVEL="${STOL_ZSTD_LEVEL:-3}"
    VERSION="$IMAGE_VERSION-smoke"
    HOST_OUT="$REPO/build/image-smoke"
else
    PACKAGES="$PACKAGES_FULL"
    ZSTD_LEVEL="${STOL_ZSTD_LEVEL:-19}"
    VERSION="$IMAGE_VERSION"
    HOST_OUT="$REPO/build/image"
fi

if [ -n "$OUT" ]; then HOST_OUT="$OUT"; fi

ARTIFACTS="rootfs.tar.zst rootfs.tar.zst.sha256 manifest.txt image.json etc-hosts.expected etc-resolv.conf.expected"

# ---------------------------------------------------------------- белый список /root, /home

manifest_lines() {
    cat <<'EOF'
/home/
/root/
/root/.bashrc
/root/.profile
/root/.config/
/root/.config/mimeapps.list
/root/.config/Thunar/
/root/.config/Thunar/uca.xml
/root/.config/xfce4/
/root/.config/xfce4/helpers.rc
/root/.config/xfce4/panel/
/root/.config/xfce4/panel/docklike-11.rc
/root/.config/xfce4/xfconf/
/root/.config/xfce4/xfconf/xfce-perchannel-xml/
/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml
/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-keyboard-shortcuts.xml
/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml
/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml
/root/.config/xfce4/xfconf/xfce-perchannel-xml/xsettings.xml
/root/.local/
/root/.local/share/
/root/.local/share/icons/
/root/Desktop/
/root/Desktop/antigravity.desktop
/root/Desktop/antigravity2.desktop
/root/Desktop/claude.desktop
/root/Desktop/code.desktop
/root/Desktop/codoceh-store.desktop
/root/Desktop/obsidian.desktop
/root/Desktop/zcode.desktop
EOF
    # В смоук-наборе офиса нет, значит нет и его ярлыков: манифест обязан описывать
    # то дерево, которое собирается, иначе белый список падёт на пустом месте.
    if [ "$MODE" = "full" ]; then
        local name
        for name in $OFFICE_ENTRIES; do echo "/root/Desktop/$name.desktop"; done
    fi
}

# ---------------------------------------------------------------- запуск на macOS: уходим в lima

run_in_lima() {
    command -v limactl >/dev/null || die "нет limactl — на macOS образ собирается в виртуалке lima"
    limactl list 2>/dev/null | grep -q "$LIMA_VM" \
        || die "виртуалки lima '$LIMA_VM' нет: создайте arm64-машину или задайте STOL_LIMA_VM"

    log "виртуалка lima '$LIMA_VM'"
    if ! limactl list 2>/dev/null | grep "$LIMA_VM" | grep -q "Running"; then
        limactl start "$LIMA_VM" >/dev/null
    fi
    [ "$(limactl shell "$LIMA_VM" -- uname -m)" = "aarch64" ] \
        || die "виртуалка '$LIMA_VM' не aarch64 — образ должен собираться на arm64"
    ok "поднята, arch aarch64"

    limactl shell "$LIMA_VM" -- test -d "$REPO" \
        || die "репозиторий $REPO не виден внутри виртуалки — проверьте mounts в lima.yaml"

    log "инструменты сборки в виртуалке"
    limactl shell "$LIMA_VM" -- sudo env DEBIAN_FRONTEND=noninteractive bash -c '
        need=""
        for c in debootstrap zstd python3; do command -v $c >/dev/null || need="$need $c"; done
        [ -n "$need" ] || exit 0
        apt-get update -qq && apt-get install -y -qq debootstrap zstd python3 >/dev/null
    ' || die "не удалось поставить debootstrap/zstd/python3 в виртуалке"
    ok "debootstrap, zstd, python3 на месте"

    local vm_out="$WORK/out" mode_flag=""
    if [ "$MODE" = "smoke" ]; then mode_flag="--smoke"; fi
    limactl shell "$LIMA_VM" -- sudo bash "$REPO/tools/mkimage.sh" --local \
        $mode_flag --out "$vm_out"

    log "забираем артефакты из виртуалки в $HOST_OUT"
    rm -rf "$HOST_OUT"
    mkdir -p "$HOST_OUT"
    local f
    for f in $ARTIFACTS; do
        limactl shell "$LIMA_VM" -- cat "$vm_out/$f" > "$HOST_OUT/$f"
    done

    local want got
    want="$(cut -d' ' -f1 < "$HOST_OUT/rootfs.tar.zst.sha256")"
    got="$(shasum -a 256 "$HOST_OUT/rootfs.tar.zst" | cut -d' ' -f1)"
    [ "$want" = "$got" ] || die "архив побился при переносе из виртуалки: $want != $got"
    ok "sha256 сошёлся после переноса"

    printf '\n\033[32mГОТОВО\033[0m  %s\n' "$HOST_OUT"
    ls -lh "$HOST_OUT"
}

# ---------------------------------------------------------------- сборка на arm64-Linux

ROOTFS=""
# Снимает всё, что смонтировано внутрь rootfs. Вызывается и перед `rm -rf` прошлой
# сборки: удалять сквозь оставшийся /proc — верный способ испортить прогон.
umount_pseudo() {
    [ -n "$ROOTFS" ] || return 0
    local mp
    while read -r mp; do
        umount -l "$mp" 2>/dev/null || true
    done < <(awk -v r="$ROOTFS/" '$2 == r || index($2, r) == 1 {print $2}' /proc/self/mounts | LC_ALL=C sort -r)
}

rootfs_size() { du -sh --exclude=proc --exclude=sys --exclude=dev "$ROOTFS" | cut -f1; }

in_chroot() {
    chroot "$ROOTFS" /usr/bin/env -i \
        HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin \
        DEBIAN_FRONTEND=noninteractive LC_ALL=C "$@"
}

# То же самое, но с юникодом в выводе. Под LC_ALL=C GLib заменяет всё нелатинское
# вопросительными знаками — русское значение ключа вернулось бы «????» и обратное
# чтение расходилось бы с эталоном на пустом месте.
in_chroot_utf8() {
    chroot "$ROOTFS" /usr/bin/env -i \
        HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin \
        DEBIAN_FRONTEND=noninteractive LC_ALL=C.UTF-8 "$@"
}

step_debootstrap() {
    log "1/9 debootstrap $SUITE/$ARCH"
    umount_pseudo
    rm -rf "$ROOTFS"
    mkdir -p "$ROOTFS" "$WORK/apt-cache"
    debootstrap --arch="$ARCH" --variant=minbase --cache-dir="$WORK/apt-cache" \
        "$SUITE" "$ROOTFS" "$MIRROR" >"$WORK/debootstrap.log" 2>&1 \
        || { tail -30 "$WORK/debootstrap.log"; die "debootstrap упал, полный лог: $WORK/debootstrap.log"; }
    ok "базовая система: $(rootfs_size)"
}

step_packages() {
    log "2/9 пакеты образа"
    cat > "$ROOTFS/etc/apt/sources.list" <<EOF
deb $MIRROR $SUITE main
deb $MIRROR $SUITE-updates main
deb http://deb.debian.org/debian-security $SUITE-security main
EOF
    printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > "$ROOTFS/etc/resolv.conf"
    printf '#!/bin/sh\nexit 101\n' > "$ROOTFS/usr/sbin/policy-rc.d"
    chmod 0755 "$ROOTFS/usr/sbin/policy-rc.d"
    printf 'APT::Install-Recommends "false";\nAPT::Install-Suggests "false";\n' \
        > "$ROOTFS/etc/apt/apt.conf.d/99codoceh-no-recommends"

    mount -t proc proc "$ROOTFS/proc"
    in_chroot apt-get update -qq >"$WORK/apt.log" 2>&1 \
        || { tail -30 "$WORK/apt.log"; die "apt-get update упал"; }
    # shellcheck disable=SC2086
    in_chroot apt-get install -y -qq --no-install-recommends $PACKAGES >>"$WORK/apt.log" 2>&1 \
        || { tail -40 "$WORK/apt.log"; die "установка пакетов упала, лог: $WORK/apt.log"; }

    if [ "$MODE" = "full" ]; then
        # tigervnc-standalone-server/tigervnc-common идут не из apt, а из committed
        # .deb с патчем DIB/image в RFB Extended Clipboard (R08.1). dpkg -i не
        # трогает зависимости — их всё равно тянем обычным apt внутри debootstrap
        # (R08.1), поэтому следом apt-get install -f на случай unmet deps (R08.3).
        install -d -m 0755 "$ROOTFS/tmp/tigervnc-deb"
        cp "$REPO"/tools/artifacts/tigervnc/*.deb "$ROOTFS/tmp/tigervnc-deb/"
        in_chroot /bin/bash -c 'dpkg -i /tmp/tigervnc-deb/*.deb' >>"$WORK/apt.log" 2>&1 || true
        in_chroot apt-get install -y -qq -f >>"$WORK/apt.log" 2>&1 \
            || { tail -40 "$WORK/apt.log"; die "dpkg -i tigervnc не срослось по зависимостям, лог: $WORK/apt.log"; }
        rm -rf "$ROOTFS/tmp/tigervnc-deb"
    fi

    rm -f "$ROOTFS/usr/sbin/policy-rc.d"
    ok "поставлено пакетов: $(in_chroot dpkg-query -f '.\n' -W | wc -l), размер $(rootfs_size)"
}

step_brand() {
    log "3/9 фон, тема, ярлыки из reference/"

    install -d -m 0755 "$ROOTFS/usr/share/backgrounds/codoceh"
    install -m 0644 "$REPO/reference/wallpaper.png" \
        "$ROOTFS/usr/share/backgrounds/codoceh/wallpaper-codoceh.png"

    tar -xJf "$REPO/tools/artifacts/fluent/fluent-theme.tar.xz" -C "$ROOTFS" \
        --no-same-owner --no-same-permissions
    in_chroot gtk-update-icon-cache -f -q /usr/share/icons/Fluent-dark >/dev/null 2>&1 || true
    in_chroot gtk-update-icon-cache -f -q /usr/share/icons/Fluent >/dev/null 2>&1 || true

    local xmldir="$ROOTFS/root/.config/xfce4/xfconf/xfce-perchannel-xml"
    install -d -m 0700 "$ROOTFS/root/.config" "$ROOTFS/root/.config/xfce4"
    install -d -m 0700 "$ROOTFS/root/.config/xfce4/xfconf" "$xmldir"
    local ch
    for ch in $XFCONF_CHANNELS; do
        python3 "$REPO/tools/xfconf-dump-to-xml.py" "$ch" \
            "$REPO/reference/xfconf/$ch.txt" "$xmldir/$ch.xml"
    done

    install -d -m 0700 "$ROOTFS/root/Desktop"
    local name
    for name in $DESKTOP_ENTRIES; do
        install -m 0755 "$REPO/reference/desktop-entries/$name.desktop" \
            "$ROOTFS/root/Desktop/$name.desktop"
    done
    # Ярлык магазина — не из reference/ (в эталоне стола его нет), а свой, из store/.
    install -m 0755 "$REPO/store/codoceh-store.desktop" \
        "$ROOTFS/root/Desktop/codoceh-store.desktop"

    if [ "$MODE" = "full" ]; then
        for name in $OFFICE_ENTRIES; do
            [ -f "$ROOTFS/usr/share/applications/$name.desktop" ] \
                || die "офис не положил $name.desktop — ярлык на стол брать неоткуда (R01.1)"
            install -m 0755 "$ROOTFS/usr/share/applications/$name.desktop" \
                "$ROOTFS/root/Desktop/$name.desktop"
        done
    fi

    install -d -m 0700 "$ROOTFS/root/.config/xfce4/panel"
    cat > "$ROOTFS/root/.config/xfce4/panel/docklike-11.rc" <<'EOF'
[user]
pinned=/usr/share/applications/xfce4-terminal.desktop;/usr/share/applications/thunar.desktop;/usr/share/applications/chromium.desktop;
showPreviews=false
keyComboActive=false
keyAloneActive=false
EOF
    chmod 0644 "$ROOTFS/root/.config/xfce4/panel/docklike-11.rc"

    # Значок кнопки «Пуск» — наковальня КодоЦеха из ресурсов приложения, а не картинка
    # из чужой темы (R12i.2). Путь к нему записан в дампе канала xfce4-panel.
    install -d -m 0755 "$ROOTFS/usr/share/pixmaps" "$ROOTFS/usr/share/icons/hicolor/48x48/apps"
    install -m 0644 "$REPO/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" \
        "$ROOTFS/usr/share/pixmaps/codoceh-start.png"
    if [ -d "$REPO/reference/icons" ]; then
        for icon in "$REPO/reference/icons"/*.png; do
            [ -f "$icon" ] || continue
            install -m 0644 "$icon" "$ROOTFS/usr/share/pixmaps/"
            install -m 0644 "$icon" "$ROOTFS/usr/share/icons/hicolor/48x48/apps/"
        done
    fi

    cat > "$ROOTFS/root/.config/xfce4/helpers.rc" <<'EOF'
TerminalEmulator=xfce4-terminal
FileManager=Thunar
WebBrowser=chromium
EOF
    chmod 0600 "$ROOTFS/root/.config/xfce4/helpers.rc"

    install -d -m 0755 "$ROOTFS/usr/local/bin"
    cat > "$ROOTFS/usr/local/bin/chromium" <<'EOF'
#!/bin/sh
exec /usr/bin/chromium --no-sandbox --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage "$@"
EOF
    chmod 0755 "$ROOTFS/usr/local/bin/chromium"

    for b in x-www-browser gnome-www-browser sensible-browser; do
        cat > "$ROOTFS/usr/local/bin/$b" <<'EOF'
#!/bin/sh
exec /usr/local/bin/chromium "$@"
EOF
        chmod 0755 "$ROOTFS/usr/local/bin/$b"
    done

    cat > "$ROOTFS/root/.config/mimeapps.list" <<'EOF'
[Default Applications]
text/html=chromium.desktop
x-scheme-handler/http=chromium.desktop
x-scheme-handler/https=chromium.desktop
x-scheme-handler/about=chromium.desktop
x-scheme-handler/unknown=chromium.desktop
application/x-ms-dos-executable=wine.desktop
application/x-msdos-program=wine.desktop
application/x-dosexec=wine.desktop
application/x-msi=wine.desktop
application/x-msi-installer=wine.desktop
application/x-bat=wine.desktop
application/x-msdos-batch=wine.desktop
text/x-msdos-batch=wine.desktop

[Added Associations]
text/html=chromium.desktop;
x-scheme-handler/http=chromium.desktop;
x-scheme-handler/https=chromium.desktop;
application/x-ms-dos-executable=wine.desktop;
application/x-msdos-program=wine.desktop;
application/x-dosexec=wine.desktop;
application/x-msi=wine.desktop;
application/x-msi-installer=wine.desktop;
application/x-bat=wine.desktop;
application/x-msdos-batch=wine.desktop;
text/x-msdos-batch=wine.desktop;
EOF
    chmod 0644 "$ROOTFS/root/.config/mimeapps.list"

    install -d -m 0700 "$ROOTFS/root/.local" "$ROOTFS/root/.local/share" \
        "$ROOTFS/root/.local/share/icons"

    ok "обои, $XFCONF_CHANNELS, $(ls "$ROOTFS/root/Desktop" | wc -l | tr -d ' ') ярлыков, док"
}

step_scripts() {
    log "4/9 скрипты стола в /opt/stol"
    install -d -m 0755 "$ROOTFS/opt/stol"
    local copied=0 f
    for f in "$REPO"/image/*.sh; do
        [ -f "$f" ] || continue
        install -m 0755 "$f" "$ROOTFS/opt/stol/$(basename "$f")"
        copied=$((copied + 1))
    done
    if [ "$copied" -eq 0 ]; then
        skip "в image/ нет скриптов (задача 05 ещё не сдана) — /opt/stol создан пустым"
    else
        ok "перенесено скриптов: $copied"
    fi

    install -m 0755 "$REPO/image/parametry.py" "$ROOTFS/opt/stol/parametry.py"
    install -m 0755 "$REPO/image/snap.py" "$ROOTFS/opt/stol/snap.py"
    install -m 0755 "$REPO/image/window-memory.py" "$ROOTFS/opt/stol/window-memory.py"
    install -d -m 0755 "$ROOTFS/etc/xdg/menus/applications-merged" "$ROOTFS/usr/share/desktop-directories"
    install -m 0644 "$REPO/image/menu/kovkod-android.menu" "$ROOTFS/etc/xdg/menus/applications-merged/kovkod-android.menu"
    install -m 0644 "$REPO/image/menu/kovkod-android.directory" "$ROOTFS/usr/share/desktop-directories/kovkod-android.directory"
    printf '%s\n' "$VERSION" > "$ROOTFS/opt/stol/VERSION"
    install -d -m 0755 "$ROOTFS/usr/share/applications"
    cat > "$ROOTFS/usr/share/applications/kovkod-parametry.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Name=Параметры
Comment=Экран, оформление, приложения, время и язык
Exec=python3 /opt/stol/parametry.py
Icon=preferences-system
Terminal=false
StartupNotify=true
StartupWMClass=kovkod-parametry
Categories=Settings;
EOF
    in_chroot python3 -c 'import gi; gi.require_version("Gtk", "3.0"); from gi.repository import Gtk' \
        || die "в образе нет GTK для питона — «Параметрам» не на чем открыться"
    ok "Параметры в /opt/stol/parametry.py, версия $VERSION"

    # Магазин едет в образе рядом со скриптами: ярлык на столе зовёт его по
    # /opt/stol/store/codoceh-store.py, и без этих файлов нажимать нечего.
    install -d -m 0755 "$ROOTFS/opt/stol/store"
    install -m 0755 "$REPO/store/codoceh-store.py" "$ROOTFS/opt/stol/store/codoceh-store.py"
    install -m 0644 "$REPO/store/backend.py"       "$ROOTFS/opt/stol/store/backend.py"
    install -m 0644 "$REPO/store/catalog.json"     "$ROOTFS/opt/stol/store/catalog.json"
    ok "магазин в /opt/stol/store: $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$REPO/store/catalog.json") программ в каталоге"

    # Своя unix-часть wine (650 КБ) — единственное, что от стека windows-программ
    # едет в образе. Готовый Hangover на Android не запускается: домен untrusted_app
    # запрещает execmod, а wine раскладывает PE-образ именно так. Наша сборка читает
    # PE в безымянную память (tools/hangover/build-ntdll.sh); без неё магазин
    # откажется ставить стек, а не нарисует мёртвый ярлык.
    if [ -f "$REPO/tools/hangover/ntdll.so" ]; then
        install -m 0644 "$REPO/tools/hangover/ntdll.so" \
            "$ROOTFS/opt/stol/store/hangover-ntdll.so"
        ok "своя unix-часть wine: $(numfmt --to=iec "$(stat -c %s "$REPO/tools/hangover/ntdll.so")")"
    else
        die "нет tools/hangover/ntdll.so — стек windows-программ без него не встанет"
    fi

    # GPU-стек (zink -> turnip -> Adreno): своя сборка Mesa 26 под glibc,
    # см. tools/gpu/README.md. На устройстве без Adreno или без доступа к
    # /dev/kgsl-3d0 драйвер не заведётся — это не ломает стол: без обёртки
    # gl-run программы рисуют софтом, как и раньше.
    if [ -f "$REPO/tools/gpu/libvulkan_freedreno.so" ]; then
        install -d -m 0755 "$ROOTFS/opt/stol/gpu/gl/dri"
        install -m 0644 "$REPO/tools/gpu/libvulkan_freedreno.so" "$ROOTFS/opt/stol/gpu/"
        install -m 0644 "$REPO/tools/gpu/freedreno_icd.json" "$ROOTFS/opt/stol/gpu/"
        install -m 0644 "$REPO/tools/gpu/libGL.so.1.2.0" "$ROOTFS/opt/stol/gpu/gl/"
        ln -sf libGL.so.1.2.0 "$ROOTFS/opt/stol/gpu/gl/libGL.so.1"
        install -m 0644 "$REPO/tools/gpu/libgallium-26.3.0-devel.so" "$ROOTFS/opt/stol/gpu/gl/"
        install -m 0644 "$REPO/tools/gpu/dri/swrast_dri.so" "$ROOTFS/opt/stol/gpu/gl/dri/"
        install -m 0755 "$REPO/tools/gpu/gl-run" "$ROOTFS/opt/stol/gpu/gl-run"
        ok "GPU-стек: $(du -sh "$REPO/tools/gpu" | cut -f1) в /opt/stol/gpu"
    else
        skip "tools/gpu пуст — образ без GPU-ускорения (софтовый рендер)"
    fi

    # Thunar: контекстные действия — Wine и GPU
    install -d -m 0755 "$ROOTFS/root/.config/Thunar"
    install -m 0644 "$REPO/tools/thunar/uca.xml" "$ROOTFS/root/.config/Thunar/uca.xml"
    ok "Thunar: действия Wine/GPU"

    # Windows-обёртка для двойного клика по .exe (B): ставить стек по требованию
    # и запускать wine. Сам стек в образ не входит — весит >2 ГБ; в образ едет
    # только обёртка и ярлык-ассоциация, а установка идёт при первом запуске .exe.
    install -d -m 0755 "$ROOTFS/opt/stol/windows"
    install -m 0755 "$REPO/tools/windows/run-exe.sh" "$ROOTFS/opt/stol/windows/run-exe.sh"
    install -d -m 0755 "$ROOTFS/usr/share/applications"
    install -m 0644 "$REPO/tools/windows/wine.desktop" "$ROOTFS/usr/share/applications/wine.desktop"
    install -m 0755 "$REPO/tools/windows/backup-prefix.sh" "$ROOTFS/opt/stol/windows/backup-prefix.sh"
    ok "Windows-обёртка: двойной клик по .exe ставит стек и запускает wine"

    install -d -m 0755 "$ROOTFS/opt/stol/telemetry"
    install -m 0755 "$REPO/tools/telemetry/collect.sh" "$ROOTFS/opt/stol/telemetry/collect.sh"
    ok "Телеметрия: /opt/stol/telemetry/collect.sh"
}

step_system() {
    log "5/9 системные файлы образа"
    printf 'codoceh-stol\n' > "$ROOTFS/etc/hostname"
    cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1	localhost codoceh-stol
::1	localhost ip6-localhost ip6-loopback
EOF
    printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > "$ROOTFS/etc/resolv.conf"
    # Свой id нужен dbus-у; в образе он одинаковый у всех, поэтому start-gui.sh
    # перевыписывает его на первом запуске (шов с задачей 05).
    in_chroot dbus-uuidgen > "$ROOTFS/etc/machine-id" 2>/dev/null || printf '\n' > "$ROOTFS/etc/machine-id"

    # Игры Debian кладёт в /usr/games, а /etc/profile для root этот каталог из PATH
    # выбрасывает — ярлык игры из меню свою программу не находил (R06D.2).
    #
    # Дописываем в сам /etc/profile, а не в /etc/profile.d, хотя штатное место —
    # именно profile.d. Причина проверена вживую: под proot проверка «файл читается»
    # врёт, а Debian обходит profile.d ровно через неё:
    #     for i in $(run-parts --list ... /etc/profile.d); do [ -r $i ] && . $i; done
    # `[ -r /etc/profile.d/что-угодно ]` под proot ложно даже для root, и файл
    # молча не читается. Сам /etc/profile оболочка открывает без этой проверки,
    # поэтому строка здесь доезжает и до `bash -l`, и до `sh -lc` — так входит APK.
    cat >> "$ROOTFS/etc/profile" <<'EOF'

# КодоЦех Стол: /usr/games в PATH — иначе ярлык игры не находит свою программу.
case ":$PATH:" in
    *:/usr/games:*) ;;
    *) PATH="$PATH:/usr/local/games:/usr/games"; export PATH ;;
esac
EOF

    # Русский интерфейс офиса (R01.2). Установленного пакета языка мало: язык офис
    # берёт из локали сессии, а в образе не сгенерировано ни одной, кроме C. Без
    # этого Writer молча открывается по-английски, и русский пакет лежит мёртвым.
    if [ "$MODE" = "full" ]; then
        printf 'ru_RU.UTF-8 UTF-8\n' > "$ROOTFS/etc/locale.gen"
        in_chroot locale-gen >/dev/null 2>&1 || die "locale-gen не собрал ru_RU.UTF-8 (R01.2)"
        # Снова в сам /etc/profile, а не в /etc/default/locale: тот читает PAM при
        # входе, а в образ входят через `sh -lc` — PAM в этом продукте не участвует.
        cat >> "$ROOTFS/etc/profile" <<'EOF'

# КодоЦех Стол: язык стола и офиса.
LANG=ru_RU.UTF-8
export LANG
EOF
        ok "hostname, hosts, resolv.conf, machine-id, /usr/games в PATH, локаль ru_RU.UTF-8"
    else
        ok "hostname, hosts, resolv.conf, machine-id, /usr/games в PATH"
    fi
}

step_verify_theme() {
    log "6/9 сверка темы с эталоном (R07.1, R08.1)"

    local want got
    want="$(cut -d' ' -f1 < "$REPO/reference/wallpaper.sha256")"
    got="$(sha256sum "$ROOTFS/usr/share/backgrounds/codoceh/wallpaper-codoceh.png" | cut -d' ' -f1)"
    [ "$want" = "$got" ] || die "обои в образе не совпали с эталоном: $got != $want (R07.1)"
    ok "обои совпали побайтово: $want"

    if [ ! -x "$ROOTFS/usr/bin/xfconf-query" ]; then
        skip "xfconf-query нет в образе — обратное чтение каналов пропущено"
        return 0
    fi

    local tmp="$WORK/xfconf-readback"
    rm -rf "$tmp"; mkdir -p "$tmp"
    mountpoint -q "$ROOTFS/proc" || mount -t proc proc "$ROOTFS/proc"
    local ch
    for ch in $XFCONF_CHANNELS; do
        in_chroot_utf8 dbus-run-session -- xfconf-query -c "$ch" -l -v > "$tmp/$ch.raw" 2>"$tmp/$ch.err" \
            || { cat "$tmp/$ch.err"; die "xfconf-query по каналу $ch не отработал в образе"; }
        normalize_dump < "$tmp/$ch.raw" > "$tmp/$ch.got"
        normalize_dump < "$REPO/reference/xfconf/$ch.txt" > "$tmp/$ch.want"
        diff -u "$tmp/$ch.want" "$tmp/$ch.got" > "$tmp/$ch.diff" \
            || { cat "$tmp/$ch.diff"; die "канал $ch разошёлся с эталоном (R08.1)"; }
        ok "канал $ch: $(wc -l < "$tmp/$ch.want") ключей сошлись"
    done

    # xfconfd мог тронуть XML при чтении — возвращаем ровно то, что сгенерировано из дампа.
    local xmldir="$ROOTFS/root/.config/xfce4/xfconf/xfce-perchannel-xml"
    for ch in $XFCONF_CHANNELS; do
        python3 "$REPO/tools/xfconf-dump-to-xml.py" "$ch" \
            "$REPO/reference/xfconf/$ch.txt" "$xmldir/$ch.xml" >/dev/null
    done
}

normalize_dump() {
    sed -E 's/[[:space:]]{2,}/ /g; s/[[:space:]]+$//' | grep -v '^$' | LC_ALL=C sort
}

step_content_checks() {
    log "7/9 состав образа"

    local path
    while read -r path; do
        [ -n "$path" ] || continue
        if [ -e "$ROOTFS$path" ]; then die "в образе нашлось проприетарное: $path (R12i.1)"; fi
    done <<EOF
$FORBIDDEN_PATHS
EOF
    ok "проприетарных программ в образе нет (R12i.1)"

    # PATH сессии: без /usr/games ярлык игры из меню не находит свою программу.
    grep -q '/usr/local/games:/usr/games' "$ROOTFS/etc/profile" \
        || die "в /etc/profile нет строки про /usr/games — R06D.2"
    local path_seen
    path_seen="$(in_chroot /bin/bash -lc 'printf %s "$PATH"' 2>/dev/null || true)"
    case ":$path_seen:" in
        *:/usr/games:*) ok "/usr/games в PATH сессии: $path_seen (R06D.2)" ;;
        *) die "/usr/games не попал в PATH входа в образ, получилось: $path_seen (R06D.2)" ;;
    esac

    if [ "$MODE" = "smoke" ]; then
        skip "Xtigervnc и Chromium в смоук-наборе не ставятся"
        return 0
    fi
    [ -x "$ROOTFS/usr/bin/Xtigervnc" ] || die "нет /usr/bin/Xtigervnc — R15.1 не выполнено"
    ok "Xtigervnc в образе (R15.1)"
    # Наличие бинарника не доказывает, что это патченная сборка, а не апстримный
    # apt-пакет, тихо подменивший .deb (R08.2).
    local tigervnc_version
    tigervnc_version="$(in_chroot dpkg-query -W -f '${Version}' tigervnc-standalone-server 2>/dev/null || true)"
    case "$tigervnc_version" in
        *+codoceh2*) ok "tigervnc-standalone-server версии $tigervnc_version (R08.2)" ;;
        *) die "tigervnc-standalone-server версии '$tigervnc_version' без +codoceh2 — патч DIB/image не попал в образ (R08.2)" ;;
    esac
    [ -x "$ROOTFS/usr/bin/chromium" ] || die "нет /usr/bin/chromium — браузеру не на чем открыться"
    ok "Chromium в образе"

    # Окно магазина. Наличие файла ничего не доказывает — спрашиваем сам питон образа,
    # собирается ли у него окно tkinter (R06D.1).
    [ -f "$ROOTFS/opt/stol/store/codoceh-store.py" ] || die "магазина нет в /opt/stol/store"
    [ -f "$ROOTFS/root/Desktop/codoceh-store.desktop" ] || die "нет ярлыка магазина на столе"
    in_chroot python3 -c 'import tkinter; tkinter.Tk' >/dev/null 2>&1 \
        || die "python3-tk не работает в образе — окно магазина не откроется (R06D.1)"
    ok "магазин, ярлык и рабочий tkinter в образе (R06D.1)"
    [ -f "$ROOTFS/usr/share/themes/Fluent-Dark/gtk-3.0/gtk.css" ] \
        || die "нет темы Fluent-Dark с gtk.css в /usr/share/themes (R03.10)"
    [ -d "$ROOTFS/usr/share/themes/Fluent-Dark/xfwm4" ] \
        || die "у темы Fluent-Dark нет части xfwm4 — рамки окон останутся чужими (R03.10)"
    [ -f "$ROOTFS/usr/share/icons/Fluent-dark/index.theme" ] \
        || die "нет набора значков Fluent-dark (R03.10)"
    if [ -d "$ROOTFS/usr/share/icons/Yaru" ]; then
        die "набор значков Yaru вернулся в образ — это 109 МБ веса (R11i.2)"
    fi
    [ -d "$ROOTFS/usr/share/icons/AdwaitaLegacy" ] \
        || die "нет AdwaitaLegacy — значкам панели и магазина взяться неоткуда (R11i.2)"
    ok "тема Fluent-Dark с частью xfwm4, значки Fluent-dark и Adwaita (R03.10, R11i.2)"

    # Кнопка «Пуск»: сам плагин, его значок и шрифт, которым подписан стол (R03.2, R03.11).
    ls "$ROOTFS"/usr/lib/*/xfce4/panel/plugins/libwhiskermenu.so >/dev/null 2>&1 \
        || die "нет libwhiskermenu.so — кнопке «Пуск» взяться неоткуда (R03.2)"
    ls "$ROOTFS"/usr/lib/*/xfce4/panel/plugins/libdocklike.so >/dev/null 2>&1 \
        || die "нет libdocklike.so — панели задач взяться неоткуда (R03.2)"
    [ -f "$ROOTFS/usr/share/pixmaps/codoceh-start.png" ] \
        || die "нет значка кнопки «Пуск» — она нарисовалась бы чужой картинкой (R12i.2)"
    ls "$ROOTFS"/usr/share/fonts/truetype/noto/NotoSans*.ttf >/dev/null 2>&1 \
        || die "в образе нет Noto Sans — шрифт интерфейса взять неоткуда (R03.11)"
    ok "плагин «Пуск», его значок и Noto Sans в образе (R03.2, R03.11, R12i.2)"

    # Офис. Проверяем не пакет, а то, что открывается: программу и ярлыки на столе.
    [ -x "$ROOTFS/usr/bin/soffice" ] || die "нет /usr/bin/soffice — офис не поставился (R01.4)"
    local name
    for name in $OFFICE_ENTRIES; do
        [ -f "$ROOTFS/root/Desktop/$name.desktop" ] || die "нет ярлыка $name на столе (R01.1)"
    done
    [ -f "$ROOTFS/usr/lib/libreoffice/program/resource/ru/LC_MESSAGES/sfx.mo" ] \
        || die "русских строк офиса в образе нет — интерфейс будет английским (R01.2)"
    local lang_seen
    lang_seen="$(in_chroot /bin/bash -lc 'printf %s "$LANG"' 2>/dev/null || true)"
    [ "$lang_seen" = "ru_RU.UTF-8" ] \
        || die "LANG на входе в образ = '$lang_seen', офис откроется по-английски (R01.2)"
    in_chroot locale -a > "$WORK/locales.txt" 2>/dev/null || true
    grep -qix 'ru_RU.utf8' "$WORK/locales.txt" \
        || die "локаль ru_RU.UTF-8 не сгенерирована — LANG сорвётся в C (R01.2)"
    ok "офис, три ярлыка, русские строки и локаль ru_RU.UTF-8 (R01.1, R01.2, R01.4)"
    [ -f "$ROOTFS/usr/share/xfce4/helpers/chromium.desktop" ] \
        || skip "exo не знает помощника chromium — кнопка браузера в доке спросит программу"
}

step_clean() {
    log "8/9 чистка образа (R05.2, R06.1)"
    umount_pseudo
    in_chroot apt-get clean >/dev/null 2>&1 || true
    rm -rf "$ROOTFS"/var/lib/apt/lists/* "$ROOTFS"/var/cache/apt/archives/*.deb \
           "$ROOTFS"/var/cache/apt/*.bin "$ROOTFS"/var/cache/debconf/*-old \
           "$ROOTFS"/var/lib/dpkg/*-old "$ROOTFS"/var/cache/ldconfig/aux-cache
    rm -rf "$ROOTFS"/root/.cache "$ROOTFS"/root/.dbus "$ROOTFS"/root/.gnupg \
           "$ROOTFS"/root/.bash_history "$ROOTFS"/root/.wget-hsts "$ROOTFS"/root/.Xauthority
    rm -rf "$ROOTFS"/tmp/* "$ROOTFS"/var/tmp/*
    find "$ROOTFS/var/log" -type f -exec sh -c ': > "$1"' _ {} \;
    # Мусор чужой файловой системы: ._* приезжает, если дерево хоть раз проехало
    # через macOS. Пакетный менеджер потом спотыкается о них в своих каталогах.
    find "$ROOTFS" -xdev \( -name '._*' -o -name '.DS_Store' \) -delete

    if mountpoint -q "$ROOTFS/proc"; then die "внутренний /proc всё ещё смонтирован — чистить опасно"; fi
    if mountpoint -q "$ROOTFS/dev";  then die "внутренний /dev всё ещё смонтирован — чистить опасно"; fi
    find "$ROOTFS/dev" -mindepth 1 -delete
    ok "кэши, журналы и /dev пусты"
}

step_manifest() {
    log "9/9 манифест и упаковка"
    mkdir -p "$OUT"

    {
        echo "# Белый список путей образа КодоЦех Стол в /root и /home."
        echo "# Строки — абсолютные пути внутри образа; каталог оканчивается на '/'."
        echo "# Строки с '#' и пустые игнорируются. Всё, чего здесь нет, — лишнее (R05.2)."
        echo "# Собрано tools/mkimage.sh, версия образа $VERSION."
        manifest_lines | LC_ALL=C sort
    } > "$OUT/manifest.txt"

    local actual="$WORK/actual-paths.txt" expected="$WORK/expected-paths.txt"
    ( cd "$ROOTFS" && find root home \( -type d -printf '/%p/\n' \) -o -printf '/%p\n' ) \
        | LC_ALL=C sort > "$actual"
    manifest_lines | LC_ALL=C sort > "$expected"
    if ! diff -u "$expected" "$actual" > "$WORK/manifest.diff"; then
        cat "$WORK/manifest.diff"
        die "дерево /root и /home разошлось с манифестом (R05.2)"
    fi
    ok "манифест сошёлся с деревом: $(wc -l < "$expected") путей"

    cp "$ROOTFS/etc/hosts" "$OUT/etc-hosts.expected"
    cp "$ROOTFS/etc/resolv.conf" "$OUT/etc-resolv.conf.expected"

    local unpacked
    unpacked="$(du -sb "$ROOTFS" | cut -f1)"
    tar --numeric-owner --sort=name -C "$ROOTFS" -cf - . \
        | zstd -q -f -T0 "-$ZSTD_LEVEL" -o "$OUT/rootfs.tar.zst"
    ( cd "$OUT" && sha256sum rootfs.tar.zst > rootfs.tar.zst.sha256 )

    local bytes sha
    bytes="$(stat -c %s "$OUT/rootfs.tar.zst")"
    sha="$(cut -d' ' -f1 < "$OUT/rootfs.tar.zst.sha256")"
    cat > "$OUT/image.json" <<EOF
{
  "version": "$VERSION",
  "asset": "rootfs.tar.zst",
  "sha256": "$sha",
  "bytes": $bytes,
  "unpacked_bytes": $unpacked,
  "min_android": $MIN_ANDROID
}
EOF
    ok "архив $(numfmt --to=iec "$bytes"), в развёрнутом виде $(numfmt --to=iec "$unpacked")"
}

step_unpack_test() {
    log "проверка: архив разворачивается и совпадает с манифестом"
    local dir="$WORK/unpacked"
    rm -rf "$dir"; mkdir -p "$dir"
    zstd -dc "$OUT/rootfs.tar.zst" | tar -C "$dir" -xf - --numeric-owner

    local path
    for path in \
        usr/share/backgrounds/codoceh/wallpaper-codoceh.png \
        root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml \
        root/Desktop/codoceh-store.desktop \
        opt/stol/store/codoceh-store.py
    do
        [ -e "$dir/$path" ] || die "в развёрнутом архиве нет $path"
    done
    grep -q '/usr/local/games:/usr/games' "$dir/etc/profile" \
        || die "в развёрнутом архиве /etc/profile без /usr/games (R06D.2)"

    # Мусор чужой файловой системы проверяем на самом артефакте, а не на дереве
    # сборки: испортить архив может и перепаковка на чужой машине (R06D.3).
    local litter
    litter="$(find "$dir" -xdev \( -name '._*' -o -name '.DS_Store' \) | head -20)"
    if [ -n "$litter" ]; then
        printf '%s\n' "$litter"
        die "в архиве мусор чужой файловой системы (._* / .DS_Store) — R06D.3"
    fi
    ok "мусора чужой файловой системы в архиве нет (R06D.3)"

    local actual="$WORK/unpacked-paths.txt"
    ( cd "$dir" && find root home \( -type d -printf '/%p/\n' \) -o -printf '/%p\n' ) \
        | LC_ALL=C sort > "$actual"
    diff -u "$WORK/expected-paths.txt" "$actual" \
        || die "развёрнутый архив разошёлся с манифестом"
    ok "распаковано $(du -sh "$dir" | cut -f1), манифест сошёлся"
    rm -rf "$dir"
}

build_local() {
    [ "$(id -u)" = "0" ] || die "локальная сборка требует root (debootstrap создаёт узлы устройств)"
    [ "$(uname -s)" = "Linux" ] || die "локальная сборка возможна только на Linux"
    [ "$(uname -m)" = "aarch64" ] || die "образ arm64 собирается только на arm64-машине, здесь $(uname -m)"
    local c
    for c in debootstrap tar zstd python3 chroot numfmt; do
        command -v "$c" >/dev/null || die "нет команды $c"
    done
    [ -n "$OUT" ] || OUT="$WORK/out"

    ROOTFS="$WORK/rootfs"
    trap umount_pseudo EXIT

    log "режим: $MODE, вывод: $OUT"
    step_debootstrap
    step_packages
    step_brand
    step_scripts
    step_system
    step_verify_theme
    step_content_checks
    step_clean
    step_manifest
    step_unpack_test

    chmod -R a+rX "$OUT"
    printf '\n\033[32mГОТОВО\033[0m  образ собран: %s\n' "$OUT"
}

if [ "$LOCAL" = "1" ]; then
    build_local
elif [ "$(uname -s)" = "Linux" ] && [ "$(uname -m)" = "aarch64" ]; then
    OUT="${OUT:-$HOST_OUT}"
    build_local
else
    run_in_lima
fi
