# Спецификация: Динамическая смена ориентации экрана и раскрытия фолда, запуск и установка Electron-приложений с рабочим DNS

## 1. Контекст и архитектура

КодоЦех Стол поднимает сессию Debian 13 XFCE внутри proot.
Настоящая спецификация решает две проблемы:
1. Восстановление плавной динамической смены ориентации экрана / раскрытия Fold без пересоздания Activity, без падения сессии и без зависания на экране «Стол собран».
2. Поддержка запуска и установки Electron-приложений (Visual Studio Code, Claude, Antigravity 2.0, Obsidian, ZCode) с рабочим DNS-резолвером и обязательными параметрами `--user-data-dir` и `--no-sandbox`.

## 2. Требования

### R01, R02 & R05i. Динамическая адаптация геометрии и фолда без пересоздания Activity
- **R01 (configChanges):** В `AndroidManifest.xml` для `MainActivity` указывается `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|density"`, исключая пересоздание Activity при поворотах устройства и раскладывании Fold.
- **R02 (Гарантированный вход в стол):** В `MainActivity.java` в методе `launchWhenMeasured()` проверяется текущий размер `rootContainer`. Если `width > 0 && height > 0`, запуск `launcher.launch(desktopFit)` выполняется немедленно без ожидания `OnGlobalLayoutListener`, исключая зависание на экране «Стол собран».
- **R05i (Динамический Viewport):** При изменении размеров экрана `DesktopView` динамически пересчитывает масштабирование и центрирование через `Viewport.java` без разрыва RFB-соединения и без перезапуска XFCE.

### R03, R04 & R06i. Запуск, установка Electron-приложений и DNS
- **R03 (Флаги запуска):** В `reference/desktop-entries/` для VS Code и Electron-приложений добавляются флаги `--user-data-dir=/root/.config/<app>`, `--no-sandbox`, `--disable-gpu`, `--disable-software-rasterizer`, `--disable-dev-shm-usage`.
- **R04 (Каталог магазина):** В `store/catalog.json` и `store/backend.py` регистрируются позиции VS Code, Claude, Antigravity 2.0, Obsidian, ZCode с корректными маркерами проверки установки и URL загрузки.
- **R06i (Динамический DNS):** При старте сессии в `ProotSession.java` / `DesktopLauncher.java` актуальный системный DNS устройства Android синхронизируется в `/etc/resolv.conf`.

### R07i. Тестирование и верификация
- Модульные тесты `./gradlew testDebugUnitTest -Pstol.forge.canon.absent=1` и проверка образа `bash tools/test-checkimage.sh`.
- Проверка работы на эмуляторе.
