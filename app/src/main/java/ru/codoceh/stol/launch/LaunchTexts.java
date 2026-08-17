package ru.codoceh.stol.launch;

import ru.codoceh.stol.R;

/**
 * Слова запуска и ожидания стола (R03, R18.4, R18.5).
 *
 * <p>Соблюдение правил (по-русски, на «вы», без жаргона, путей и портов) проверяется тестом.
 */
public final class LaunchTexts {

    private LaunchTexts() {
    }

    public static int title(DesktopLauncher.State state) {
        switch (state.phase()) {
            case STARTING:
                return R.string.launch_starting_title;
            case WAITING_BEACON:
                return R.string.launch_waiting_title;
            case RUNNING:
                return R.string.launch_running_title;
            case FAILED:
                return R.string.launch_failed_title;
            default:
                return R.string.state_ready;
        }
    }

    public static int hint(DesktopLauncher.State state) {
        switch (state.phase()) {
            case STARTING:
                return R.string.launch_starting_hint;
            case WAITING_BEACON:
                return R.string.launch_waiting_hint;
            default:
                return 0;
        }
    }

    public static int action(DesktopLauncher.Action action) {
        switch (action) {
            case STOP:
                return R.string.launch_action_stop;
            case RETRY:
                return R.string.launch_action_retry;
            case ABOUT:
                return R.string.launch_action_about;
            default:
                return 0;
        }
    }

    public static int failureCause(DesktopLauncher.Failure reason) {
        switch (reason) {
            case TIMEOUT:
                return R.string.launch_error_timeout_cause;
            case NOT_INSTALLED:
                return R.string.launch_error_not_installed_cause;
            default:
                return R.string.launch_error_start_cause;
        }
    }

    public static int failureAction(DesktopLauncher.Failure reason) {
        switch (reason) {
            case TIMEOUT:
                return R.string.launch_error_timeout_action;
            case NOT_INSTALLED:
                return R.string.launch_error_not_installed_action;
            default:
                return R.string.launch_error_start_action;
        }
    }
}
