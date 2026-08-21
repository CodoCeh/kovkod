# Падение на Android 11: RemoteServiceException в DesktopService

Слаг: `padenie-na-android-11-remoteserviceexception-v-d` · режим normal · масштаб малая
Начат: 2026-08-19T12:06:57+03:00

## Сделано

По вердикту слепого приёмщика — субагента, которому дали только бриф:
- Вызов startForeground(NOTIFICATION, notification()) реализован немедленно в DesktopService.onCreate(), а также в DesktopService.onStartCommand()
- Корректная обработка жизненного цикла службы: stopForeground(true) в DesktopService.onDestroy(), защита от исключений в методах hold() и release(), предотвращение гонки повторных вызовов в MainActivity.holdWhileDesktopLives()
- Юнит-тесты (:app:testDebugUnitTest, 198 тестов) успешно проходят без ошибок
- Отсутствие падений подтверждено сквозным e2e-прогоном на живом Android 11 / API 30 (emulator-5556)

## Доказательства

Прогонов записано: 3, из них зелёных: 3.

- 01 Починка жизненного цикла DesktopService и гарантированный startForeground — `./gradlew :app:testDebugUnitTest -Pstol.forge.canon.absent=1` → 0, коммит a176493c
- 02 Сквозная проверка отсутствия падений на живом Android 11 (API 30) — `STOL_ENDURANCE_SERIAL=emulator-5556 bash tests/e2e/warm-start.sh` → 0, коммит 2c5a60b7

## Гейты

- G1 Требования разобраны: пройден
- G2 Спека покрывает бриф: не проверялся
- G3 План прослеживается в обе стороны: пройден
- G4 Приёмка доказана прогоном: пройден
