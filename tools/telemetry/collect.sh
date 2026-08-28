#!/bin/sh
# Сбор телеметрии падений стола: buffer-сцена, wine/zink stack overflow, X trace.
set -eu
OUT="/tmp/telemetry-$(date +%Y%m%d-%H%M%S).tar.zst"
LOGS="/tmp/telemetry-collect.log"
echo "Собираю телеметрию..." | tee "$LOGS"
for f in /tmp/win-auto.log /tmp/win-install.log /tmp/glmark2.log /tmp/glboom.log /tmp/telemetry.log /var/log/Xorg.1.log /run/stol/start.log; do
  [ -f "$f" ] && echo " + $f ($(wc -l < "$f") строк)" | tee -a "$LOGS"
done
tar --zstd -cf "$OUT" -C /tmp win-auto.log win-install.log glmark2.log glboom.log telemetry.log 2>/dev/null || tar --zstd -cf "$OUT" -C /tmp . 2>/dev/null | head -1
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))" | tee -a "$LOGS"
# Также дублируем в /tmp/telemetry.log для долгого хранения
cat "$LOGS" >> /tmp/telemetry.log 2>/dev/null || true
