package ru.codoceh.stol.setup;

import android.content.Context;

/**
 * Мастер первого запуска (R02): человек открыл приложение, нажал одну кнопку и через
 * несколько минут получил стол. Мастер ничего не рисует и ничего не запускает — он
 * переводит установку образа в состояния, а экран-хозяин их показывает.
 *
 * <p>Правила простоты (R18) держатся здесь устройством, а не уговором:
 *
 * <ul>
 *   <li>состояний четыре, на каждом не больше одного действия — {@link State#action()}
 *       возвращает одно значение, а не список, поэтому вторая кнопка на экране не
 *       появится даже по невнимательности;
 *   <li>путь от иконки до стола: нажали «Собрать» и ждали — стол открывается сам, потому
 *       что у {@link Phase#READY} действия нет;
 *   <li>слова человеческие: тексты живут в {@link SetupTexts}, здесь их нет вовсе.
 * </ul>
 *
 * <p>Сеть мастеру не нужна ни на одном шаге: образ лежит в самом приложении (R01.3).
 */
public final class SetupWizard {

    /** Что человек видит. Одна фаза — один экран. */
    public enum Phase {
        /** Стол не собран, ждём нажатия. */
        NOT_BUILT,
        /** Идёт сборка: показываем шаг и долю, молчать нельзя. */
        BUILDING,
        /** Стол собран — открывается сам, спрашивать нечего. */
        READY,
        /** Сборка не дошла до конца: названы причина и следующее действие. */
        FAILED
    }

    /** Что человек может нажать. Больше одного действия на состоянии не бывает. */
    public enum Action {
        /** Собрать стол. */
        BUILD,
        /** Попробовать снова после отказа. */
        RETRY
    }

    /** Состояние изменилось — перерисуйте экран. */
    public interface Listener {
        void onState(State state);
    }

    /** Сколько шагов в сборке: «Шаг 2 из 4». */
    public static final int TOTAL_STEPS = InstallProgress.Stage.values().length;

    /**
     * Границы долей шагов на общей шкале: SPACE занимает 0–2, VERIFY 2–10, UNPACK 10–99,
     * FINISH 99–100. Полосы неравные, потому что неравны и шаги: место считается мгновенно,
     * а разбор образа идёт минутами и съедает почти всё ожидание.
     *
     * <p>Процент один на всю сборку и берётся из байтов шага — числа, которое приходит от
     * распаковки. Отдельно от дела он расти не умеет: без {@link InstallProgress} ему
     * неоткуда взяться.
     */
    private static final int[] STAGE_BOUNDS = { 0, 2, 10, 99, 100 };

    private final RootfsInstaller installer;
    private final Listener listener;
    private volatile State state;
    private long stageTotal;

    /** Мастер рабочего приложения: образ из APK, каталог — тот же, что знает запуск. */
    public static SetupWizard forApp(Context context, Listener listener) {
        return new SetupWizard(RootfsInstaller.forApp(context), listener);
    }

    public SetupWizard(RootfsInstaller installer, Listener listener) {
        this.installer = installer;
        this.listener = listener;
        this.state = installer.isInstalled() ? State.ready() : State.notBuilt();
    }

    /** Что показывать прямо сейчас. */
    public State state() {
        return state;
    }

    /**
     * Собирает стол либо накладывает обновление. Идёт минутами — вызывайте с фонового потока; {@link Listener}
     * приходит с того же потока, на главный его перекладывает экран-хозяин.
     *
     * <p>Повторное нажатие во время сборки ничего не делает: второй распаковки быть не
     * должно, даже если экран не успел убрать кнопку.
     */
    public State build() {
        if (state.phase() == Phase.BUILDING) {
            return state;
        }
        set(State.building(InstallProgress.Stage.SPACE, 0));
        try {
            installer.update(progress);
            return set(State.ready());
        } catch (InstallFailedException failed) {
            return set(State.failed(failed.reason(),
                    failed.requiredBytes(), failed.availableBytes()));
        } catch (RuntimeException broke) {
            // Установщик объясняет свои отказы сам; всё остальное на телефоне — отказ
            // записи. Человеку в любом случае нужен текст с причиной и действием, а не
            // упавшее приложение: «неизвестная ошибка» запрещена (R18.5).
            return set(State.failed(InstallFailedException.Reason.DISK, 0L, 0L));
        }
    }

    private final InstallProgress progress = new InstallProgress() {

        @Override
        public void onStage(Stage stage, long totalBytes) {
            stageTotal = totalBytes;
            set(State.building(stage, STAGE_BOUNDS[stage.ordinal()]));
        }

        @Override
        public void onProgress(long doneBytes) {
            State current = state;
            if (current.phase() != Phase.BUILDING) {
                return;
            }
            int percent = percent(current.stage(), doneBytes);
            if (percent != current.percent()) {
                set(State.building(current.stage(), percent));
            }
        }

        private int percent(Stage stage, long doneBytes) {
            int from = STAGE_BOUNDS[stage.ordinal()];
            int to = STAGE_BOUNDS[stage.ordinal() + 1];
            if (stageTotal <= 0 || doneBytes <= 0) {
                // Шаг не про байты — например, переименование в конце. Показываем начало
                // его полосы: делить на ноль нечего, а число замирать не должно.
                return from;
            }
            // Образ разворачивается не байт в байт с заявленным размером, поэтому доля
            // упирается в верх полосы, а не уходит за неё.
            return from + (int) (Math.min(stageTotal, doneBytes) * (to - from) / stageTotal);
        }
    };

    /**
     * Ход установки, который мастер отдаёт установщику. Виден пакету, чтобы доля считалась
     * проверкой напрямую — в том числе на шаге, размер которого неизвестен.
     */
    InstallProgress progress() {
        return progress;
    }

    private State set(State next) {
        state = next;
        listener.onState(next);
        return next;
    }

    /** Снимок мастера: что показать и что предложить нажать. */
    public static final class State {

        private final Phase phase;
        private final InstallProgress.Stage stage;
        private final int percent;
        private final InstallFailedException.Reason reason;
        private final long requiredBytes;
        private final long availableBytes;

        private State(Phase phase, InstallProgress.Stage stage, int percent,
                      InstallFailedException.Reason reason,
                      long requiredBytes, long availableBytes) {
            this.phase = phase;
            this.stage = stage;
            this.percent = percent;
            this.reason = reason;
            this.requiredBytes = requiredBytes;
            this.availableBytes = availableBytes;
        }

        static State notBuilt() {
            return new State(Phase.NOT_BUILT, null, 0, null, 0L, 0L);
        }

        static State building(InstallProgress.Stage stage, int percent) {
            return new State(Phase.BUILDING, stage, percent, null, 0L, 0L);
        }

        static State ready() {
            // Сборка дошла до конца — число обязано это показать, а не замереть на 99.
            return new State(Phase.READY, null, 100, null, 0L, 0L);
        }

        static State failed(InstallFailedException.Reason reason,
                            long requiredBytes, long availableBytes) {
            return new State(Phase.FAILED, null, 0, reason, requiredBytes, availableBytes);
        }

        public Phase phase() {
            return phase;
        }

        /** Текущий шаг сборки; {@code null} вне сборки. */
        public InstallProgress.Stage stage() {
            return stage;
        }

        /** Номер шага для подписи «Шаг N из {@link #TOTAL_STEPS}»; 0 вне сборки. */
        public int stepNumber() {
            return stage == null ? 0 : stage.ordinal() + 1;
        }

        /** Доля всей сборки, 0..100: у собранного стола — сто. */
        public int percent() {
            return percent;
        }

        /** Единственное действие экрана; {@code null}, когда нажимать нечего. */
        public Action action() {
            switch (phase) {
                case NOT_BUILT:
                    return Action.BUILD;
                case FAILED:
                    return Action.RETRY;
                default:
                    // Во время сборки человек ждёт, после сборки стол открывается сам.
                    return null;
            }
        }

        /** Причина отказа; {@code null}, пока отказа нет. */
        public InstallFailedException.Reason reason() {
            return reason;
        }

        /** Сколько места нужно — словами, для текста отказа. */
        public String requiredHuman() {
            return InstallFailedException.human(requiredBytes);
        }

        /** Сколько места есть — словами, для текста отказа. */
        public String availableHuman() {
            return InstallFailedException.human(availableBytes);
        }
    }
}
