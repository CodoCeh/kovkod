# Интерфейсы и швы

## 1. TouchTranslator.Mode
```java
public enum Mode {
    TOUCH,
    TRACKPAD
}
```
Методы `TouchTranslator`:
- `setMode(Mode mode)`
- `mode()` -> `Mode`
- `setCursor(int x, int y)`

## 2. DesktopView
- `setTouchMode(TouchTranslator.Mode mode)`
- `touchMode()` -> `TouchTranslator.Mode`
- Отключение фокусного хайлайта в конструкторе: `setDefaultFocusHighlightEnabled(false)`.

## 3. DesktopEntries
- Все шаблоны `.desktop` в `reference/desktop-entries/` для Electron/Chromium содержат:
  `--no-sandbox --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage`
