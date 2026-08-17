package ru.codoceh.stol.apps;

/**
 * Чем кончилась установка одной программы. Состояний ровно три (шов 4), и «источник
 * ответил» среди них нет: пока программа не найдена на столе проверкой, она не стоит.
 */
public final class AppResult {

    public enum State {
        /** Программа на столе и найдена проверкой после установки. */
        INSTALLED,
        /** Не встала; причина названа. Стол при этом собран и работает. */
        FAILED,
        /** Отложена: интернета нет, а загрузить её неоткуда. Повтор пройдёт с сетью. */
        SKIPPED_OFFLINE
    }

    private final AppSpec app;
    private final State state;
    private final AppInstallException.Reason reason;
    private final String detail;

    private AppResult(AppSpec app, State state, AppInstallException.Reason reason, String detail) {
        this.app = app;
        this.state = state;
        this.reason = reason;
        this.detail = detail;
    }

    static AppResult installed(AppSpec app) {
        return new AppResult(app, State.INSTALLED, null, "");
    }

    static AppResult skipped(AppSpec app) {
        return new AppResult(app, State.SKIPPED_OFFLINE, null, "");
    }

    static AppResult failed(AppSpec app, AppInstallException failure) {
        return new AppResult(app, State.FAILED, failure.reason(), String.valueOf(failure.getMessage()));
    }

    public AppSpec app() {
        return app;
    }

    public State state() {
        return state;
    }

    /** Причина отказа; {@code null}, когда отказа нет. */
    public AppInstallException.Reason reason() {
        return reason;
    }

    /** Подробность для журнала: числа и коды, которые человеку на экране не нужны. */
    public String detail() {
        return detail;
    }

    /** Стоит ли программа. Ровно один способ спросить — чтобы не завелось второго. */
    public boolean isInstalled() {
        return state == State.INSTALLED;
    }
}
