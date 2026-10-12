# Границы и швы

## Слои

```
Android ClipboardManager  <-->  DesktopView.java  <-->  RfbClient.java  <-->  RFB-соединение (сокет)
        (ClipData)              (мост/конвертация)      (протокол)
```

`RfbClient.java` ничего не знает про Android `ClipData`/`Bitmap`/`Uri` — он оперирует
только сырыми байтами и форматными флагами протокола. Вся конвертация Android ↔ протокол
живёт в `DesktopView.java`. Это тот же принцип, что уже действует для текста
(`CutTextListener` отдаёт/принимает `String`, не зная про `ClipboardManager`).

## Контракт `RfbClient.java` → вызывающий код (расширение существующего)

Существующий текстовый контракт не меняется:
- `sendCutText(String text)` — отправка текста (как сейчас).
- `CutTextListener.onCutText(String text)` — приём текста (как сейчас).

Новая пара методов для картинок, симметричная текстовой:
- `sendExtendedClipboardImage(byte[] dibBytes)` — отправка DIB-блока. Если Extended
  Clipboard не согласован с собеседником или формат DIB не заявлен — метод тихо
  ничего не делает (не бросает исключение, не буферизует на будущее).
- Расширение слушателя (аналог `CutTextListener`, но для картинок) —
  `onExtendedClipboardImage(byte[] dibBytes)`, вызывается только когда данные успешно
  распакованы (zlib) и прошли базовую валидацию заголовка DIB.
- `isExtendedClipboardImageSupported(): boolean` — можно ли сейчас вызывать
  `sendExtendedClipboardImage` не впустую (для необязательной оптимизации на
  стороне `DesktopView` — не читать `Bitmap` заранее, если отправка всё равно
  будет отброшена).

`RfbClient` сам решает: заявлять ли pseudo-encoding, разбирать Caps, отличать
классический `CutText` от Extended (по знаку длины) — это не выходит наружу.

## Контракт `DesktopView.java` (мост)

Вход со стороны Android:
- `ClipData.Item.getText() != null` → существующий текстовый путь, без изменений.
- `getText() == null && getUri() != null && MIME начинается с "image/"` → новая
  картиночная ветка: прочитать через `ContentResolver` → декодировать в `Bitmap` →
  сконвертировать в DIB (BITMAPINFOHEADER + пиксели, без 14-байтного BMP-заголовка
  файла) → `rfbClient.sendExtendedClipboardImage(dib)`.
- Любой другой случай (не текст, не картинка) — no-op, как и сейчас.

Вход со стороны стола (`onExtendedClipboardImage`):
- Раскодировать DIB → `Bitmap` → сохранить как PNG во временный файл в `cacheDir`
  приложения → получить `content://` `Uri` через существующий/новый `FileProvider`
  → `ClipData.newUri(resolver, "image", uri)` → `clipboardManager.setPrimaryClip(...)`.

## Формат DIB (для реализующего таск)

DIB = `BITMAPINFOHEADER` (40 байт) + пиксельные данные, **без** 14-байтного
`BITMAPFILEHEADER` — это ровно то, что лежит в Windows-буфере обмена под форматом
`CF_DIB`, и то, что Extended Clipboard передаёт под флагом DIB. Конвертация
`Bitmap → DIB` и `DIB → Bitmap` — новый вспомогательный код (не Android API,
своя реализация или существующая лёгкая библиотека — решает исполнитель таска).

## Кто кого зовёт (порядок вызовов)

1. RFB handshake завершён → `RfbClient` сам инициирует обмен Caps (не ждёт внешнего
   вызова) → выставляет внутренний флаг «Extended Clipboard согласован: да/нет,
   форматы: {Text, DIB}».
2. Android скопировал что-то → `DesktopView` решает, текст это или картинка, и
   зовёт соответствующий метод `RfbClient`.
3. С сокета пришло `ServerCutText` с отрицательной длиной → `RfbClient` сам
   разбирает Caps/Provide, для Provide с форматом DIB — зовёт
   `onExtendedClipboardImage`, для Text — существующий `onCutText`.

## Что не пересекает границу

- `Bitmap`, `Uri`, `ContentResolver`, `ClipboardManager` — не заходят в
  `RfbClient.java` ни в одну сторону.
- Формат Caps/Provide/флаги протокола — не заходят в `DesktopView.java`; он получает
  уже готовые сырые байты DIB и передаёт их обратно как сырые байты DIB.
