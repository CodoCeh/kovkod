#!/usr/bin/env bash
#
# tools/mkimage.sh — собирает чистый rootfs Debian 13 trixie (arm64) для КодоЦех Стол.
#
# Образ строится debootstrap-ом с нуля: домашний каталог владельца не читается и не
# копируется (R05.1). Внутрь кладутся XFCE, Xtigervnc (R15.1), Chromium, шрифты, обои и
# тема из reference/ (R07, R08) и семь ярлыков стола. Проприетарные программы (VS Code,
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

IMAGE_VERSION="2026.08.1"
MIN_ANDROID=28
SUITE="trixie"
ARCH="arm64"
MIRROR="${STOL_MIRROR:-http://deb.debian.org/debian}"
LIMA_VM="${STOL_LIMA_VM:-ceho}"
WORK="${STOL_WORK:-/var/tmp/codoceh-stol}"

# Полный состав образа. Recommends выключены осознанно: под proot нет systemd, и
# gvfs/udisks/policykit из рекомендаций всё равно не работают, а вес дают.
PACKAGES_FULL="
xfce4-session xfwm4 xfdesktop4 xfce4-panel xfce4-settings xfce4-appfinder
xfce4-terminal thunar exo-utils xfconf libxfconf-0-3
tigervnc-standalone-server tigervnc-common
chromium
dbus-x11 x11-xserver-utils x11-utils wmctrl
yaru-theme-gtk yaru-theme-icon adwaita-icon-theme hicolor-icon-theme librsvg2-common
fonts-dejavu-core fonts-liberation2 fonts-noto-core fonts-noto-color-emoji
procps psmisc ca-certificates
"

# Смоук: тот же путь сборки на минимуме пакетов. xfconf и dbus-x11 берутся не для веса —
# без них не проверить обратным чтением, что тема легла как в эталоне (R08.1).
PACKAGES_SMOKE="xfconf libxfconf-0-3 dbus-x11"

XFCONF_CHANNELS="xsettings xfwm4 xfce4-desktop xfce4-panel"

DESKTOP_ENTRIES="antigravity antigravity2 claude code gemini obsidian zcode"

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
/root/.config/xfce4/
/root/.config/xfce4/helpers.rc
/root/.config/xfce4/panel/
/root/.config/xfce4/panel/launcher-13/
/root/.config/xfce4/panel/launcher-13/17866595691.desktop
/root/.config/xfce4/panel/launcher-14/
/root/.config/xfce4/panel/launcher-14/17866595692.desktop
/root/.config/xfce4/panel/launcher-15/
/root/.config/xfce4/panel/launcher-15/17866595693.desktop
/root/.config/xfce4/panel/launcher-16/
/root/.config/xfce4/panel/launcher-16/17866595694.desktop
/root/.config/xfce4/xfconf/
/root/.config/xfce4/xfconf/xfce-perchannel-xml/
/root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-desktop.xml
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
/root/Desktop/gemini.desktop
/root/Desktop/obsidian.desktop
/root/Desktop/zcode.desktop
EOF
    if [ -f "$REPO/tools/assets/gemini.png" ]; then
        echo /root/.local/share/icons/gemini.png
    fi
}

# ---------------------------------------------------------------- запуск на macOS: уходим в lima

run_in_lima() {
    command -v limactl >/dev/null || die "нет limactl — на macOS образ собирается в виртуалке lima"
    limactl list --format '{{.Name}}' 2>/dev/null | grep -qx "$LIMA_VM" \
        || die "виртуалки lima '$LIMA_VM' нет: создайте arm64-машину или задайте STOL_LIMA_VM"

    log "виртуалка lima '$LIMA_VM'"
    if [ "$(limactl list --format '{{.Status}}' "$LIMA_VM")" != "Running" ]; then
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
    rm -f "$ROOTFS/usr/sbin/policy-rc.d"
    ok "поставлено пакетов: $(in_chroot dpkg-query -f '.\n' -W | wc -l), размер $(rootfs_size)"
}

step_brand() {
    log "3/9 фон, тема, ярлыки из reference/"

    install -d -m 0755 "$ROOTFS/usr/share/backgrounds/codoceh"
    install -m 0644 "$REPO/reference/wallpaper.png" \
        "$ROOTFS/usr/share/backgrounds/codoceh/wallpaper-codoceh.png"

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

    # Нижний док эталона: показать стол | терминал, файлы, браузер, поиск программ | /root.
    # Сами элементы док-лаунчеров в дампе не сняты (там только значения xfconf), состав
    # восстановлен по reference/screenshot.png — это штатный набор XFCE.
    panel_launcher 13 17866595691 "Terminal Emulator" "exo-open --launch TerminalEmulator" utilities-terminal
    panel_launcher 14 17866595692 "File Manager"      "exo-open --launch FileManager"      system-file-manager
    panel_launcher 15 17866595693 "Web Browser"       "exo-open --launch WebBrowser"       web-browser
    panel_launcher 16 17866595694 "Application Finder" "xfce4-appfinder"                   org.xfce.appfinder

    cat > "$ROOTFS/root/.config/xfce4/helpers.rc" <<'EOF'
TerminalEmulator=xfce4-terminal
FileManager=Thunar
WebBrowser=chromium
EOF
    chmod 0600 "$ROOTFS/root/.config/xfce4/helpers.rc"

    install -d -m 0700 "$ROOTFS/root/.local" "$ROOTFS/root/.local/share" \
        "$ROOTFS/root/.local/share/icons"
    if [ -f "$REPO/tools/assets/gemini.png" ]; then
        install -m 0644 "$REPO/tools/assets/gemini.png" \
            "$ROOTFS/root/.local/share/icons/gemini.png"
    else
        skip "tools/assets/gemini.png нет — ярлык Gemini останется без своей иконки"
    fi

    ok "обои, $XFCONF_CHANNELS, $(echo $DESKTOP_ENTRIES | wc -w) ярлыка, док"
}

panel_launcher() {
    local plugin="$1" item="$2" name="$3" exec="$4" icon="$5"
    local dir="$ROOTFS/root/.config/xfce4/panel/launcher-$plugin"
    install -d -m 0700 "$ROOTFS/root/.config/xfce4/panel" "$dir"
    cat > "$dir/$item.desktop" <<EOF
[Desktop Entry]
Version=1.0
Type=Application
Name=$name
Exec=$exec
Icon=$icon
Terminal=false
StartupNotify=false
Categories=X-XFCE;X-Xfce-Toplevel;
EOF
    chmod 0644 "$dir/$item.desktop"
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
    ok "hostname, hosts, resolv.conf, machine-id"
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
        in_chroot dbus-run-session -- xfconf-query -c "$ch" -l -v > "$tmp/$ch.raw" 2>"$tmp/$ch.err" \
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

    if [ "$MODE" = "smoke" ]; then
        skip "Xtigervnc и Chromium в смоук-наборе не ставятся"
        return 0
    fi
    [ -x "$ROOTFS/usr/bin/Xtigervnc" ] || die "нет /usr/bin/Xtigervnc — R15.1 не выполнено"
    ok "Xtigervnc в образе (R15.1)"
    [ -x "$ROOTFS/usr/bin/chromium" ] || die "нет /usr/bin/chromium — окну Gemini не на чем открыться"
    ok "Chromium в образе"
    [ -d "$ROOTFS/usr/share/themes/Yaru" ] || die "нет темы Yaru в /usr/share/themes"
    [ -d "$ROOTFS/usr/share/icons/Yaru" ] || die "нет иконок Yaru в /usr/share/icons"
    ok "тема и иконки Yaru на месте (R08.1)"
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
        root/Desktop/gemini.desktop \
        opt/stol
    do
        [ -e "$dir/$path" ] || die "в развёрнутом архиве нет $path"
    done

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
