package ru.codoceh.stol.apps;

/**
 * Ход установки программ наружу. Загрузка идёт минутами, и экран обязан показывать её, а
 * не молчать — как и при сборке стола. Подписи — дело экрана, отсюда уходят программа,
 * её номер и байты.
 */
public interface AppsProgress {

    /** Взялись за программу: «Ставим 3-ю из 7». */
    void onApp(AppSpec app, int number, int total);

    /**
     * Ход загрузки текущей программы.
     *
     * @param totalBytes сколько всего байт обещал источник; 0, если не сказал
     */
    void onDownload(long doneBytes, long totalBytes);

    /** Программа кончилась одним из трёх состояний. */
    void onResult(AppResult result);
}
