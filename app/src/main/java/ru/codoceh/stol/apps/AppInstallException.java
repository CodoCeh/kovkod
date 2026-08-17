package ru.codoceh.stol.apps;

/**
 * Программа не встала. Причина — машине, текст — в журнал: человеку слова подбирает
 * {@link AppsTexts}, а не это сообщение.
 */
public final class AppInstallException extends Exception {

    public enum Reason {
        /** Источник не ответил или ответил отказом: адрес устарел, сайт лежит, доступа нет. */
        NO_SOURCE,
        /** Связь оборвалась посреди загрузки: файл пришёл короче обещанного. */
        BROKEN_LINK,
        /** Файл загружен, но установка внутри стола вернула ошибку. */
        INSTALL_REFUSED,
        /**
         * Установка отработала без ошибки, а программы на столе нет. Отдельная причина
         * потому, что ровно здесь ломается самообман «источник ответил — значит стоит».
         */
        NOT_INSTALLED
    }

    private final Reason reason;

    AppInstallException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    AppInstallException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
