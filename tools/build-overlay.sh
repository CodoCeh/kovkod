#!/usr/bin/env bash
# Собирает слой обновления уже установленного стола: скрипты /opt/stol, меню, обои.
# Печатает поля overlay_* для image.json; /root слой не трогает.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$REPO/build/image/overlay.tar.zst}"
VERSION="${2:?usage: build-overlay.sh OUT VERSION}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/kovkod-overlay.XXXXXX")"
STAGE="$WORK/stage"
TAR="$WORK/overlay.tar"
trap 'rm -rf "$WORK"' EXIT

install -d -m 0755 "$STAGE/opt/stol" "$STAGE/etc/xdg/menus/applications-merged" \
    "$STAGE/usr/share/desktop-directories" "$STAGE/usr/share/backgrounds/codoceh" "$(dirname "$OUT")"
for f in "$REPO"/image/*.sh "$REPO"/image/parametry.py "$REPO"/image/snap.py "$REPO"/image/window-memory.py; do
    install -m 0755 "$f" "$STAGE/opt/stol/$(basename "$f")"
done
install -m 0644 "$REPO/image/menu/kovkod-android.menu" "$STAGE/etc/xdg/menus/applications-merged/"
install -m 0644 "$REPO/image/menu/kovkod-android.directory" "$STAGE/usr/share/desktop-directories/"
install -m 0644 "$REPO/reference/wallpaper.png" "$STAGE/usr/share/backgrounds/codoceh/wallpaper-codoceh.png"
printf '%s\n' "$VERSION" > "$STAGE/opt/stol/VERSION"

if tar --version 2>/dev/null | grep -q 'GNU tar'; then
    tar --numeric-owner --owner=0 --group=0 -C "$STAGE" -cf "$TAR" .
else
    COPYFILE_DISABLE=1 bsdtar -cf "$TAR" --uid 0 --gid 0 --no-mac-metadata -C "$STAGE" .
fi
zstd -q -f -19 "$TAR" -o "$OUT"

sha="$(shasum -a 256 "$OUT" | awk '{print $1}')"
bytes="$(stat -f '%z' "$OUT" 2>/dev/null || stat -c '%s' "$OUT")"
printf 'overlay_asset=%s\noverlay_sha256=%s\noverlay_bytes=%s\n' "$(basename "$OUT")" "$sha" "$bytes"

if [ -n "${3:-}" ]; then
    python3 - "$3" "$(basename "$OUT")" "$sha" "$bytes" <<'PY'
import json, sys
path, asset, sha, size = sys.argv[1:]
data = json.load(open(path))
data.update(overlay_asset=asset, overlay_sha256=sha, overlay_bytes=int(size))
json.dump(data, open(path, "w"), indent=2)
open(path, "a").write("\n")
PY
fi
