#!/bin/sh
#
# /opt/stol/backup.sh create АРХИВ | restore АРХИВ — настройки стола, ярлыки и список программ из магазина.

set -eu

STORE=/opt/stol/store
LIST=/root/.config/kovkod/store-apps.txt

case "${1:-}" in
    create)
        mkdir -p "$(dirname "$LIST")"
        python3 - "$STORE" > "$LIST" <<'PY'
import json, subprocess, sys
store = sys.argv[1]
for item in json.load(open(store + "/catalog.json", encoding="utf-8")):
    if subprocess.run(["python3", store + "/backend.py", "installed", item["id"]],
                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        print(item["id"])
PY
        cd /
        set -- "$2"
        for item in root/.config/xfce4 root/.config/kovkod root/.config/Thunar root/.config/gtk-3.0 \
            root/.config/mimeapps.list root/.config/autostart root/Desktop \
            root/.local/share/applications root/.bashrc root/.profile; do
            [ -e "$item" ] && set -- "$@" "$item"
        done
        archive=$1
        shift
        tar -czf "$archive" --ignore-failed-read --exclude='.l2s.*' "$@"
        ;;
    restore)
        tar -xzf "$2" -C /
        [ -f "$LIST" ] || exit 0
        while read -r app; do
            [ -n "$app" ] || continue
            python3 "$STORE/backend.py" installed "$app" >/dev/null 2>&1 \
                || python3 "$STORE/backend.py" install "$app" || true
        done < "$LIST"
        ;;
    *)
        echo "usage: $0 create|restore ARCHIVE" >&2
        exit 2
        ;;
esac
