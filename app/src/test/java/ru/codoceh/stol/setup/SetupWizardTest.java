package ru.codoceh.stol.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.luben.zstd.ZstdOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.codoceh.stol.runtime.ProotSession;

/**
 * Задача 07: мастер первого запуска. Проверяется то, что видит человек — состояния и
 * переходы между ними, честный ход сборки, счётный путь до стола (R18.2) и отказы,
 * названные причиной и действием (R18.5).
 *
 * <p>Установщик берётся настоящий: образ собирается тем же zstd, что распаковывает
 * приложение, а место на диске подставляется швом. Сети в тесте нет и быть не может —
 * ровно так же, как у человека в самолёте (R01.3).
 */
public class SetupWizardTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File rootfs;
    private FakeAssets assets;
    private Journal seen;

    @Before
    public void setUp() throws IOException {
        rootfs = new File(folder.newFolder("files"), "rootfs");
        assets = new FakeAssets();
        seen = new Journal();
    }

    // ---------- состояния и переходы ----------

    @Test
    public void freshAppShowsNotBuiltAndASingleButton() throws Exception {
        publish(desktopImage());

        SetupWizard wizard = wizard(gigabytes(4));

        assertEquals(SetupWizard.Phase.NOT_BUILT, wizard.state().phase());
        assertEquals(SetupWizard.Action.BUILD, wizard.state().action());
        assertNull("на несобранном столе нечему отказывать", wizard.state().reason());
        assertEquals(0, wizard.state().stepNumber());
    }

    @Test
    public void oneTapBuildsTheDesktopAndOpensItWithoutQuestions() throws Exception {
        publish(desktopImage());

        SetupWizard.State done = wizard(gigabytes(4)).build();

        assertEquals(SetupWizard.Phase.READY, done.phase());
        assertNull("после сборки стол открывается сам, а не по второй кнопке", done.action());
        assertTrue("стол не собрался", new File(rootfs, ProotSession.READY_MARKER).isFile());
    }

    @Test
    public void buildingWalksFourStepsInOrderAndNeverGoesSilent() throws Exception {
        publish(desktopImage());

        wizard(gigabytes(4)).build();

        assertEquals(Arrays.asList(InstallProgress.Stage.SPACE, InstallProgress.Stage.VERIFY,
                        InstallProgress.Stage.UNPACK, InstallProgress.Stage.FINISH),
                seen.stages());
        assertEquals(Arrays.asList(1, 2, 3, 4), seen.stepNumbers());
        assertEquals(4, SetupWizard.TOTAL_STEPS);
        seen.assertPercentIsSaneAndGrowsWithinStage();
        assertTrue("ход сборки наружу не пошёл: экран будет молчать минутами",
                seen.maxPercentOf(InstallProgress.Stage.VERIFY) > 0);
        assertTrue("распаковка не показала ни одной доли",
                seen.maxPercentOf(InstallProgress.Stage.UNPACK) > 0);
        assertEquals("во время сборки нажимать нечего", 0, seen.actionsOffered());
    }

    @Test
    public void builtDesktopIsSeenOnTheNextLaunch() throws Exception {
        publish(desktopImage());
        wizard(gigabytes(4)).build();
        seen = new Journal();

        SetupWizard next = wizard(gigabytes(4));

        assertEquals(SetupWizard.Phase.READY, next.state().phase());
        assertNull(next.state().action());
    }

    @Test
    public void installedDesktopAppliesWallpaperOverlayOnAppUpdate() throws Exception {
        publish(desktopImage());
        wizard(gigabytes(4)).build();

        File wallpaper = new File(rootfs,
                "usr/share/backgrounds/codoceh/wallpaper-codoceh.png");
        assertTrue(wallpaper.getParentFile().mkdirs());
        Files.write(wallpaper.toPath(), "старые обои".getBytes(StandardCharsets.UTF_8));

        byte[] overlay = wallpaperOverlay("новые обои");
        publishUpdate("2026.08.8", "wallpaper-overlay.tar.zst", overlay);
        assets.put("wallpaper-overlay.tar.zst", overlay);

        SetupWizard.State done = wizard(gigabytes(4)).build();

        assertEquals(SetupWizard.Phase.READY, done.phase());
        assertEquals("новые обои", new String(Files.readAllBytes(wallpaper.toPath()),
                StandardCharsets.UTF_8));
    }

    @Test
    public void secondTapDuringBuildingDoesNotStartASecondBuild() throws Exception {
        publish(desktopImage());
        SetupWizard wizard = wizard(gigabytes(4));
        seen.onFirstBuilding(wizard::build);

        SetupWizard.State done = wizard.build();

        assertEquals(SetupWizard.Phase.READY, done.phase());
        assertEquals("образ читался лишний раз: сборка пошла дважды",
                2, assets.opens("rootfs.tar.zst"));
    }

    // ---------- процент рядом с кузницей (R04.2) ----------

    @Test
    public void percentComesFromTheStepsAndReachesAHundred() throws Exception {
        publish(desktopImage());

        SetupWizard.State done = wizard(gigabytes(4)).build();

        assertEquals("стол собран, а число застряло на полпути", 100, done.percent());
        seen.assertPercentOnlyGrows();
        assertTrue("число за всю сборку почти не двигалось: человеку не видно хода",
                seen.distinctPercents() >= 4);
    }

    @Test
    public void percentIsCountedFromBytesAndNotFromStepChanges() throws Exception {
        publish(desktopImage());

        wizard(gigabytes(4)).build();

        // Если бы число рисовалось от смены шагов, внутри шага оно бы стояло: доля
        // растёт только оттого, что установщик отдаёт прочитанные и разложенные байты.
        assertTrue("на проверке файлов число не сдвинулось от прочитанных байт",
                seen.growthInside(InstallProgress.Stage.VERIFY) > 0);
        assertTrue("на раскладке файлов число не сдвинулось от разложенных байт",
                seen.growthInside(InstallProgress.Stage.UNPACK) > 0);
    }

    @Test
    public void aStepWithoutBytesDoesNotDivideByZero() {
        // Шаг без байтов — обычное дело: метка готовности ставится одна, размера у неё нет.
        // Ноль в знаменателе не должен ни ронять сборку, ни отматывать число назад.
        SetupWizard wizard = wizard(gigabytes(4));
        InstallProgress progress = wizard.progress();

        progress.onStage(InstallProgress.Stage.UNPACK, 0L);
        int atStageStart = wizard.state().percent();
        progress.onProgress(64L * 1024 * 1024);
        int afterBytes = wizard.state().percent();

        progress.onStage(InstallProgress.Stage.FINISH, 0L);
        progress.onProgress(1L);

        assertTrue("шаг без размера начался не со своей доли: " + atStageStart,
                atStageStart > 0 && atStageStart < 100);
        assertEquals("доля шага без размера поехала от неизвестных байт",
                atStageStart, afterBytes);
        assertTrue("последний шаг встал ниже предыдущего: " + wizard.state().percent(),
                wizard.state().percent() > afterBytes && wizard.state().percent() < 100);
    }

    // ---------- путь до стола считается тапами (R18.2) ----------

    @Test
    public void firstRunTakesTwoTapsAndEveryNextOneTap() throws Exception {
        publish(desktopImage());

        int first = tapsToDesktop();
        assertTrue("первый запуск дороже трёх нажатий: " + first, first <= 3);
        assertEquals("первый запуск: тап по иконке и тап по кнопке", 2, first);
        assertEquals("собранный стол должен открываться одним тапом по иконке",
                1, tapsToDesktop());
    }

    @Test
    public void everyScreenOffersAtMostOneButton() throws Exception {
        Map<SetupWizard.Phase, SetupWizard.Action> offered =
                new EnumMap<>(SetupWizard.Phase.class);

        publish(desktopImage(), gigabytes(4));
        SetupWizard tight = wizard(megabytes(200));
        remember(offered, tight.state());
        remember(offered, tight.build());

        publish(desktopImage());
        seen = new Journal();
        remember(offered, wizard(gigabytes(4)).build());

        assertEquals("мастер показал не все свои экраны: " + offered, 4, offered.size());
        assertEquals(SetupWizard.Action.BUILD, offered.get(SetupWizard.Phase.NOT_BUILT));
        assertEquals(SetupWizard.Action.RETRY, offered.get(SetupWizard.Phase.FAILED));
        assertNull("во время сборки нажимать нечего",
                offered.get(SetupWizard.Phase.BUILDING));
        assertNull("собранный стол открывается сам", offered.get(SetupWizard.Phase.READY));
    }

    private void remember(Map<SetupWizard.Phase, SetupWizard.Action> offered,
                          SetupWizard.State last) {
        for (SetupWizard.State state : seen.states) {
            offered.put(state.phase(), state.action());
        }
        offered.put(last.phase(), last.action());
    }

    /** Считает тапы от иконки: один — на запуск приложения, остальные — по кнопкам. */
    private int tapsToDesktop() {
        SetupWizard wizard = wizard(gigabytes(4));
        int taps = 1;
        while (wizard.state().phase() != SetupWizard.Phase.READY) {
            SetupWizard.Action action = wizard.state().action();
            assertNotNull("мастер встал: нажимать нечего, а стола нет", action);
            assertEquals("на пути до стола появилось не то действие",
                    SetupWizard.Action.BUILD, action);
            taps++;
            wizard.build();
            assertTrue("мастер водит человека по кругу", taps <= 3);
        }
        return taps;
    }

    // ---------- отказы: причина и следующее действие ----------

    @Test
    public void fullDiskIsCaughtBeforeUnpackingAndNamedWithNumbers() throws Exception {
        publish(desktopImage(), 4L * 1024 * 1024 * 1024);

        SetupWizard.State failed = wizard(megabytes(200)).build();

        assertEquals(SetupWizard.Phase.FAILED, failed.phase());
        assertEquals(InstallFailedException.Reason.NOT_ENOUGH_SPACE, failed.reason());
        assertEquals(SetupWizard.Action.RETRY, failed.action());
        assertTrue("не сказано, сколько нужно: " + failed.requiredHuman(),
                failed.requiredHuman().contains("ГБ") && failed.requiredHuman().contains("4"));
        assertTrue("не сказано, сколько есть: " + failed.availableHuman(),
                failed.availableHuman().contains("МБ") && failed.availableHuman().contains("200"));
        assertFalse("распаковка началась при нехватке места", rootfs.exists());
        assertFalse("образ читался, хотя места заведомо мало",
                seen.stages().contains(InstallProgress.Stage.UNPACK));
    }

    @Test
    public void brokenImageIsNamedAndRetryIsOffered() throws Exception {
        byte[] image = desktopImage();
        publish(image);
        image[image.length / 2] ^= 0x5a;
        assets.put("rootfs.tar.zst", image);

        SetupWizard.State failed = wizard(gigabytes(4)).build();

        assertEquals(InstallFailedException.Reason.BROKEN_CHECKSUM, failed.reason());
        assertEquals(SetupWizard.Action.RETRY, failed.action());
    }

    @Test
    public void missingImageIsNamedInsteadOfSilence() {
        SetupWizard.State failed = wizard(gigabytes(4)).build();

        assertEquals(InstallFailedException.Reason.NO_IMAGE, failed.reason());
        assertEquals(SetupWizard.Action.RETRY, failed.action());
    }

    @Test
    public void unexpectedBreakageBecomesAHumanFailureAndNotACrash() {
        assets.breakOnOpen();

        SetupWizard.State failed = wizard(gigabytes(4)).build();

        assertEquals(SetupWizard.Phase.FAILED, failed.phase());
        assertNotNull("отказ без причины — это «неизвестная ошибка»", failed.reason());
        assertEquals(SetupWizard.Action.RETRY, failed.action());
    }

    @Test
    public void retryAfterFreeingSpaceReachesTheDesktop() throws Exception {
        publish(desktopImage(), 4L * 1024 * 1024 * 1024);
        assertEquals(InstallFailedException.Reason.NOT_ENOUGH_SPACE,
                wizard(megabytes(200)).build().reason());

        publish(desktopImage());
        SetupWizard.State done = wizard(gigabytes(4)).build();

        assertEquals(SetupWizard.Phase.READY, done.phase());
    }

    // ---------- сети на пути к столу нет (R01.3) ----------

    @Test
    public void wizardNeverReachesForTheNetwork() throws IOException {
        for (File source : sources()) {
            String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
            for (String forbidden : new String[]{"java.net", "HttpURLConnection", "Socket",
                    "openConnection", "android.net", "DownloadManager"}) {
                assertFalse(source.getName() + " лезет в сеть при сборке стола: " + forbidden,
                        text.contains(forbidden));
            }
        }
    }

    // ---------- снаряжение ----------

    private SetupWizard wizard(long freeBytes) {
        return new SetupWizard(new RootfsInstaller(assets, rootfs, dir -> freeBytes), seen);
    }

    private void publish(byte[] image) throws Exception {
        publish(image, image.length * 4L);
    }

    private void publish(byte[] image, long unpackedBytes) throws Exception {
        assets.put("image.json", String.format(Locale.ROOT,
                "{\"version\":\"2026.08.1\",\"asset\":\"rootfs.tar.zst\",\"sha256\":\"%s\","
                        + "\"bytes\":%d,\"unpacked_bytes\":%d,\"min_android\":28}",
                sha256(image), image.length, unpackedBytes).getBytes(StandardCharsets.UTF_8));
        assets.put("rootfs.tar.zst", image);
    }

    private void publishUpdate(String version, String asset, byte[] overlay) throws Exception {
        assets.put("image.json", String.format(Locale.ROOT,
                "{\"version\":\"%s\",\"asset\":\"rootfs.tar.zst\",\"sha256\":\"%s\","
                        + "\"bytes\":1,\"unpacked_bytes\":1,\"min_android\":28,"
                        + "\"overlay_asset\":\"%s\",\"overlay_sha256\":\"%s\","
                        + "\"overlay_bytes\":%d}",
                version, "0".repeat(64), asset, sha256(overlay), overlay.length)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Крошечный образ того же устройства, что настоящий: каталог, файл, права. */
    private static byte[] desktopImage() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            TarArchiveEntry dir = new TarArchiveEntry("etc/");
            dir.setMode(0755);
            tar.putArchiveEntry(dir);
            tar.closeArchiveEntry();
            byte[] content = "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"\n"
                    .getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry file = new TarArchiveEntry("etc/os-release");
            file.setSize(content.length);
            file.setMode(0644);
            tar.putArchiveEntry(file);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (OutputStream out = new ZstdOutputStream(packed)) {
            out.write(raw.toByteArray());
        }
        return packed.toByteArray();
    }

    private static byte[] wallpaperOverlay(String contents) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            byte[] data = contents.getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry file = new TarArchiveEntry(
                    "usr/share/backgrounds/codoceh/wallpaper-codoceh.png");
            file.setSize(data.length);
            file.setMode(0644);
            tar.putArchiveEntry(file);
            tar.write(data);
            tar.closeArchiveEntry();
        }
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (OutputStream out = new ZstdOutputStream(packed)) {
            out.write(raw.toByteArray());
        }
        return packed.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    private static long gigabytes(int count) {
        return count * 1024L * 1024 * 1024;
    }

    private static long megabytes(int count) {
        return count * 1024L * 1024;
    }

    private static List<File> sources() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{"src/main/java/ru/codoceh/stol/setup",
                    "app/src/main/java/ru/codoceh/stol/setup"}) {
                File root = new File(dir, candidate);
                if (root.isDirectory()) {
                    File[] found = root.listFiles();
                    assertNotNull(found);
                    return Arrays.asList(found);
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл исходники мастера от " + System.getProperty("user.dir"));
        return null;
    }

    /** Что увидел бы экран: все состояния подряд, в порядке появления. */
    private static final class Journal implements SetupWizard.Listener {

        final List<SetupWizard.State> states = new ArrayList<>();
        private Runnable onFirstBuilding;

        void onFirstBuilding(Runnable action) {
            onFirstBuilding = action;
        }

        @Override
        public void onState(SetupWizard.State state) {
            states.add(state);
            if (state.phase() == SetupWizard.Phase.BUILDING && onFirstBuilding != null) {
                Runnable once = onFirstBuilding;
                onFirstBuilding = null;
                once.run();
            }
        }

        List<InstallProgress.Stage> stages() {
            List<InstallProgress.Stage> stages = new ArrayList<>();
            for (SetupWizard.State state : states) {
                if (state.stage() != null && !stages.contains(state.stage())) {
                    stages.add(state.stage());
                }
            }
            return stages;
        }

        List<Integer> stepNumbers() {
            List<Integer> numbers = new ArrayList<>();
            for (SetupWizard.State state : states) {
                if (state.stage() != null && !numbers.contains(state.stepNumber())) {
                    numbers.add(state.stepNumber());
                }
            }
            return numbers;
        }

        int maxPercentOf(InstallProgress.Stage stage) {
            int most = 0;
            for (SetupWizard.State state : states) {
                if (state.stage() == stage) {
                    most = Math.max(most, state.percent());
                }
            }
            return most;
        }

        int actionsOffered() {
            int offered = 0;
            for (SetupWizard.State state : states) {
                if (state.phase() == SetupWizard.Phase.BUILDING && state.action() != null) {
                    offered++;
                }
            }
            return offered;
        }

        /** Насколько число выросло внутри шага — то есть от байтов, а не от смены шага. */
        int growthInside(InstallProgress.Stage stage) {
            int first = -1;
            int last = 0;
            for (SetupWizard.State state : states) {
                if (state.stage() == stage) {
                    if (first < 0) {
                        first = state.percent();
                    }
                    last = state.percent();
                }
            }
            assertTrue("шага " + stage + " экран вообще не видел", first >= 0);
            return last - first;
        }

        int distinctPercents() {
            List<Integer> seen = new ArrayList<>();
            for (SetupWizard.State state : states) {
                if (!seen.contains(state.percent())) {
                    seen.add(state.percent());
                }
            }
            return seen.size();
        }

        /** Доля одна на всю сборку: от начала до ста, ни разу не назад. */
        void assertPercentOnlyGrows() {
            int previous = 0;
            for (SetupWizard.State state : states) {
                if (state.phase() != SetupWizard.Phase.BUILDING
                        && state.phase() != SetupWizard.Phase.READY) {
                    continue;
                }
                assertTrue("доля вышла за сотню: " + state.percent(),
                        state.percent() >= 0 && state.percent() <= 100);
                assertTrue("доля поехала назад: " + previous + " → " + state.percent(),
                        state.percent() >= previous);
                previous = state.percent();
            }
            assertEquals("сборка кончилась, а число не дошло до ста", 100, previous);
        }

        void assertPercentIsSaneAndGrowsWithinStage() {
            Map<InstallProgress.Stage, Integer> before = new EnumMap<>(InstallProgress.Stage.class);
            for (SetupWizard.State state : states) {
                if (state.stage() == null) {
                    continue;
                }
                assertTrue("доля вышла за сотню: " + state.percent(),
                        state.percent() >= 0 && state.percent() <= 100);
                Integer previous = before.get(state.stage());
                if (previous != null) {
                    assertTrue("доля поехала назад на шаге " + state.stage(),
                            state.percent() >= previous);
                }
                before.put(state.stage(), state.percent());
            }
        }
    }

    private static final class FakeAssets implements AssetSource {

        private final Map<String, byte[]> content = new HashMap<>();
        private final Map<String, Integer> opened = new HashMap<>();
        private boolean broken;

        void put(String name, byte[] bytes) {
            content.put(name, bytes);
        }

        /** Сбой, которого установщик не обещал: мастер обязан устоять и объясниться. */
        void breakOnOpen() {
            broken = true;
        }

        int opens(String name) {
            Integer count = opened.get(name);
            return count == null ? 0 : count;
        }

        @Override
        public InputStream open(String name) throws IOException {
            if (broken) {
                throw new IllegalStateException("телефон отказал");
            }
            byte[] bytes = content.get(name);
            if (bytes == null) {
                throw new FileNotFoundException(name);
            }
            opened.put(name, opens(name) + 1);
            return new ByteArrayInputStream(bytes);
        }
    }
}
