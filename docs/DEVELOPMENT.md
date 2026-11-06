# КодоЦех Стол

Один APK, который поднимает рабочий стол Linux на любом Android-телефоне.

## Требования к устройству

- **ОС**: Android 9 (API 28) или новее (включая Android 16, API 36).
- **Архитектура**: 64-битный процессор `arm64` (`aarch64`).
- **Свободное место**: не менее 5 ГБ для разворачивания системы стола и программ.
- **Root-права**: не требуются (работает изолированно через proot).

## Что внутри стола

- **Рабочий стол XFCE** + `Firefox`, `LibreOffice` (Writer/Calc/Impress), `Geany`, файловый менеджер `Thunar`.
- **Магазин** (`/opt/stol/store`) — ставит программы по требованию; `Windows`-стек скрыт из витрины, но ставится сам при двойном клике по `.exe`/`.msi`/`.bat`.
- **GPU-ускорение (Adreno)** — `Mesa 26` `zink→turnip→kgsl-3d0` (`/opt/stol/gpu`, `gl-run`). На `Adreno 840` `glmark2 1280×720: 236–486 fps` против `4,4` на `llvmpipe`; включается авто-детектом `kgsl` в `start-gui.sh` или вручную ` /opt/stol/gpu/gl-run ПРОГРАММА`.

## Сборка

```sh
bash tools/mkimage.sh          # ~20 мин, нужен lima `ceho` (aarch64), кладёт `build/image/rootfs.tar.zst`
bash tools/checkimage.sh       # 9 правил, должен быть `ЧИСТО`
./gradlew clean && bash ci/build-release.sh  # APK `~454M` (<500M `R01.3i`)
adb install -r app/build/outputs/apk/debug/app-debug.apk  # данные стола не трогает
```
