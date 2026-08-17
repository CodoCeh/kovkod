package ru.codoceh.stol.launch;

import java.util.Objects;

import ru.codoceh.stol.runtime.ProotSession;

/**
 * Координатор запуска и остановки стола (R03, швы 2 и 3).
 *
 * <p>Поднимает службы стола через {@link ProotSession#exec(String, boolean)}, ждёт сигнала
 * готовности от маячка на порту 5910 (R03.3) и только после этого отдаёт сигнал подключиться
 * к RFB-порту 5901. При остановке гасит сессию стола и освобождает ресурсы (R03.4).
 */
public final class DesktopLauncher {

    public static final int RFB_PORT = 5901;
    public static final int BEACON_PORT = 5910;
    public static final long DEFAULT_BEACON_TIMEOUT_MS = 30_000L;

    public static final String START_SCRIPT = "/opt/stol/start-gui.sh";
    public static final String STOP_SCRIPT = "/opt/stol/stop-gui.sh";

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
    private final Listener listener;
    private volatile State state;

    public DesktopLauncher(ProotSession session, Listener listener) {
        this(session, BeaconPoller.SYSTEM, DEFAULT_BEACON_TIMEOUT_MS, listener);
    }

    public DesktopLauncher(ProotSession session, BeaconPoller beaconPoller,
                           long beaconTimeoutMs, Listener listener) {
        this.session = Objects.requireNonNull(session, "session");
        this.beaconPoller = Objects.requireNonNull(beaconPoller, "beaconPoller");
        this.beaconTimeoutMs = beaconTimeoutMs;
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
        int code = session.exec(START_SCRIPT, true);
        if (code != 0) {
            return set(State.failed(Failure.START_FAILED));
        }

        set(State.waitingBeacon());
        boolean ready = beaconPoller.waitForBeacon(BEACON_PORT, beaconTimeoutMs);
        if (!ready) {
            // Если маячок не открылся, гасим процессы
            session.exec(STOP_SCRIPT, false);
            return set(State.failed(Failure.TIMEOUT));
        }

        return set(State.running());
    }

    /**
     * Останавливает стол.
     */
    public State stop() {
        set(State.stopping());
        session.exec(STOP_SCRIPT, false);
        return set(State.stopped());
    }

    private State set(State next) {
        this.state = next;
        if (listener != null) {
            listener.onState(next);
        }
        return next;
    }
}
