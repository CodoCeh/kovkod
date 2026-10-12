#!/usr/bin/env bash
# Собирает минимальный слой обновления уже установленного стола: только обои.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$REPO/build/image/wallpaper-overlay.tar.zst}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/kovkod-wallpaper-overlay.XXXXXX")"
STAGE="$WORK/stage"
TAR="$WORK/overlay.tar"

mkdir -p "$STAGE/usr/share/backgrounds/codoceh" "$(dirname "$OUT")"
cp "$REPO/reference/wallpaper.png" \
    "$STAGE/usr/share/backgrounds/codoceh/wallpaper-codoceh.png"

if tar --version 2>/dev/null | grep -q 'GNU tar'; then
    tar --numeric-owner --owner=0 --group=0 -C "$STAGE" -cf "$TAR" \
        ./usr/share/backgrounds/codoceh/wallpaper-codoceh.png
else
    bsdtar -cf "$TAR" --uid 0 --gid 0 -C "$STAGE" \
        ./usr/share/backgrounds/codoceh/wallpaper-codoceh.png
fi
zstd -q -f -19 "$TAR" -o "$OUT"

sha="$(shasum -a 256 "$OUT" | awk '{print $1}')"
bytes="$(stat -f '%z' "$OUT" 2>/dev/null || stat -c '%s' "$OUT")"
printf 'overlay_asset=%s\noverlay_sha256=%s\noverlay_bytes=%s\n' \
    "$(basename "$OUT")" "$sha" "$bytes"
