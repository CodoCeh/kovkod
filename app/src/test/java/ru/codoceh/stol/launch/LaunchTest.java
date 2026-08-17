package ru.codoceh.stol.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ru.codoceh.stol.runtime.ProcessLauncher;
import ru.codoceh.stol.runtime.ProotSession;

/**
 * Проверка координатора запуска стола (R03, R05D, швы 2 и 3).
 */
public class LaunchTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private File nativeDir;
    private File rootfsDir;
    private File startResult;
    private File stopMark;
    private FakeLauncher launcher;
    private FakeBeaconPoller poller;
    private List<DesktopLauncher.State> states;

    @Before
    public void setUp() throws IOException {
        nativeDir = temp.newFolder("lib");
        rootfsDir = temp.newFolder("rootfs");
        temp.newFile("lib/libproot.so");
        temp.newFile("lib/libproot-loader.so");
        temp.newFile("lib/libtalloc.so");

        startResult = new File(rootfsDir, DesktopLauncher.START_RESULT.substring(1));
        stopMark = new File(rootfsDir, DesktopLauncher.SESSION_STOP.substring(1));
        assertTrue(startResult.getParentFile().mkdirs());
        launcher = new FakeLauncher(startResult);
        poller = new FakeBeaconPoller(startResult, stopMark);
        states = new ArrayList<>();
    }

    @Test
    public void launchFailsWhenRootfsNotReady() {
        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        DesktopLauncher.State state = desktop.launch();

        assertEquals(DesktopLauncher.Phase.FAILED, state.phase());
        assertEquals(DesktopLauncher.Failure.NOT_INSTALLED, state.failure());
        assertEquals(DesktopLauncher.Action.RETRY, state.action());
        assertEquals(0, launcher.runs.size());
        assertEquals(0, launcher.handles.size());
    }

    @Test
    public void launchStartsScriptAndTransitionsToRunningWhenBeaconAnswers() throws IOException {
        markReady();
        poller.answer = true;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        DesktopLauncher.State state = desktop.launch();

        assertEquals(DesktopLauncher.Phase.RUNNING, state.phase());
        assertNull(state.failure());
        assertEquals(DesktopLauncher.Action.STOP, state.action());

        assertTrue("start-gui.sh не запускался", launcher.ran(DesktopLauncher.START_SCRIPT));
        assertEquals(1, poller.polledPorts.size());
        assertEquals(Integer.valueOf(DesktopLauncher.BEACON_PORT), poller.polledPorts.get(0));

        assertEquals(3, states.size());
        assertEquals(DesktopLauncher.Phase.STARTING, states.get(0).phase());
        assertEquals(DesktopLauncher.Phase.WAITING_BEACON, states.get(1).phase());
        assertEquals(DesktopLauncher.Phase.RUNNING, states.get(2).phase());
    }

    /**
     * R05D.1: скрипт запуска отработал и вышел, а сессия осталась. Иначе с ней уходит всё,
     * что она подняла, — и стол умирает через несколько секунд после старта.
     */
    @Test
    public void sessionOutlivesTheStartScript() throws IOException {
        markReady();
        poller.answer = true;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        assertEquals(DesktopLauncher.Phase.RUNNING, desktop.launch().phase());

        assertEquals("сессия должна быть ровно одна", 1, launcher.handles.size());
        FakeHandle session = launcher.handles.get(0);
        assertTrue("сессия закрылась вместе со скриптом запуска", session.isAlive());
        assertFalse(session.closed);
    }

    /**
     * R05D.2: окно ожидания маячка открывается после того, как скрипт запуска отчитался,
     * а не по нажатию — иначе на медленном устройстве столу остаются секунды.
     */
    @Test
    public void beaconWindowOpensAfterTheStartScriptReports() throws IOException {
        markReady();
        poller.answer = true;
        // Отчёт появляется не сразу: сессия «работает» несколько кругов опроса.
        launcher.reportAfterAliveChecks = 2;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 7_000L, 5_000L,
                states::add);
        assertEquals(DesktopLauncher.Phase.RUNNING, desktop.launch().phase());

        FakeHandle session = launcher.handles.get(0);
        assertTrue("координатор не дождался скрипта запуска", session.aliveChecks >= 2);
        assertTrue("маячок опрашивали до отчёта скрипта", poller.sawReport);
        assertEquals("окно ожидания урезали временем скрипта",
                Long.valueOf(7_000L), poller.timeouts.get(0));
    }

    @Test
    public void launchCleansUpWhenBeaconTimesOut() throws IOException {
        markReady();
        poller.answer = false;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        DesktopLauncher.State state = desktop.launch();

        assertEquals(DesktopLauncher.Phase.FAILED, state.phase());
        assertEquals(DesktopLauncher.Failure.TIMEOUT, state.failure());
        assertEquals(DesktopLauncher.Action.RETRY, state.action());

        assertTrue("start-gui.sh не запускался", launcher.ran(DesktopLauncher.START_SCRIPT));
        assertTrue("stop-gui.sh не вызывался при тайм-ауте", launcher.ran(DesktopLauncher.STOP_SCRIPT));
        assertTrue("сессия осталась открытой после неудачи", launcher.handles.get(0).closed);
    }

    @Test
    public void launchFailsWhenStartScriptReturnsError() throws IOException {
        markReady();
        launcher.scriptCode = 1;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        DesktopLauncher.State state = desktop.launch();

        assertEquals(DesktopLauncher.Phase.FAILED, state.phase());
        assertEquals(DesktopLauncher.Failure.START_FAILED, state.failure());
        assertEquals(DesktopLauncher.Action.RETRY, state.action());
        assertFalse("опрос маячка пошёл после падения скрипта", poller.wasPolled());
        assertTrue("сессия осталась открытой после падения скрипта", launcher.handles.get(0).closed);
    }

    /** Сессия умерла, не дождавшись отчёта: ждать её дальше нечего. */
    @Test
    public void launchFailsWhenSessionDiesBeforeReporting() throws IOException {
        markReady();
        launcher.scriptCode = null;
        launcher.sessionDies = true;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, 5_000L, states::add);
        DesktopLauncher.State state = desktop.launch();

        assertEquals(DesktopLauncher.Phase.FAILED, state.phase());
        assertEquals(DesktopLauncher.Failure.START_FAILED, state.failure());
        assertFalse(poller.wasPolled());
    }

    /** Метка остановки прошлого раза закончила бы сессию, не дав ей начаться. */
    @Test
    public void staleStopMarkIsClearedBeforeLaunch() throws IOException {
        markReady();
        assertTrue(stopMark.createNewFile());
        poller.answer = true;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        assertEquals(DesktopLauncher.Phase.RUNNING, desktop.launch().phase());
        assertFalse("сессия стартовала с меткой остановки на месте", poller.sawStopMark);
    }

    /** Отчёт прошлого запуска не должен сойти за сегодняшний. */
    @Test
    public void staleStartResultIsNotTakenForThisRun() throws IOException {
        markReady();
        Files.write(startResult.toPath(), "0\n".getBytes(StandardCharsets.UTF_8));
        launcher.scriptCode = null;
        launcher.sessionDies = true;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, 5_000L, states::add);
        assertEquals(DesktopLauncher.Failure.START_FAILED, desktop.launch().failure());
    }

    @Test
    public void stopRunsStopScriptAndClosesTheSession() throws IOException {
        markReady();
        poller.answer = true;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        desktop.launch();
        DesktopLauncher.State stopped = desktop.stop();

        assertEquals(DesktopLauncher.Phase.STOPPED, stopped.phase());
        assertNull(stopped.action());
        assertTrue("stop-gui.sh не вызывался", launcher.ran(DesktopLauncher.STOP_SCRIPT));
        assertTrue("сессии не сказали заканчиваться", stopMark.isFile());
        assertTrue("сессия осталась жить после остановки", launcher.handles.get(0).closed);
    }

    /** Команда сессии обязана пережить скрипт запуска, иначе лечение только на словах. */
    @Test
    public void sessionCommandOutlivesTheStartScriptAndEndsOnTheStopMark() {
        assertTrue(DesktopLauncher.SESSION_COMMAND.startsWith(DesktopLauncher.START_SCRIPT));
        assertFalse("сессия заканчивается вместе со скриптом",
                DesktopLauncher.SESSION_COMMAND.trim().endsWith(DesktopLauncher.START_SCRIPT));
        assertTrue("сессия не отчитывается о коде возврата скрипта",
                DesktopLauncher.SESSION_COMMAND.contains(DesktopLauncher.START_RESULT));
        assertTrue("сессия не знает, когда ей заканчиваться",
                DesktopLauncher.SESSION_COMMAND.contains(DesktopLauncher.SESSION_STOP));
    }

    private void markReady() throws IOException {
        File marker = new File(rootfsDir, ProotSession.READY_MARKER);
        assertTrue(marker.createNewFile());
    }

    private ProotSession session() {
        return new ProotSession(nativeDir, rootfsDir, launcher);
    }

    /**
     * Подставная сессия образа: записывает отчёт скрипта запуска так же, как это делает
     * настоящая команда сессии, и остаётся живой, пока её не закрыли.
     */
    private static class FakeLauncher implements ProcessLauncher {
        final List<String> runs = new ArrayList<>();
        final List<String> sessions = new ArrayList<>();
        final List<FakeHandle> handles = new ArrayList<>();
        private final File startResult;

        /** С каким кодом отчитается скрипт запуска; {@code null} — не отчитается вовсе. */
        Integer scriptCode = 0;
        /** Через столько проверок «жива ли сессия» появится отчёт. */
        int reportAfterAliveChecks = 0;
        boolean sessionDies;

        FakeLauncher(File startResult) {
            this.startResult = startResult;
        }

        @Override
        public int run(List<String> argv, Map<String, String> env, boolean background) {
            runs.add(argv.get(argv.size() - 1));
            return 0;
        }

        @Override
        public Handle open(List<String> argv, Map<String, String> env) throws IOException {
            sessions.add(argv.get(argv.size() - 1));
            FakeHandle handle = new FakeHandle(sessionDies ? null : this);
            handles.add(handle);
            if (reportAfterAliveChecks == 0) {
                report();
            }
            return handle;
        }

        void report() throws IOException {
            if (scriptCode == null) {
                return;
            }
            startResult.getParentFile().mkdirs();
            Files.write(startResult.toPath(), (scriptCode + "\n").getBytes(StandardCharsets.UTF_8));
        }

        boolean ran(String fragment) {
            for (String cmd : runs) {
                if (cmd.contains(fragment)) {
                    return true;
                }
            }
            for (String cmd : sessions) {
                if (cmd.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class FakeHandle implements ProcessLauncher.Handle {
        private final FakeLauncher owner;
        boolean closed;
        int aliveChecks;

        FakeHandle(FakeLauncher owner) {
            this.owner = owner;
        }

        @Override
        public boolean isAlive() {
            aliveChecks++;
            if (owner == null) {
                return false;
            }
            if (!closed && aliveChecks >= owner.reportAfterAliveChecks
                    && owner.reportAfterAliveChecks > 0) {
                try {
                    owner.report();
                } catch (IOException failed) {
                    throw new IllegalStateException(failed);
                }
            }
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static class FakeBeaconPoller implements BeaconPoller {
        boolean answer = true;
        boolean sawReport;
        boolean sawStopMark;
        final List<Integer> polledPorts = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        private final File startResult;
        private final File stopMark;

        FakeBeaconPoller(File startResult, File stopMark) {
            this.startResult = startResult;
            this.stopMark = stopMark;
        }

        @Override
        public boolean waitForBeacon(int beaconPort, long timeoutMs) {
            polledPorts.add(beaconPort);
            timeouts.add(timeoutMs);
            sawReport = startResult.isFile();
            sawStopMark = stopMark.isFile();
            return answer;
        }

        boolean wasPolled() {
            return !polledPorts.isEmpty();
        }
    }
}
