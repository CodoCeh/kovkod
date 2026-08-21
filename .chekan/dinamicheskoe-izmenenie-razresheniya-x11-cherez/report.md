# Динамическое изменение разрешения X11 через xrandr при повороте и…

Динамическое изменение разрешения X11 через xrandr при повороте и раскрытии фолда, предустановка и запуск приложений (Claude, VS Code, Antigravity)

Слаг: `dinamicheskoe-izmenenie-razresheniya-x11-cherez` · режим normal · масштаб средняя
Начат: 2026-08-19T20:55:47+03:00

## Сделано

По вердикту слепого приёмщика — субагента, которому дали только бриф:
- Динамическое изменение разрешения X11 через xrandr при повороте экрана и изменении геометрии (Fold) реализовано в DesktopLauncher.resize, DesktopView.surfaceChanged и MainActivity (onConfigurationChanged, ResizeListener)
- Ярлыки запуска и конфигурации приложений Claude, VS Code, Antigravity, Obsidian, ZCode настроены в reference/desktop-entries/*.desktop с поддержкой нативного бинарного запуска и fallback на веб-версии Chromium
- Модульные тесты Gradle (:app:testDebugUnitTest) успешно пройдены без ошибок
- Тесты верификации чистоты и целостности образа (tools/test-checkimage.sh) успешно пройдены (34 из 34 проверок сошлись)

## Доказательства

Прогонов записано: 8, из них зелёных: 5.

- 01 Динамическое изменение разрешения X11 через xrandr при onSizeChanged в DesktopView/DesktopLauncher — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит afb3cf28
- 02 Предустановка исполняемых лаунчеров и ярлыков для Claude, VS Code, Antigravity, Obsidian, ZCode — `bash tools/test-checkimage.sh` → 0, коммит 50c5822e
- 03 Тестирование динамической смены геометрии xrandr и запуска приложений на эмуляторе — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит 514ca551

## Гейты

- G1 Требования разобраны: пройден
- G2 Спека покрывает бриф: пройден
- G3 План прослеживается в обе стороны: пройден
- G4 Приёмка доказана прогоном: пройден
