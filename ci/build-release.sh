#!/usr/bin/env bash
#
# ci/build-release.sh — сборка APK с образом внутри и измерение веса (R01, R11i, R13).
#

set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

log()  { printf '\033[1m==>\033[0m %s\n' "$*"; }
ok()   { printf '    \033[32mOK\033[0m  %s\n' "$*"; }
die()  { printf '\033[31mОШИБКА: %s\033[0m\n' "$*" >&2; exit 1; }

log "1. Подготовка артефактов образа для APK"
ASSETS_DIR="$REPO/app/src/main/assets"
mkdir -p "$ASSETS_DIR"

# Образ кладётся в APK по контрольной сумме, а не по факту наличия. Раньше он
# копировался только если в assets пусто — и пересобранный образ туда не попадал
# вовсе: сборка бодро отчитывалась успехом, а внутри APK ехал образ прошлой
# недели. Молчаливое расхождение хуже отказа: доказывать на таком APK нечего.
stage_image() {
    local src="$1"
    local want have
    want="$(shasum -a 256 "$src/rootfs.tar.zst" | cut -d' ' -f1)"
    have=""
    [ -f "$ASSETS_DIR/rootfs.tar.zst" ] \
        && have="$(shasum -a 256 "$ASSETS_DIR/rootfs.tar.zst" | cut -d' ' -f1)"
    if [ "$want" = "$have" ]; then
        ok "образ в assets совпадает со свежесобранным ($want)"
    else
        log "образ в assets устарел, кладём свежий из $(basename "$src")"
        cp "$src/rootfs.tar.zst" "$ASSETS_DIR/"
        cp "$src/image.json" "$ASSETS_DIR/"
        ok "образ обновлён: $want"
    fi
}

if [ -f "$REPO/build/image/rootfs.tar.zst" ]; then
    stage_image "$REPO/build/image"
elif [ -f "$REPO/build/image-smoke/rootfs.tar.zst" ]; then
    stage_image "$REPO/build/image-smoke"
elif [ ! -f "$ASSETS_DIR/rootfs.tar.zst" ] || [ ! -f "$ASSETS_DIR/image.json" ]; then
    die "Не найден образ rootfs.tar.zst ни в assets, ни в build/image(-smoke)"
else
    log "собранного образа нет, идём с тем, что лежит в assets"
fi

ok "образ на месте в assets: $(du -sh "$ASSETS_DIR/rootfs.tar.zst" | cut -f1)"

log "2. Сборка APK через Gradle"
./gradlew :app:assembleDebug >/dev/null 2>&1 || ./gradlew :app:assembleDebug

APK_FILE="$REPO/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK_FILE" ] || die "APK не собрался: $APK_FILE отсутствует"

ok "APK собран успешно"

log "3. Проверка содержимого APK (R01, R13i)"
CONTENTS="$(unzip -l "$APK_FILE")"

echo "$CONTENTS" | grep -q "assets/rootfs.tar.zst" || die "В APK нет assets/rootfs.tar.zst"
echo "$CONTENTS" | grep -q "assets/image.json" || die "В APK нет assets/image.json"
echo "$CONTENTS" | grep -q "assets/apps.json" || die "В APK нет assets/apps.json"
echo "$CONTENTS" | grep -q "assets/about/attributions.json" || die "В APK нет attributions.json"
echo "$CONTENTS" | grep -q "assets/about/source-offer.txt" || die "В APK нет source-offer.txt"
echo "$CONTENTS" | grep -q "lib/arm64-v8a/libproot.so" || die "В APK нет libproot.so"

ok "все необходимые файлы и библиотеки внутри APK"

log "4. Измерение веса APK"
APK_SIZE="$(stat -f%z "$APK_FILE" 2>/dev/null || stat -c%s "$APK_FILE")"
APK_SIZE_HUMAN="$(du -sh "$APK_FILE" | cut -f1)"

mkdir -p "$REPO/build/reports"
cat > "$REPO/build/reports/apk-size.json" <<EOF
{
  "apk": "$APK_FILE",
  "bytes": $APK_SIZE,
  "human": "$APK_SIZE_HUMAN",
  "arm64_only": true
}
EOF

ok "вес APK: $APK_SIZE_HUMAN ($APK_SIZE байт)"

echo
printf '\033[32mЗЕЛЁНО\033[0m  сборка APK и проверка содержимого завершены успешно\n'
exit 0
