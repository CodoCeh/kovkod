# Границы и швы: TigerVNC DIB clipboard

Три слоя, каждый не знает о внутренностях соседнего.

## 1. Протокольный слой — `common/rfb/SConnection.cxx` / `.h`

Знает: биты флагов Extended Clipboard, zlib-(рас)паковку кадра, размеры/лимиты (maxSize).
Не знает: X11, Atom, Selection, BMP/DIB-заголовки.

Публичный шов наружу (в X11-слой) — новый виртуальный метод, симметричный уже
существующему текстовому:

```cpp
// уже есть, не трогаем:
virtual void handleClipboardData(const char* data);

// новый, поведение по умолчанию — пустое (как у handleClipboardData):
virtual void handleClipboardImageData(const uint8_t* data, size_t length);
```

Вызывающая сторона (`handleClipboardProvide`) решает по биту флага, в какой из двух
методов передать соответствующий `data[i]`/`lengths[i]` — `handleClipboardData` получает
null-terminated C-строку как раньше, `handleClipboardImageData` получает сырой буфер и
длину, без интерпретации формата.

Симметрично на отправку — сервер X11-слоя дёргает наружу через `SConnection`
(`sendClipboardImageData` или расширение существующего `sendClipboardData`-пути) с тем же
сырым буфером.

## 2. X11-слой — `unix/xserver/hw/vnc/{vncSelection.c, XserverDesktop.cc, XserverDesktop.h, vncExtInit.h}`

Знает: X11 Atom/Selection/Target-протокол, снятие/добавление 14-байтного BMP-заголовка.
Не знает: биты RFB-флагов, zlib, формат кадра Provide/Caps.

- `vncSelection.c`: `vncHandleSelection` дополнительно проверяет `vncHasAtom(xaIMAGE_BMP, ...)`
  рядом с существующими `xaSTRING`/`xaUTF8_STRING`; `vncConvertSelection` обслуживает
  `target == xaIMAGE_BMP` так же, как сейчас `xaSTRING`/`xaTEXT`/`xaUTF8_STRING`.
- Снятие/добавление BMP-заголовка (14 байт: `BM` + размер + резерв + смещение пикселей)
  происходит **только здесь**, на границе с X11 — то, что уходит в `SConnection`, уже
  чистый DIB (BITMAPINFOHEADER + пиксели), то, что приходит из `SConnection`, оборачивается
  в BMP-заголовок только перед тем, как отдать X11-клиенту через `XChangeProperty`.
- `XserverDesktop.cc`/`.h`: glue-функции `sendClipboardImageData`/`handleClipboardImageData`
  — просто перенаправление между C11-кодом `vncSelection.c` и C++ методом `SConnection` из
  шва №1, без собственной логики (по образцу уже существующих
  `sendClipboardData`/`handleClipboardData`).
- `vncExtInit.h`: добавить extern C объявления новых glue-функций рядом с существующими
  `vncRequestClipboard`/`vncAnnounceClipboard`/`vncSendClipboardData`.

## 3. Пайплайн сборки образа — `tools/mkimage.sh`

Знает: путь к `.deb` в `tools/artifacts/tigervnc/`, что нужно `dpkg -i` вместо apt install.
Не знает и не должен знать: что внутри патча, как он собран.

Единственный публичный контракт между патчем и `mkimage.sh`/`checkimage.sh` — **версия
пакета** (суффикс `+codoceh1`), которую можно проверить командой `dpkg -l`. Если версия
патча когда-нибудь поменяется — обновляется только строка сборки и, при необходимости,
ожидаемый суффикс в проверке; сам скрипт формат патча не разбирает.

## Инвариант, который держит всю границу

Каждый шов передаёт **сырые байты** без интерпретации формата на своей стороне — так же,
как сейчас текстовый путь передаёт `const char*` без интерпретации кодировки внутри
`SConnection`. Смена реализации в одном слое (например, добавление PNG вместо/вместе с
BMP в X11-слое) не требует правок в соседних слоях, кроме, возможно, размера буфера.
