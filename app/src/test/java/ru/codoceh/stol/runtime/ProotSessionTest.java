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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Шов 2: одна точка запуска, правильная командная строка, внятный отказ без образа. */
public class ProotSessionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** То, что установщик пакетов кладёт в каталог родных библиотек. */
    private static final List<String> SHIPPED = Arrays.asList(
            "libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so");

    private File files;
    private File nativeDir;
    private File rootfs;
    private Recorder recorder;

    @Before
    public void setUp() throws IOException {
        files = folder.newFolder("files");
        // Каталог родных библиотек лежит вне данных приложения — так же и в тесте.
        nativeDir = folder.newFolder("lib", "arm64");
        rootfs = new File(files, "rootfs");
        for (String name : SHIPPED) {
            assertTrue(new File(nativeDir, name).createNewFile());
        }
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
    public void runsProotFromTheNativeLibraryDir() throws IOException {
        markReady();
        session().exec("true", false);
        assertEquals(new File(nativeDir, "libproot.so").getAbsolutePath(), recorder.argv.get(0));
    }

    /**
     * R19D.1: запускается только то, что лежит в каталоге библиотек. Из каталога данных
     * система запускать не даёт с Android 10 — там могут лежать только ссылки.
     */
    @Test
    public void nothingIsExecutedFromTheDataDirectory() throws IOException {
        markReady();
        session().exec("true", false);
        String data = files.getAbsolutePath();
        assertFalse("proot запускается из данных приложения", recorder.argv.get(0).startsWith(data));
        assertFalse("загрузчик лежит в данных приложения",
                recorder.env.get("PROOT_LOADER").startsWith(data));
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
        List<String> path = Arrays.asList(recorder.env.get("LD_LIBRARY_PATH").split(":"));
        assertTrue("библиотеки рядом с бинарником не в пути: " + path,
                path.contains(nativeDir.getAbsolutePath()));
        assertTrue("каталог версионных имён не в пути: " + path,
                path.contains(sonameDir().getAbsolutePath()));
        assertTrue(recorder.env.containsKey("PROOT_TMP_DIR"));
    }

    /** Загрузчик proot ищет рядом с собой, но имя файла в каталоге библиотек своё. */
    @Test
    public void loaderIsNamedAndFoundNextToProot() throws IOException {
        markReady();
        session().exec("true", false);
        File loader = new File(recorder.env.get("PROOT_LOADER"));
        assertEquals(new File(nativeDir, "libproot-loader.so").getAbsolutePath(),
                loader.getAbsolutePath());
        assertTrue("загрузчика нет там, куда указывает PROOT_LOADER", loader.isFile());
        assertEquals("загрузчик лежит не рядом с proot",
                new File(recorder.argv.get(0)).getParent(), loader.getParent());
    }

    /**
     * Версионный SONAME линкер ищет буквально, а в каталог библиотек попадают только имена
     * вида {@code lib*.so}. Ссылка даёт имя, файл при этом остаётся в каталоге библиотек.
     */
    @Test
    public void versionedSonameIsLinkedToTheShippedLibrary() throws IOException {
        markReady();
        session().exec("true", false);
        File link = new File(sonameDir(), "libtalloc.so.2");
        assertTrue("нет ссылки под версионным именем", Files.isSymbolicLink(link.toPath()));
        assertEquals(new File(nativeDir, "libtalloc.so").getAbsolutePath(),
                Files.readSymbolicLink(link.toPath()).toString());
    }

    /** Повторный запуск не спотыкается о ссылку, оставшуюся с прошлого раза. */
    @Test
    public void repeatedRunRefreshesTheLink() throws IOException {
        markReady();
        session().exec("true", false);
        session().exec("true", false);
        assertTrue(Files.isSymbolicLink(new File(sonameDir(), "libtalloc.so.2").toPath()));
    }

    @Test
    public void missingLibraryStopsTheLaunchInsteadOfStartingProot() throws IOException {
        markReady();
        assertTrue(new File(nativeDir, "libtalloc.so").delete());
        assertEquals(ProotSession.EXIT_LAUNCH_FAILED, session().exec("true", false));
        assertFalse("процесс запускать было нельзя", recorder.called);
    }

    private File sonameDir() {
        return new File(files, "soname");
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
