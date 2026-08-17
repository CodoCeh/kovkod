package ru.codoceh.stol.apps;

import ru.codoceh.stol.R;

/**
 * Слова про программы. Единственное место, где состояние установки превращается в текст:
 * экран берёт строки отсюда и ничего не сочиняет сам.
 *
 * <p>Правила те же, что у мастера (R18.4 и R18.5): по-русски, на «вы», без слов про
 * устройство внутренностей, и у каждого отказа названы причина и следующее действие.
 * Соблюдение проверяется тестом по строковым ресурсам.
 */
public final class AppsTexts {

    private AppsTexts() {
    }

    /** Как называется итог рядом с программой в списке. */
    public static int state(AppResult.State state) {
        switch (state) {
            case INSTALLED:
                return R.string.apps_state_installed;
            case SKIPPED_OFFLINE:
                return R.string.apps_state_skipped;
            default:
                return R.string.apps_state_failed;
        }
    }

    /** Надпись на кнопке повторной установки — единственное действие рядом с программой. */
    public static int action() {
        return R.string.apps_action_install;
    }

    /** Почему программа не встала. */
    public static int failureCause(AppInstallException.Reason reason) {
        switch (reason) {
            case NO_SOURCE:
                return R.string.apps_error_source_cause;
            case BROKEN_LINK:
                return R.string.apps_error_link_cause;
            case NOT_INSTALLED:
                return R.string.apps_error_missing_cause;
            default:
                return R.string.apps_error_refused_cause;
        }
    }

    /** Что человеку сделать дальше. Без этой строки отказ показывать нельзя. */
    public static int failureAction(AppInstallException.Reason reason) {
        switch (reason) {
            case NO_SOURCE:
                return R.string.apps_error_source_action;
            case BROKEN_LINK:
                return R.string.apps_error_link_action;
            case NOT_INSTALLED:
                return R.string.apps_error_missing_action;
            default:
                return R.string.apps_error_refused_action;
        }
    }

    /** Почему программа отложена: это не отказ, а честное «пока неоткуда взять». */
    public static int skippedCause() {
        return R.string.apps_skipped_cause;
    }

    /** Что сделать, чтобы отложенная программа встала. */
    public static int skippedAction() {
        return R.string.apps_skipped_action;
    }
}
