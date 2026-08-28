#!/bin/sh
# Резерв и сброс WINEPREFIX (~/.wine) — вызывается из Настроек или вручную.
set -eu
PREFIX="$HOME/.wine"
BACKUP="$HOME/Wine-backup-$(date +%Y%m%d-%H%M).tar.zst"
case "${1:-backup}" in
  backup)
    [ -d "$PREFIX" ] || { echo "Префикс $PREFIX не найден"; exit 1; }
    echo "Архивирую $PREFIX → $BACKUP ..."
    tar --zstd -cf "$BACKUP" -C "$HOME" .wine 2>&1 | tail -3
    echo "Готово: $BACKUP ($(du -h "$BACKUP" | cut -f1))"
    ;;
  reset)
    echo "Сбрасываю $PREFIX ..."
    rm -rf "$PREFIX"
    /usr/bin/wine wineboot --init 2>&1 | tail -5
    echo "Префикс пересоздан"
    ;;
  *) echo "Использование: $0 [backup|reset]"; exit 1 ;;
esac
