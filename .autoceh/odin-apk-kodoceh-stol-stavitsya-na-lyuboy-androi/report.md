# Один APK «КодоЦех Стол»: ставится на любой Android и поднимает тот же…

Один APK «КодоЦех Стол»: ставится на любой Android и поднимает тот же рабочий стол Linux — тот же лаунчер, фон и тема, тот же набор программ, но без личных данных владельца

Слаг: `odin-apk-kodoceh-stol-stavitsya-na-lyuboy-androi` · режим auto · масштаб крупная
Начат: 2026-08-17T20:08:50+03:00

## Сделано

По вердикту слепого приёмщика — субагента, которому дали только бриф:
- Один автономный APK (ru.codoceh.stol): ставится как одно приложение на Android (minSdk 28, arm64) без необходимости ставить внешние APK (Termux, AVNC) и без root-прав.
- Полноценный рабочий стол Linux: чистый rootfs Debian 13 trixie (arm64) с окружением XFCE и Xtigervnc, запускаемый через изолированный proot-процесс.
- Брендовый фон и эталонная тема: обои с наковальней и QR-кодами (reference/wallpaper.png, совпадение по sha256) и настройки xfconf (xsettings, xfwm4, xfce4-desktop, xfce4-panel), идентичные эталону Galaxy Fold.
- Полный набор иконок стола: Antigravity, Antigravity 2.0, Claude, Visual Studio Code, Obsidian, ZCode, Gemini (ярлыки в /root/Desktop и док-панель XFCE).
- Полная очистка от личных данных владельца: сборка с нуля (debootstrap), белый список файлов manifest.txt, проверка checkimage.sh и check-nologin.sh (отсутствие истории команд, ключей, токенов, кук и сессий), генерация уникального machine-id при старте.
- Собственный RFB/VNC-клиент на Java (RFC 6143): написан с нуля без копилефтных библиотек и без форка AVNC, с поддержкой тач-жестов, масштабирования и клавиатуры.
- Мастер докачки проприетарных программ при первом запуске: автоматическая установка с официальных сайтов для VS Code, Obsidian, Antigravity, Antigravity 2.0, Claude Desktop, ZCode без включения закрытых бинарников в раздаваемый образ.
- Магазин приложений и игр с графическим интерфейсом: окно на Python/Tkinter (store/codoceh-store.py) для установки и удаления программ (Geany, GIMP, Evince, MC и др.) и игр (gnome-mines, aisleriot с флагом софтверного рендеринга) без использования терминала.
- Юридическая чистота и разделение лицензий: изоляция proot и talloc в виде отдельных неизменённых процессов (runtime/ProotSession.java), отсутствие GPL-заражения кода приложения, реестр лицензий (attributions.json) и письменное предложение исходников (source-offer.txt).
- Простота использования (UX для ребёнка): единая точка входа LAUNCHER, запуск в один тап («Собрать» / запуск стола), отсутствие технического жаргона, портов и путей на экранах пользователя, остановка стола в 1 клик.
- Тестовое покрытие и сборка: 172 unit-теста Gradle (:app:testDebugUnitTest) проходят со 100% успехом, скрипты сборки и валидации (ci/build-release.sh, tools/checkimage.sh, licensing/audit.sh, store/test-store.sh, image/test-scripts.sh, tests/ux/simplicity.sh, tests/e2e/two-devices.sh) завершаются успешно.

## Доказательства

Прогонов записано: 28, из них зелёных: 27.

- 01 Каркас приложения и вход в Linux: proot и родные бинарники arm64 — `./gradlew :app:testDebugUnitTest` → 0, коммит 8dd23e7e
- 02 Снимок эталона с Fold: обои, тема, панель, ярлыки — `bash reference/verify-dump.sh` → 0, коммит 2adca29d
- 03 Сборка чистого образа Debian с XFCE и Xtigervnc — `bash tools/mkimage.sh --smoke` → 0, коммит ef5c382b
- 04 Проверка образа по белому списку: ни одного лишнего пути — `bash tools/test-checkimage.sh` → 0, коммит 258eb0b6
- 05 Скрипты стола в образе: запуск, остановка, маячок готовности — `bash image/test-scripts.sh` → 0, коммит 1ddccb4b
- 06 Распаковка образа из assets: сумма, атомарность, место на диске — `./gradlew :app:testDebugUnitTest --tests '*Installer*'` → 0, коммит 78601d34
- 07 Мастер первого запуска, в том числе без сети — `./gradlew :app:testDebugUnitTest --tests '*Wizard*'` → 0, коммит ef5c382b
- 08 Свой клиент RFB: подключение к 127.0.0.1:5901 и ввод — `./gradlew :app:testDebugUnitTest --tests '*Screen*'` → 0, коммит f68a47af
- 09 Экран ожидания с кузницей, запуск и остановка стола — `./gradlew :app:testDebugUnitTest --tests '*Launch*'` → 0, коммит 3d5d2772
- 10 Установщик программ: четыре вида, честный итог по каждой — `./gradlew :app:testDebugUnitTest --tests '*Apps*'` → 0, коммит 0c821552
- 11 Набор из семи программ описан данными — `./gradlew :app:testDebugUnitTest --tests '*AppsManifest*'` → 0, коммит 044d00a6
- 12 Лицензионная гигиена: происхождение каждого чужого куска и экран «О программе» — `./gradlew :app:testDebugUnitTest --tests '*About*'` → 0, коммит c8a39138
- 13 Сборка APK с образом внутри, вес измерен — `bash ci/build-release.sh` → 0, коммит b8366c22
- 14 Прогон на двух устройствах с разными версиями Android — `bash tests/e2e/two-devices.sh` → 0, коммит 6dbfb231
- 15 После мастера ни одна программа не залогинена — `bash tools/test-nologin.sh` → 0, коммит c6a23a27
- 16 Ревизия прав: реестр, письменное предложение исходников, чистка сирот — `bash licensing/audit.sh` → 0, коммит 73f6d5d3
- 17 Магазин программ и игр внутри стола, с реальной проверкой установки — `bash store/test-store.sh` → 0, коммит 24fce77a
- 18 Простота сквозным проходом: одно приложение, счётный путь до стола, без жаргона — `bash tests/ux/simplicity.sh` → 0, коммит e777c5fa
- 19 Исполняемые файлы в каталоге родных библиотек: снять потолок targetSdk — `./gradlew :app:testDebugUnitTest --tests '*Native*'` → 0, коммит ef5c382b
- 20 Письменное предложение исходников и перечень пакетов образа — `./gradlew :app:testDebugUnitTest --tests '*Offer*'` → 0, коммит 392c8867

## Гейты

- G1 Требования разобраны: пройден
- G2 Спека покрывает бриф: пройден
- G3 План прослеживается в обе стороны: пройден
- G4 Приёмка доказана прогоном: пройден
