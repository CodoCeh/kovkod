# Интеграция КодоЦех Стол с Android: файловый мост, двусторонний буфер…

Интеграция КодоЦех Стол с Android: файловый мост, двусторонний буфер обмена и динамическое разрешение

Слаг: `integraciya-kodoceh-stol-s-android-faylovyy-most` · режим auto · масштаб средняя
Начат: 2026-08-19T13:09:56+03:00

## Сделано

По вердикту слепого приёмщика — субагента, которому дали только бриф:
- Файловый мост в ProotSession.java: проброс каталогов загрузок (/sdcard/Download, /storage/emulated/0/Download) в /root/Загрузки и документов (/sdcard/Documents, /storage/emulated/0/Documents) в /root/Документы через proot -b
- Двусторонний буфер обмена в RfbClient.java и DesktopView.java: приём ServerCutText (тип 3) и отправка ClientCutText (тип 6), двусторонняя синхронизация с Android ClipboardManager и OnPrimaryClipChangedListener с фильтрацией эхо-зацикливания
- Динамическое масштабирование и геометрия стола в Viewport.java и DesktopView.java: пропорциональное вписывание и центрирование кадра без обрезки, трансформация координат ввода и поддержка изменения размера экрана (ENCODING_DESKTOP_SIZE)
- Юнит-тесты ./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1 успешно пройдены (BUILD SUCCESSFUL, exit code 0)

## Доказательства

Прогонов записано: 5, из них зелёных: 5.

- 01 Файловый мост: проброс /sdcard/Download и /sdcard/Documents в proot — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит 9006c8f2
- 02 Двусторонняя синхронизация буфера обмена Android и X11 RFB — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит 6488946f
- 03 Динамическое разрешение и адаптация геометрии стола под экран и монитор — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит 7d8596e9
- 04 Сквозная проверка мостов интеграции на эмуляторе — `bash tests/e2e/warm-start.sh` → 0, коммит cdcdf586

## Гейты

- G1 Требования разобраны: пройден
- G2 Спека покрывает бриф: пройден
- G3 План прослеживается в обе стороны: пройден
- G4 Приёмка доказана прогоном: пройден
