# Интерфейсы и швы

## 1. AndroidManifest.xml
- `MainActivity`: `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|density"`

## 2. MainActivity.java
- `launchWhenMeasured()`: немедленный старт при `width > 0 && height > 0`
- `onConfigurationChanged(Configuration newConfig)`: поддержка плавной смены геометрии

## 3. store/catalog.json & backend.py
- Позиции каталога: `code`, `claude`, `antigravity2`, `obsidian`, `zcode`
