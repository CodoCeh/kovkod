#!/bin/sh
[ -f /usr/bin/chromium ] || exit 0

STORE="Магазин КодоЦех"
export DISPLAY="${DISPLAY:-:1}" HOME="${HOME:-/root}" DEBIAN_FRONTEND=noninteractive

exec 9>/tmp/kovkod-remove-chromium.lock
if ! flock -n 9; then
    wmctrl -a "$STORE" 2>/dev/null
    exit 0
fi

if [ "${1:-}" = auto ]; then
    sleep 20
    python3 -c 'import socket; socket.create_connection(("deb.debian.org", 443), 8)' 2>/dev/null || [ -x /usr/bin/firefox-esr ] || exit 0
fi

mkdir -p /etc/firefox/policies
printf '%s\n' '{"policies": {"DisableTelemetry": true, "DontCheckDefaultBrowser": true, "OverrideFirstRunPage": "", "OverridePostUpdatePage": ""}}' > /etc/firefox/policies/policies.json

if [ ! -x /usr/bin/firefox-esr ]; then
    python3 /opt/stol/store/codoceh-store.py --install firefox --then /usr/bin/firefox-esr 9>&-
    [ -x /usr/bin/firefox-esr ] || exit 0
fi

pkill -x chromium 2>/dev/null
packages=$(dpkg-query -W -f='${Package}\n' 'chromium*' 2>/dev/null)
[ -n "$packages" ] || exit 1
# shellcheck disable=SC2086
apt-get purge -y $packages || exit 1

rm -rf /usr/local/bin/chromium /etc/chromium.d "$HOME/.local/share/applications/chromium.desktop"
for name in x-www-browser gnome-www-browser sensible-browser; do
    printf '#!/bin/sh\nexec /usr/bin/firefox-esr "$@"\n' > "/usr/local/bin/$name"
    chmod 755 "/usr/local/bin/$name"
done
sed -i 's/chromium\.desktop/firefox-esr.desktop/g' "$HOME/.config/mimeapps.list" 2>/dev/null
sed -i 's/^WebBrowser=chromium$/WebBrowser=firefox/' "$HOME/.config/xfce4/helpers.rc" 2>/dev/null

favorites=$(xfconf-query -c xfce4-panel -p /plugins/plugin-1/favorites 2>/dev/null | grep '\.desktop$' | sed 's/^chromium\.desktop$/firefox-esr.desktop/')
set --
for favorite in $favorites; do
    set -- "$@" -t string -s "$favorite"
done
[ $# -eq 0 ] || xfconf-query -c xfce4-panel -p /plugins/plugin-1/favorites "$@"

xfce4-panel -q 2>/dev/null
sleep 2
sed -i 's#/usr/share/applications/chromium\.desktop#/usr/share/applications/firefox-esr.desktop#g' "$HOME"/.config/xfce4/panel/docklike-*.rc 2>/dev/null
sed -i '/^pinned=/s/\([=;]\)chromium;/\1firefox-esr;/' "$HOME"/.config/xfce4/panel/docklike-*.rc 2>/dev/null
setsid xfce4-panel >/dev/null 2>&1 </dev/null 9>&- &
