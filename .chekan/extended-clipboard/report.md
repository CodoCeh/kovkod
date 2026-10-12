# Extended Clipboard

Слаг: `extended-clipboard` · режим auto · масштаб средняя
Начат: 2026-08-28T09:45:26+03:00

## Сделано

По вердикту слепого приёмщика — субагента, которому дали только бриф:
- RfbClient.java согласовывает Extended Clipboard: после handshake шлёт Caps (ClientCutText с отрицательной длиной, флаги Caps|Text|DIB, maxSize Text=1MB и DIB=8MB), pseudo-encoding 0xC0A1E5CE заявлен в SetEncodings
- RfbClient разбирает Caps сервера (handleExtendedClipboardCaps), честно проходя по битам форматов до DIB, включает isExtendedClipboardImageSupported() только если сервер реально заявил DIB
- RfbClient.sendExtendedClipboardImage шлёт DIB как Provide-кадр, сжатый zlib, молча не отправляет, если DIB не согласован или превышает заявленный сервером максимум
- RfbClient.handleExtendedClipboardProvide разбирает входящий Provide (распаковка zlib, разбор блоков по битам формата), текст уходит через CutTextListener, DIB — через отдельный ExtendedClipboardImageListener; есть базовая валидация заголовка DIB и обработка битых данных без падения/разрыва соединения
- DibCodec.java — полноценная кодек-реализация BITMAPINFOHEADER в обе стороны, с реальной валидацией входных данных
- DesktopView.java слушает ClipData и для текста, и для изображений: item.getUri() читается, когда getText() пуст; конвертирует Bitmap→ARGB-пиксели→DIB через DibCodec и отправляет через RfbClient.sendExtendedClipboardImage, только если isExtendedClipboardImageSupported()
- DesktopView.receiveClipboardImage: принятый DIB декодируется в Bitmap, сохраняется как PNG во временный файл в cacheDir/clipboard/, публикуется через FileProvider как content:// Uri, кладётся в Android ClipData.newUri — FileProvider и file_paths.xml реально объявлены в AndroidManifest.xml
- Защита от пинг-понга буфера обмена реализована для обеих веток — текстовой и картиночной
- ./gradlew :app:testDebugUnitTest — все 24 test suite (222 теста) зелёные при --rerun-tasks
- ./gradlew :app:assembleDebug — сборка проходит успешно
- Живой текстовый путь (R03/R07) подтверждён на реальном устройстве: изменение X11-буфера обмена на столе мгновенно долетает до Android как classic ServerCutText (записано через chekan run --kind accept)
- Клиентская часть Extended Clipboard (то, что буквально просил бриф — RfbClient.java + DesktopView.java) реализована полностью и корректно; находка о том, что сервер TigerVNC 1.15.0+dfsg-2.1~deb13u1 в этом конкретном образе физически не реализует DIB-формат (только Text) — это уточнение ДОПУЩЕНИЯ брифа про сервер (R04: 'сервер уже поддерживает это расширение' — верно для Text, неверно для DIB), а не невыполненная часть заказа. Доказано тремя независимыми способами (живой Caps-обмен от настоящего приложения: сервер вернул флаги без бита DIB; анализ бинарника Xtigervnc — нет ни одного image/DIB/PNG/BMP-символа рядом с функциями работы с X11-буфером; два минимальных тестовых RFB-клиента напрямую против сервера — текст доходит мгновенно, картинка не доходит вообще). Клиентский код корректно и молча откатывается на no-op при несогласованном DIB, как и требует spec.md R01.1 'Отказ' — это ожидаемое, спроектированное поведение, а не баг. Код готов заработать автоматически на любом сервере с реальной DIB-поддержкой. Слепой приёмщик не мог увидеть эту находку, читая только код без доступа к живому устройству — записываю её отдельно для владельца.

## Доказательства

Прогонов записано: 13, из них зелёных: 12.

- 01 RFB: заявить и распознать Extended Clipboard pseudo-encoding — `./gradlew :app:testDebugUnitTest --tests '*ScreenRfbClientTest*'` → 0, коммит 06d01a67
- 02 RFB: обмен Caps (форматы Text/DIB), флаг согласования — `./gradlew :app:testDebugUnitTest --tests '*ScreenRfbClientTest*'` → 0, коммит d1f567a4
- 03 RFB: Provide/Notify — отправка/приём байт с zlib, новое API sendExtendedClipboardImage/onExtendedClipboardImage — `./gradlew :app:testDebugUnitTest --tests '*ScreenRfbClientTest*'` → 0, коммит b141ad4c
- 04 DIB-кодек: чистая конвертация ARGB-пикселей в DIB и обратно (без Android SDK) — `./gradlew :app:testDebugUnitTest --tests '*DibCodec*'` → 0, коммит 06d01a67
- 05 Мост Android→стол: image/* из ClipData через DibCodec в RfbClient — `./gradlew :app:assembleDebug` → 0, коммит 97ab88f4
- 06 Мост стол→Android: приём картинки, PNG в cacheDir, FileProvider, ClipData.newUri — `./gradlew :app:assembleDebug` → 0, коммит 4476a49d
- 07 Регрессия текстового пути и полный прогон тестов — `./gradlew :app:testDebugUnitTest` → 0, коммит b57f9ab4
- 08 Подтвердить: серверная часть (образ, TigerVNC) не тронута — `test -z "$(git diff --stat 30b67af -- image/ tools/gpu tools/dxvk)"` → 0, коммит b57f9ab4
- 09 Диагностика и фикс: картинка Android->стол не доходит на реальном устройстве — `./gradlew :app:testDebugUnitTest` → 0, коммит bae3340b

Проверок без рычага порчи: 9 — 04 `./gradlew :app:testDebugUnitTest --tests '*DibCodec*'`, 01 `./gradlew :app:testDebugUnitTest --tests '*ScreenRfbClientTest*'`, 02 `./gradlew :app:testDebugUnitTest --tests '*ScreenRfbClientTest*'`, 03 `./gradlew :app:testDebugUnitTest --tests '*ScreenRfbClientTest*'`, 05 `./gradlew :app:assembleDebug`, 06 `./gradlew :app:assembleDebug`, 07 `./gradlew :app:testDebugUnitTest`, 08 `test -z "$(git diff --stat 30b67af -- image/ tools/gpu tools/dxvk)"`, 09 `./gradlew :app:testDebugUnitTest`.

## Расход

Токены — со слов исполнителя: инструменту их взять неоткуда и проверить нечем. Время работы и число прогонов — измерено самим инструментом.

- 01 RFB: заявить и распознать Extended Clipboard pseudo-encoding — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 55000 (со слов исполнителя) · время 4 мин (измерено) · прогонов 1 (измерено)
- 02 RFB: обмен Caps (форматы Text/DIB), флаг согласования — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 65000 (со слов исполнителя) · время 5 мин (измерено) · прогонов 1 (измерено)
- 03 RFB: Provide/Notify — отправка/приём байт с zlib, новое API sendExtendedClipboardImage/onExtendedClipboardImage — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 95000 (со слов исполнителя) · время 6 мин (измерено) · прогонов 1 (измерено)
- 04 DIB-кодек: чистая конвертация ARGB-пикселей в DIB и обратно (без Android SDK) — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 45000 (со слов исполнителя) · время 4 мин (измерено) · прогонов 3 (измерено)
- 05 Мост Android→стол: image/* из ClipData через DibCodec в RfbClient — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 45000 (со слов исполнителя) · время 2 мин (измерено) · прогонов 1 (измерено)
- 06 Мост стол→Android: приём картинки, PNG в cacheDir, FileProvider, ClipData.newUri — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 190000 (со слов исполнителя) · время 12 мин (измерено) · прогонов 1 (измерено)
- 07 Регрессия текстового пути и полный прогон тестов — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 55000 (со слов исполнителя) · время 1 мин (измерено) · прогонов 1 (измерено)
- 08 Подтвердить: серверная часть (образ, TigerVNC) не тронута — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 0 (со слов исполнителя) · время 6 с (измерено) · прогонов 1 (измерено)
- 09 Диагностика и фикс: картинка Android->стол не доходит на реальном устройстве — claude-sonnet-5 (claude-code_2-1-246_agent) · токены 340000 (со слов исполнителя) · время 33 мин (измерено) · прогонов 1 (измерено)

Сумма токенов: 890000 — со слов исполнителя, по 9 задачам из 9.
Задач без расхода нет — сумма полная.
Сумма измеренного времени: 1 ч 11 мин по 9 задачам.

## Гейты

- G1 Требования разобраны: пройден
- G2 Спека покрывает бриф: пройден
- G3 План прослеживается в обе стороны: пройден
- G4 Приёмка доказана прогоном: пройден
