#!/usr/bin/env bash
#
# ci/build-release.sh — сборка APK с образом внутри и измерение веса (R01, R11i, R13).
#
# Вес APK — не наблюдение, а рычаг: превысил потолок — сборка красная. Потолок 500 МБ
# выведен, а не назначен: до офиса APK весил 368 МБ, офис добавляет около 130 МБ
# сжатыми, снос набора значков Yaru возвращает около 30 МБ — расчётные 470 МБ плюс
# запас на разброс сжатия (R01.3i).
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
    local want have overlay
    want="$(shasum -a 256 "$src/rootfs.tar.zst" | cut -d' ' -f1)"
    have=""
    [ -f "$ASSETS_DIR/rootfs.tar.zst" ] \
        && have="$(shasum -a 256 "$ASSETS_DIR/rootfs.tar.zst" | cut -d' ' -f1)"
    if [ "$want" = "$have" ]; then
        ok "образ в assets совпадает со свежесобранным ($want)"
    else
        log "образ в assets устарел, кладём свежий из $(basename "$src")"
        cp "$src/rootfs.tar.zst" "$ASSETS_DIR/"
        ok "образ обновлён: $want"
    fi
    # Описание образа может меняться отдельно: например, добавляется маленький
    # overlay при неизменном полном rootfs. Его нельзя оставлять старым по сумме архива.
    cp "$src/image.json" "$ASSETS_DIR/"

    overlay="$(sed -n 's/^[[:space:]]*"overlay_asset"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
        "$src/image.json" | head -1)"
    if [ -n "$overlay" ]; then
        [ -f "$src/$overlay" ] || die "в image.json назван слой $overlay, но файла нет"
        cp "$src/$overlay" "$ASSETS_DIR/$overlay"
        ok "слой обновления добавлен: $overlay"
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

OVERLAY_ASSET="$(sed -n 's/^[[:space:]]*"overlay_asset"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
    "$ASSETS_DIR/image.json" | head -1)"
if [ -n "$OVERLAY_ASSET" ]; then
    echo "$CONTENTS" | grep -q "assets/$OVERLAY_ASSET" \
        || die "В APK нет слоя обновления $OVERLAY_ASSET"
fi

ok "все необходимые файлы и библиотеки внутри APK"

log "4. Измерение веса APK против потолка (R01.3i)"
APK_SIZE="$(stat -f%z "$APK_FILE" 2>/dev/null || stat -c%s "$APK_FILE")"
APK_SIZE_HUMAN="$(du -sh "$APK_FILE" | cut -f1)"

APK_LIMIT_MB=500
APK_LIMIT=$((APK_LIMIT_MB * 1024 * 1024))

# Вес образа кладём рядом с весом APK: R11i.1 просит оба числа, и второе иначе
# пришлось бы выкапывать из assets руками.
IMAGE_BYTES="$(sed -n 's/^[[:space:]]*"bytes"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' \
    "$ASSETS_DIR/image.json" | head -1)"

mkdir -p "$REPO/build/reports"
cat > "$REPO/build/reports/apk-size.json" <<EOF
{
  "apk": "$APK_FILE",
  "bytes": $APK_SIZE,
  "human": "$APK_SIZE_HUMAN",
  "limit_bytes": $APK_LIMIT,
  "limit_human": "${APK_LIMIT_MB}M",
  "image_bytes": ${IMAGE_BYTES:-0},
  "arm64_only": true
}
EOF

[ "$APK_SIZE" -le "$APK_LIMIT" ] \
    || die "APK $APK_SIZE_HUMAN ($APK_SIZE байт) перевалил потолок ${APK_LIMIT_MB} МБ (R01.3i)"

ok "вес APK: $APK_SIZE_HUMAN ($APK_SIZE байт), потолок ${APK_LIMIT_MB} МБ"
ok "вес образа внутри: $(numfmt --to=iec "${IMAGE_BYTES:-0}" 2>/dev/null || echo "${IMAGE_BYTES:-0} байт")"

echo
printf '\033[32mЗЕЛЁНО\033[0m  сборка APK и проверка содержимого завершены успешно\n'
exit 0
