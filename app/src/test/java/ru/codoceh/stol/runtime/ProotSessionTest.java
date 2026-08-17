package ru.codoceh.stol.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Шов 2: одна точка запуска, правильная командная строка, внятный отказ без образа. */
public class ProotSessionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File nativeDir;
    private File rootfs;
    private Recorder recorder;

    @Before
    public void setUp() throws IOException {
        File files = folder.newFolder("files");
        nativeDir = new File(files, "native");
        rootfs = new File(files, "rootfs");
        assertTrue(new File(nativeDir, "bin").mkdirs());
        assertTrue(rootfs.mkdirs());
        recorder = new Recorder();
    }

    private void markReady() throws IOException {
        assertTrue(new File(rootfs, ProotSession.READY_MARKER).createNewFile());
    }

    private ProotSession session() {
        return new ProotSession(nativeDir, rootfs, recorder);
    }

    @Test
    public void runsProotFromOurOwnNativeDir() throws IOException {
        markReady();
        session().exec("true", false);
        assertEquals(new File(nativeDir, "bin/proot").getAbsolutePath(), recorder.argv.get(0));
    }

    @Test
    public void bindsRootfsAndLandsInRoot() throws IOException {
        markReady();
        session().exec("true", false);
        assertEquals(rootfs.getAbsolutePath(), after("-r"));
        assertEquals("/root", after("-w"));
        assertTrue(recorder.argv.contains("--kill-on-exit"));
        assertTrue(recorder.argv.contains("-0"));
        assertTrue(recorder.argv.contains("/dev"));
        assertTrue(recorder.argv.contains("/proc"));
    }

    @Test
    public void commandStaysOneArgumentAndIsNotSplitOnAndroidSide() throws IOException {
        markReady();
        String command = "echo 'привет; rm -rf /' && /opt/stol/start-gui.sh";
        session().exec(command, false);

        List<String> argv = recorder.argv;
        assertEquals(command, argv.get(argv.size() - 1));
        assertEquals("-lc", argv.get(argv.size() - 2));
        assertEquals("/bin/sh", argv.get(argv.size() - 3));
        assertEquals("одна команда — один аргумент", 1, occurrences(argv, command));
    }

    @Test
    public void wipesAndroidEnvironmentBeforeEnteringImage() throws IOException {
        markReady();
        session().exec("true", false);
        List<String> argv = recorder.argv;
        int env = argv.indexOf("/usr/bin/env");
        assertTrue("окружение Android не отрезано", env > 0);
        assertEquals("-i", argv.get(env + 1));
        assertTrue(argv.contains("HOME=/root"));
    }

    @Test
    public void tellsTermuxBinariesWhereTheirLibrariesLive() throws IOException {
        markReady();
        session().exec("true", false);
        assertEquals(new File(nativeDir, "lib").getAbsolutePath(),
                recorder.env.get("LD_LIBRARY_PATH"));
        assertEquals(new File(nativeDir, "libexec/proot/loader").getAbsolutePath(),
                recorder.env.get("PROOT_LOADER"));
        assertTrue(recorder.env.containsKey("PROOT_TMP_DIR"));
    }

    @Test
    public void backgroundFlagReachesTheLauncher() throws IOException {
        markReady();
        session().exec("/opt/stol/start-gui.sh", true);
        assertTrue(recorder.background);
    }

    @Test
    public void withoutRootfsRefusesAndStartsNothing() {
        assertEquals(ProotSession.EXIT_NO_ROOTFS, session().exec("true", false));
        assertFalse("процесс запускать было нельзя", recorder.called);
    }

    @Test
    public void halfUnpackedImageCountsAsMissing() throws IOException {
        assertTrue(new File(rootfs, "usr").mkdirs());
        assertFalse(session().isRootfsReady());
        assertEquals(ProotSession.EXIT_NO_ROOTFS, session().exec("true", false));
        assertFalse(recorder.called);
    }

    @Test
    public void launchFailureIsReportedAsCode() throws IOException {
        markReady();
        ProotSession session = new ProotSession(nativeDir, rootfs, (argv, env, background) -> {
            throw new IOException("нет такого файла");
        });
        assertEquals(ProotSession.EXIT_LAUNCH_FAILED, session.exec("true", false));
    }

    @Test
    public void emptyCommandIsRejected() throws IOException {
        markReady();
        for (String command : new String[]{null, "", "   "}) {
            try {
                session().exec(command, false);
                fail("пустая команда должна отвергаться: " + command);
            } catch (IllegalArgumentException expected) {
                assertFalse(recorder.called);
            }
        }
    }

    private String after(String flag) {
        int at = recorder.argv.indexOf(flag);
        assertTrue("нет флага " + flag, at >= 0);
        return recorder.argv.get(at + 1);
    }

    private static int occurrences(List<String> argv, String value) {
        int count = 0;
        for (String item : argv) {
            if (item.equals(value)) {
                count++;
            }
        }
        return count;
    }

    private static final class Recorder implements ProcessLauncher {
        final List<String> argv = new ArrayList<>();
        final Map<String, String> env = new HashMap<>();
        boolean background;
        boolean called;

        @Override
        public int run(List<String> argv, Map<String, String> env, boolean background) {
            this.argv.clear();
            this.argv.addAll(argv);
            this.env.clear();
            this.env.putAll(env);
            this.background = background;
            this.called = true;
            return 0;
        }
    }
}
