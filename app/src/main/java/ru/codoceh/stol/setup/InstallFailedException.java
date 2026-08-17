package ru.codoceh.stol.setup;

import java.util.Locale;

/**
 * Установка образа не дошла до конца. Причина — машине, текст — человеку: мастер
 * показывает {@link #getMessage()} как есть.
 */
public final class InstallFailedException extends Exception {

    public enum Reason {
        /** Образа или его описания нет в APK, либо описание нечитаемо. */
        NO_IMAGE,
        /** Сумма образа разошлась с зашитой: файл повреждён (R11.2). */
        BROKEN_CHECKSUM,
        /** Свободного места меньше, чем нужно под развёрнутый образ (R02.5). */
        NOT_ENOUGH_SPACE,
        /** Сумма сошлась, но архив не разбирается. */
        BROKEN_ARCHIVE,
        /** Не удалось записать на диск. */
        DISK
    }

    private final Reason reason;
    private final long requiredBytes;
    private final long availableBytes;

    InstallFailedException(Reason reason, String message) {
        this(reason, message, null, 0L, 0L);
    }

    InstallFailedException(Reason reason, String message, Throwable cause) {
        this(reason, message, cause, 0L, 0L);
    }

    private InstallFailedException(Reason reason, String message, Throwable cause,
                                   long requiredBytes, long availableBytes) {
        super(message, cause);
        this.reason = reason;
        this.requiredBytes = requiredBytes;
        this.availableBytes = availableBytes;
    }

    /** Нехватка места называется числом: сколько нужно и сколько есть. */
    static InstallFailedException notEnoughSpace(long requiredBytes, long availableBytes) {
        String message = "Не хватает места: нужно " + human(requiredBytes)
                + ", свободно " + human(availableBytes);
        return new InstallFailedException(
                Reason.NOT_ENOUGH_SPACE, message, null, requiredBytes, availableBytes);
    }

    public Reason reason() {
        return reason;
    }

    /** Сколько байт нужно под развёрнутый образ; 0 для остальных причин. */
    public long requiredBytes() {
        return requiredBytes;
    }

    /** Сколько байт свободно; 0 для остальных причин. */
    public long availableBytes() {
        return availableBytes;
    }

    static String human(long bytes) {
        String[] units = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        String number = unit == 0
                ? String.valueOf(bytes)
                : String.format(Locale.forLanguageTag("ru"), "%.1f", value);
        return number + " " + units[unit];
    }
}
