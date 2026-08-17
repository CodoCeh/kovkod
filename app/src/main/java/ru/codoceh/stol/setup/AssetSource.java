package ru.codoceh.stol.setup;

import java.io.IOException;
import java.io.InputStream;

/**
 * Откуда берётся образ (шов 1). Отдельный шов ровно для того, чтобы установщик
 * проверялся без устройства: в тестах вместо assets подставляется байтовый массив.
 */
public interface AssetSource {

    /** Открывает поток на файл в assets. Поток закрывает вызывающий. */
    InputStream open(String name) throws IOException;
}
