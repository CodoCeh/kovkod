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
     * Метка «сессии пора заканчиваться». Её кладёт приложение, а видит сама сессия.
     *
     * <p>Снаружи сессию не снять: сигнал убивает только её ведущий процесс, а всё, что он
     * вёл, остаётся сиротами и продолжает работать. Изнутри же сессия заканчивается по
     * своей воле — и тогда за ней действительно не остаётся ничего.
     */
    static final String SESSION_STOP = "/run/stol/stop";

    /**
     * Команда сессии стола: отработать скрипт запуска, отчитаться кодом возврата и жить
     * дальше, пока не появится метка остановки. Последнее и есть лечение (R05D.1) — без
     * него сессия заканчивается вместе со скриптом и уносит с собой весь стол.
     */
    static final String SESSION_COMMAND =
            START_SCRIPT + "; printf '%s\\n' \"$?\" > " + START_RESULT
                    + "; mkfifo /run/stol/keepalive 2>/dev/null; exec /bin/bash -c 'while [ ! -e " + SESSION_STOP
                    + " ]; do read -t 2 <> /run/stol/keepalive; done'";

    /**
     * Команда сессии с назначенным размером стола и масштабом его интерфейса. X-сервер
     * поднимается ровно в тех точках, в которых стол потом показан на экране: одна точка
     * стола — одна точка экрана, растягивать нечего и интерполировать нечем. Раньше размер
     * был жёстким (1600×1000 в самом {@code start-gui.sh}), картинка не ложилась на сетку
     * экрана ни по одной оси, и её вписывали с интерполяцией — отсюда бледные тона и
     * размытые границы. Размер вида в точках и плотность экрана знает только приложение,
     * поэтому оттуда они и едут — см. {@link DesktopFit}.
     *
     * <p>Присваивания перед командой видит ровно первая простая команда — сам скрипт
     * запуска; он читает {@code ${STOL_GEOMETRY:-…}} и {@code ${STOL_UI_SCALE:-…}}.
     *
     * @param fit размер и масштаб; {@code null} — без назначений, скрипт возьмёт свои
     *            значения по умолчанию
     */
    static String sessionCommand(DesktopFit fit) {
        return sessionCommand(fit, false, 0);
    }

    /**
     * То же самое, и сверх размера — необязательные ограничения из настроек стола
     * (R-настройки: разрешение, масштаб, процессор, память).
     *
     * <p>Потолок памяти — {@code ulimit -v} самой сессии до запуска скрипта: жёсткая
     * граница адресного пространства, которую видят все процессы стола (ulimit —
     * встроенная команда шелла, действует на текущий процесс и наследуется всем, что он
     * запускает). Превысил — процесс внутри стола падает с ошибкой памяти, а не просто
     * тормозит; настоящего мягкого лимита без root здесь нет.
     *
     * <p>Экономия процессора — {@code nice -n 10} перед самим скриптом запуска: не доля
     * процессора, а более низкий приоритет очереди планировщика, который наследуют все
     * порождённые скриптом процессы — вызов идёт настоящему ядру устройства, в обход
     * подмены путей внутри образа.
     *
     * @param economizeCpu понизить приоритет очереди всей сессии стола
     * @param ramLimitMb   потолок адресного пространства в мегабайтах; {@code 0} — без
     *                     потолка
     */
    static String sessionCommand(DesktopFit fit, boolean economizeCpu, int ramLimitMb) {
        // export, не «VAR=знач команда»: следующей простой командой может стать ulimit —
        // это встроенная команда шелла, а присваивание перед встроенной командой шелл
        // держит только на время её вызова и не проносит дальше по «;». export переживает
        // и её, и всё, что идёт после.
        StringBuilder prefix = new StringBuilder();
        if (fit != null) {
            prefix.append("export STOL_GEOMETRY=").append(fit.geometry()).append("; ");
            prefix.append("export STOL_UI_SCALE=").append(fit.uiScale()).append("; ");
        }
        if (ramLimitMb > 0) {
            prefix.append("ulimit -v ").append(ramLimitMb * 1024).append("; ");
        }
        String command = SESSION_COMMAND;
        if (economizeCpu) {
            command = "nice -n 10 " + command;
        }
        return prefix + command;
    }

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
     * Запускает стол в размере по умолчанию. Размер вида приложение знает не всегда —
     * например, в проверках без экрана.
     */
    public State launch() {
        return launch(null);
    }

    /**
     * Запускает стол размером ровно в вид, в котором он будет показан, и ожидает маячка
     * готовности. Должен вызываться из рабочего потока.
     *
     * @param fit размер вида и масштаб интерфейса; {@code null} — значения по умолчанию
     */
    public State launch(DesktopFit fit) {
        return launch(fit, false, 0);
    }

    /**
     * То же самое, и сверх размера — настройки стола: экономия процессора и потолок
     * памяти (см. {@link #sessionCommand(DesktopFit, boolean, int)}).
     */
    public State launch(DesktopFit fit, boolean economizeCpu, int ramLimitMb) {
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
        // не дождавшись ничего. А метка остановки прошлого раза закончила бы сессию,
        // не дав ей и начаться.
        result.delete();
        session.inside(SESSION_STOP).delete();

        ProcessLauncher.Handle opened = session.open(sessionCommand(fit, economizeCpu, ramLimitMb));
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
     * Динамически меняет разрешение X11 стола через xrandr под текущую геометрию экрана
     * (поворот экрана, раскладывание/складывание Fold). Выполняется в сессии стола без перезапуска.
     */
    public void resize(int width, int height) {
        if (state.phase() != Phase.RUNNING || width <= 0 || height <= 0) {
            return;
        }
        String mode = width + "x" + height;
        String cmd = "DISPLAY=:1 xrandr --newmode \"" + mode + "\" 0 "
                + width + " " + width + " " + width + " " + width + " "
                + height + " " + height + " " + height + " " + height + " 2>/dev/null; "
                + "DISPLAY=:1 xrandr --addmode VNC-0 \"" + mode + "\" 2>/dev/null; "
                + "DISPLAY=:1 xrandr --output VNC-0 --mode \"" + mode + "\"";
        session.exec(cmd, false);
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
        try {
            session.inside(SESSION_STOP).createNewFile();
        } catch (IOException unwritable) {
            // Метку не положить — сессия доживёт до конца приложения, но стол уже погашен.
        }
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
