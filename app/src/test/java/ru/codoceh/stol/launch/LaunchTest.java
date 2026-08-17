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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ru.codoceh.stol.runtime.ProcessLauncher;
import ru.codoceh.stol.runtime.ProotSession;

/**
 * Проверка координатора запуска стола (R03, швы 2 и 3).
 */
public class LaunchTest {

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private File nativeDir;
    private File rootfsDir;
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

        launcher = new FakeLauncher();
        poller = new FakeBeaconPoller();
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
    }

    @Test
    public void launchFailsWhenStartScriptReturnsError() throws IOException {
        markReady();
        launcher.failNext(1);

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        DesktopLauncher.State state = desktop.launch();

        assertEquals(DesktopLauncher.Phase.FAILED, state.phase());
        assertEquals(DesktopLauncher.Failure.START_FAILED, state.failure());
        assertEquals(DesktopLauncher.Action.RETRY, state.action());
        assertFalse("опрос маячка пошёл после падения скрипта", poller.wasPolled());
    }

    @Test
    public void stopRunsStopScriptAndTransitionsToStopped() throws IOException {
        markReady();
        poller.answer = true;

        DesktopLauncher desktop = new DesktopLauncher(session(), poller, 1000L, states::add);
        desktop.launch();
        DesktopLauncher.State stopped = desktop.stop();

        assertEquals(DesktopLauncher.Phase.STOPPED, stopped.phase());
        assertNull(stopped.action());
        assertTrue("stop-gui.sh не вызывался", launcher.ran(DesktopLauncher.STOP_SCRIPT));
    }

    private void markReady() throws IOException {
        File marker = new File(rootfsDir, ProotSession.READY_MARKER);
        assertTrue(marker.createNewFile());
    }

    private ProotSession session() {
        return new ProotSession(nativeDir, rootfsDir, launcher);
    }

    private static class FakeLauncher implements ProcessLauncher {
        final List<String> runs = new ArrayList<>();
        private int nextCode = 0;

        void failNext(int code) {
            this.nextCode = code;
        }

        @Override
        public int run(List<String> argv, Map<String, String> env, boolean background) {
            String cmd = argv.get(argv.size() - 1);
            runs.add(cmd);
            int code = nextCode;
            nextCode = 0;
            return code;
        }

        boolean ran(String fragment) {
            for (String cmd : runs) {
                if (cmd.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class FakeBeaconPoller implements BeaconPoller {
        boolean answer = true;
        final List<Integer> polledPorts = new ArrayList<>();

        @Override
        public boolean waitForBeacon(int beaconPort, long timeoutMs) {
            polledPorts.add(beaconPort);
            return answer;
        }

        boolean wasPolled() {
            return !polledPorts.isEmpty();
        }
    }
}
