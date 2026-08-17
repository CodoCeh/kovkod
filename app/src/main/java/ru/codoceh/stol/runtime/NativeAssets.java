package ru.codoceh.stol.runtime;

import android.content.Context;

import java.io.File;

/**
 * Где лежат родные бинарники и образ.
 *
 * <p>Бинарники едут в APK как {@code lib*.so} и попадают в каталог родных библиотек:
 * оттуда система разрешает запуск при любом {@code targetSdk}, а из каталога данных
 * приложения — нет (правило W^X, Android 10 и новее). Раскладывать и раздавать права
 * нечего: это делает установщик пакетов, а каталог библиотек нам недоступен на запись.
 *
 * <p>Откуда взят каждый файл и под какой лицензией — {@code assets/native/SOURCES.md}.
 */
public final class NativeAssets {

    private NativeAssets() {
    }

    /**
     * Каталог родных библиотек. Путь меняется при каждом обновлении APK, поэтому его
     * спрашивают у системы перед запуском, а не сохраняют.
     */
    public static File nativeDir(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir);
    }

    public static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }
}
