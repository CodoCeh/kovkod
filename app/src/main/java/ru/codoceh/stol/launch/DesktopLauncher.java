package ru.codoceh.stol.launch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Objects;

import ru.codoceh.stol.runtime.ProcessLauncher;
import ru.codoceh.stol.runtime.ProotSession;

/**
 * Координатор запуска и остановки стола (R03, швы 2 и 3).
 *
 * <p>Поднимает службы стола в живой сессии образа, ждёт сигнала готовности от маячка на
 * порту 5910 (R03.3) и только после этого отдаёт сигнал подключиться к RFB-порту 5901.
 * При остановке гасит стол и закрывает сессию (R03.4).
 *
 * <p>Сессию приложение держит само (R05D.1). Скрипт запуска возвращает управление за
 * 5–17 секунд, и раньше стол умирал вместе с ним: сессия образа выходила следом и уносила
 * Xtigervnc, XFCE и маячок. Теперь корневой процесс сессии переживает скрипт и живёт,
 * пока стол не остановили.
 */
public final class DesktopLauncher {

    public static final int RFB_PORT = 5901;
    public static final int BEACON_PORT = 5910;
    /** Столько же ждёт и сам маячок: раньше него сдаваться приложению незачем. */
    public static final long DEFAULT_BEACON_TIMEOUT_MS = 180_000L;
    /** Скрипт запуска идёт 5–17 секунд на живом телефоне; запас на медленное устройство. */
    public static final long DEFAULT_START_TIMEOUT_MS = 180_000L;

    public static final String START_SCRIPT = "/opt/stol/start-gui.sh";
    public static final String STOP_SCRIPT = "/opt/stol/stop-gui.sh";

    /**
     * Куда сессия кладёт код возврата скрипта запуска. Отсюда считается окно ожидания
     * маячка (R05D.2): пока скрипт не отработал, ждать стол ещё рано, и его собственные
     * секунды не должны съедать чужое время.
     */
    static final String START_RESULT = "/run/stol/start.rc";

    /**
     * Команда сессии стола: отработать скрипт запуска, отчитаться кодом возврата и
     * остаться жить. Последнее и есть лечение (R05D.1) — без него сессия выходит вместе
     * со скриптом, а proot с {@code --kill-on-exit} уносит за собой весь стол.
     */
    static final String SESSION_COMMAND =
            START_SCRIPT + "; printf '%s\\n' \"$?\" > " + START_RESULT + "; exec sleep infinity";

    private static final long POLL_MS = 200L;

    public enum Phase {
        IDLE,
        STARTING,
        WAITING_BEACON,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public enum Action {
        STOP,
        RETRY,
        ABOUT
    }

    public enum Failure {
        NOT_INSTALLED,
        START_FAILED,
        TIMEOUT
    }

    public static final class State {
        private final Phase phase;
        private final Failure failure;

        private State(Phase phase, Failure failure) {
            this.phase = Objects.requireNonNull(phase, "phase");
            this.failure = failure;
        }

        public static State idle() {
            return new State(Phase.IDLE, null);
        }

        public static State starting() {
            return new State(Phase.STARTING, null);
        }

        public static State waitingBeacon() {
            return new State(Phase.WAITING_BEACON, null);
        }

        public static State running() {
            return new State(Phase.RUNNING, null);
        }

        public static State stopping() {
            return new State(Phase.STOPPING, null);
        }

        public static State stopped() {
            return new State(Phase.STOPPED, null);
        }

        public static State failed(Failure failure) {
            return new State(Phase.FAILED, Objects.requireNonNull(failure, "failure"));
        }

        public Phase phase() {
            return phase;
        }

        public Failure failure() {
            return failure;
        }

        public Action action() {
            switch (phase) {
                case STARTING:
                case WAITING_BEACON:
                case RUNNING:
                    return Action.STOP;
                case FAILED:
                    return Action.RETRY;
                default:
                    return null;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof State)) return false;
            State state = (State) o;
            return phase == state.phase && failure == state.failure;
        }

        @Override
        public int hashCode() {
            return Objects.hash(phase, failure);
        }

        @Override
        public String toString() {
            return failure == null ? phase.name() : phase.name() + "(" + failure + ")";
        }
    }

    public interface Listener {
        void onState(State state);
    }

    private final ProotSession session;
    private final BeaconPoller beaconPoller;
    private final long beaconTimeoutMs;
    private final long startTimeoutMs;
    private final Listener listener;
    private volatile State state;
    private volatile ProcessLauncher.Handle desktop;

    public DesktopLauncher(ProotSession session, Listener listener) {
        this(session, BeaconPoller.SYSTEM, DEFAULT_BEACON_TIMEOUT_MS, DEFAULT_START_TIMEOUT_MS,
                listener);
    }

    public DesktopLauncher(ProotSession session, BeaconPoller beaconPoller,
                           long beaconTimeoutMs, Listener listener) {
        this(session, beaconPoller, beaconTimeoutMs, DEFAULT_START_TIMEOUT_MS, listener);
    }

    public DesktopLauncher(ProotSession session, BeaconPoller beaconPoller,
                           long beaconTimeoutMs, long startTimeoutMs, Listener listener) {
        this.session = Objects.requireNonNull(session, "session");
        this.beaconPoller = Objects.requireNonNull(beaconPoller, "beaconPoller");
        this.beaconTimeoutMs = beaconTimeoutMs;
        this.startTimeoutMs = startTimeoutMs;
        this.listener = listener;
        this.state = State.idle();
    }

    public State state() {
        return state;
    }

    /**
     * Запускает стол и ожидает маячка готовности. Должен вызываться из рабочего потока.
     */
    public State launch() {
        if (state.phase() == Phase.STARTING || state.phase() == Phase.WAITING_BEACON
                || state.phase() == Phase.RUNNING) {
            return state;
        }
        if (!session.isRootfsReady()) {
            return set(State.failed(Failure.NOT_INSTALLED));
        }

        set(State.starting());
        File result = session.inside(START_RESULT);
        // Отчёт прошлого запуска — не отчёт этого: иначе окно ожидания открылось бы,
        // не дождавшись ничего.
        result.delete();

        ProcessLauncher.Handle opened = session.open(SESSION_COMMAND);
        if (opened == null) {
            return set(State.failed(Failure.START_FAILED));
        }
        desktop = opened;

        if (awaitStartScript(opened, result) != 0) {
            release();
            return set(State.failed(Failure.START_FAILED));
        }

        // Скрипт отработал — только теперь начинается окно ожидания стола (R05D.2).
        set(State.waitingBeacon());
        boolean ready = beaconPoller.waitForBeacon(BEACON_PORT, beaconTimeoutMs);
        if (!ready) {
            release();
            return set(State.failed(Failure.TIMEOUT));
        }

        return set(State.running());
    }

    /**
     * Останавливает стол: гасит его службы по-хорошему и закрывает сессию, чтобы за ней
     * не осталось ни одного процесса образа.
     */
    public State stop() {
        set(State.stopping());
        release();
        return set(State.stopped());
    }

    /**
     * Ждёт, пока скрипт запуска отчитается кодом возврата.
     *
     * @return код возврата скрипта; ненулевой, если сессия умерла или отчёта не дождались
     */
    private int awaitStartScript(ProcessLauncher.Handle opened, File result) {
        long deadline = System.currentTimeMillis() + startTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String reported = read(result);
            if (reported != null) {
                try {
                    return Integer.parseInt(reported);
                } catch (NumberFormatException unreadable) {
                    return 1;
                }
            }
            if (!opened.isAlive()) {
                return 1;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
        return 1;
    }

    /** Отчёт скрипта, либо {@code null} пока его нет или он записан не до конца. */
    private static String read(File result) {
        try {
            String text = new String(Files.readAllBytes(result.toPath()), StandardCharsets.UTF_8);
            return text.trim().isEmpty() ? null : text.trim();
        } catch (IOException notYet) {
            return null;
        }
    }

    private void release() {
        session.exec(STOP_SCRIPT, false);
        ProcessLauncher.Handle opened = desktop;
        desktop = null;
        if (opened != null) {
            opened.close();
        }
    }

    private State set(State next) {
        this.state = next;
        if (listener != null) {
            listener.onState(next);
        }
        return next;
    }
}
