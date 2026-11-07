#!/bin/sh
[ -f /opt/stol/.first-apps-done ] && exit 0
sleep 30
i=0
until python3 -c 'import socket; socket.create_connection(("deb.debian.org", 443), 8)' 2>/dev/null; do
    i=$((i + 1))
    [ "$i" -ge 40 ] && exit 0
    sleep 30
done
python3 /opt/stol/store/codoceh-store.py --first-apps
rm -f /usr/share/dbus-1/services/org.freedesktop.portal.Desktop.service \
    /usr/share/dbus-1/services/org.freedesktop.portal.Documents.service \
    /usr/share/dbus-1/services/org.freedesktop.impl.portal.PermissionStore.service \
    /usr/share/dbus-1/services/org.freedesktop.impl.portal.desktop.gtk.service
pkill -f '[x]dg-desktop-portal'
pkill -x xdg-permission-store
