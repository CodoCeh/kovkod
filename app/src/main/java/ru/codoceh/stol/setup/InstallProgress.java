package ru.codoceh.stol.setup;

/**
 * Ход установки наружу: распаковка идёт минутами, и мастер обязан показывать её, а не
 * молчать. Подписи шагов — дело экрана, отсюда уходят только шаг и байты.
 */
public interface InstallProgress {

    enum Stage {
        /** Считаем, влезет ли развёрнутый образ. */
        SPACE,
        /** Сверяем сумму образа — до распаковки. */
        VERIFY,
        /** Разбираем образ в {@code files/rootfs.partial}. */
        UNPACK,
        /** Переименование и метка готовности. */
        FINISH
    }

    /**
     * Начался шаг.
     *
     * @param totalBytes сколько байт предстоит на этом шаге; 0, если шаг не про байты
     */
    void onStage(Stage stage, long totalBytes);

    /** Ход текущего шага: сколько байт уже пройдено. Растёт, не убывает. */
    void onProgress(long doneBytes);
}
