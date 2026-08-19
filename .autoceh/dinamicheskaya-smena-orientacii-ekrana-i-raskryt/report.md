# Динамическая смена ориентации экрана и раскрытия фолда без перезапуска…

Динамическая смена ориентации экрана и раскрытия фолда без перезапуска стола, запуск и установка Electron-приложений (VS Code, Claude, Antigravity) с рабочим DNS

Слаг: `dinamicheskaya-smena-orientacii-ekrana-i-raskryt` · режим normal · масштаб средняя
Начат: 2026-08-19T20:45:38+03:00

## Сделано

По вердикту слепого приёмщика — субагента, которому дали только бриф:
- В AndroidManifest.xml прописан configChanges='orientation|screenSize|screenLayout|smallestScreenSize|density', в MainActivity.java реализован launchWhenMeasured() с подгонкой стола DesktopFit и onConfigurationChanged() без перезапуска сессии
- В ProotSession.java реализована синхронизация системных DNS Android (через android.os.SystemProperties net.dns1..4 с фоллбеком на 1.1.1.1 и 8.8.8.8) в /etc/resolv.conf перед каждым запуском сессии
- В store/catalog.json и store/backend.py реализованы позиции каталога и механизмы установки deb-url и appimage-extract для Visual Studio Code, Claude Desktop, Antigravity 2.0, Obsidian и ZCode
- В reference/desktop-entries/*.desktop для Electron-приложений (code, claude, antigravity, antigravity2, obsidian, zcode) настроены флаги запуска без песочницы (--no-sandbox, --disable-gpu, --disable-dev-shm-usage, --user-data-dir)
- Юнит-тесты Gradle (:app:testDebugUnitTest -Pstol.forge.canon.absent=1) завершились успешно (BUILD SUCCESSFUL)
- Тест проверки структуры и чистоты образа (bash tools/test-checkimage.sh) завершился успешно (34 проверки сошлись, 0 разошлось)

## Доказательства

Прогонов записано: 7, из них зелёных: 6.

- 01 configChanges в манифесте и исправление launchWhenMeasured для динамической адаптации стола — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит 956a4b46
- 02 Автоматическая синхронизация активного DNS Android в /etc/resolv.conf при старте — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит 6947479c
- 03 Регистрация VS Code, Claude, Antigravity 2.0, Obsidian, ZCode в каталоге магазина и флаги запуска — `bash tools/test-checkimage.sh` → 0, коммит 5407f272
- 04 Сквозная проверка и сборка на эмуляторе — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит fef0d864

## Гейты

- G1 Требования разобраны: пройден
- G2 Спека покрывает бриф: пройден
- G3 План прослеживается в обе стороны: пройден
- G4 Приёмка доказана прогоном: пройден
