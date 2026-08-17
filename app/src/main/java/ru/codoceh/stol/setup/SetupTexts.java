package ru.codoceh.stol.setup;

import ru.codoceh.stol.R;

/**
 * Слова мастера. Единственное место, где состояние превращается в текст: экран-хозяин
 * берёт строки отсюда и ничего не сочиняет сам, иначе жаргон вернётся правкой через
 * месяц.
 *
 * <p>Правила текста — из R18.4 и R18.5: по-русски, на «вы», без слов про устройство
 * внутренностей, и у каждого отказа названы причина и следующее действие. Соблюдение
 * проверяется тестом по строковым ресурсам, а не на глаз.
 */
public final class SetupTexts {

    private SetupTexts() {
    }

    /** Заголовок экрана. */
    public static int title(SetupWizard.Phase phase) {
        switch (phase) {
            case NOT_BUILT:
                return R.string.state_not_built;
            case BUILDING:
                return R.string.setup_building_title;
            case READY:
                return R.string.state_ready;
            default:
                return R.string.setup_failed_title;
        }
    }

    /**
     * Подпись под заголовком; 0 для отказа — там подпись складывается из
     * {@link #failureCause} и {@link #failureAction}.
     */
    public static int hint(SetupWizard.Phase phase) {
        switch (phase) {
            case NOT_BUILT:
                return R.string.setup_not_built_hint;
            case BUILDING:
                return R.string.setup_building_hint;
            case READY:
                return R.string.setup_ready_hint;
            default:
                return 0;
        }
    }

    /** Подпись текущего шага сборки: человек ждёт минуты и должен видеть, что происходит. */
    public static int step(InstallProgress.Stage stage) {
        switch (stage) {
            case SPACE:
                return R.string.setup_step_space;
            case VERIFY:
                return R.string.setup_step_verify;
            case UNPACK:
                return R.string.setup_step_unpack;
            default:
                return R.string.setup_step_finish;
        }
    }

    /** «Шаг 1 из 4» — подставьте номер шага и {@link SetupWizard#TOTAL_STEPS}. */
    public static int stepCounter() {
        return R.string.setup_step_counter;
    }

    /**
     * Число рядом с кузницей: сколько сборки уже позади. Подставьте
     * {@link SetupWizard.State#percent()} — человек ждёт минуты и должен видеть, сколько
     * осталось, а не только то, что работа идёт.
     */
    public static int percent() {
        return R.string.setup_percent;
    }

    /** Надпись на единственной кнопке экрана. */
    public static int action(SetupWizard.Action action) {
        return action == SetupWizard.Action.BUILD
                ? R.string.setup_action_build
                : R.string.setup_action_retry;
    }

    /**
     * Почему стол не собрался. Подставляйте {@link SetupWizard.State#requiredHuman()} и
     * {@link SetupWizard.State#availableHuman()}: числа нужны нехватке места, остальные
     * причины лишние подстановки не берут.
     */
    public static int failureCause(InstallFailedException.Reason reason) {
        switch (reason) {
            case NO_IMAGE:
                return R.string.setup_error_no_image_cause;
            case BROKEN_CHECKSUM:
                return R.string.setup_error_checksum_cause;
            case NOT_ENOUGH_SPACE:
                return R.string.setup_error_space_cause;
            case BROKEN_ARCHIVE:
                return R.string.setup_error_archive_cause;
            default:
                return R.string.setup_error_disk_cause;
        }
    }

    /** Что человеку сделать дальше. Без этой строки отказ показывать нельзя. */
    public static int failureAction(InstallFailedException.Reason reason) {
        switch (reason) {
            case NO_IMAGE:
                return R.string.setup_error_no_image_action;
            case BROKEN_CHECKSUM:
                return R.string.setup_error_checksum_action;
            case NOT_ENOUGH_SPACE:
                return R.string.setup_error_space_action;
            case BROKEN_ARCHIVE:
                return R.string.setup_error_archive_action;
            default:
                return R.string.setup_error_disk_action;
        }
    }
}
