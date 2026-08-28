package ru.codoceh.stol.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.github.luben.zstd.ZstdOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ru.codoceh.stol.runtime.ProotSession;

/**
 * Задача 06: образ из assets разворачивается в {@code files/rootfs}. Настоящего образа
 * ещё нет, поэтому каждый тест собирает свой маленький {@code .tar.zst} — настоящий, тем
 * же zstd, который распаковывает приложение.
 *
 * <p>Проверяется то, из-за чего человек получил бы битый стол: сумма до распаковки
 * (R11.2), порядок «partial → rootfs → метка» (R02.4), нехватка места числом (R02.5) и
 * ход распаковки наружу.
 */
public class RootfsInstallerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File files;
    private File rootfs;
    private File partial;
    private FakeAssets assets;
    private Recorder progress;

    @Before
    public void setUp() throws IOException {
        files = folder.newFolder("files");
        rootfs = new File(files, "rootfs");
        partial = new File(files, "rootfs.partial");
        assets = new FakeAssets();
        progress = new Recorder();
    }

    // ---------- распаковка ----------

    @Test
    public void unpacksFilesDirectoriesAndLinks() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);

        File installed = installer(gigabytes(4)).install(progress);

        assertEquals(rootfs, installed);
        assertEquals("привет", read(new File(rootfs, "usr/bin/hello")));
        assertTrue("бит исполнения потерян", new File(rootfs, "usr/bin/hello").canExecute());
        assertFalse("бит исполнения появился на данных",
                new File(rootfs, "etc/os-release").canExecute());
        assertTrue(new File(rootfs, "usr/share/stol").isDirectory());
        assertEquals("dash", Files.readSymbolicLink(
                new File(rootfs, "usr/bin/sh").toPath()).toString());
        assertEquals("привет", read(new File(rootfs, "usr/bin/hello-again")));
        assertTrue("метка готовности не поставлена",
                new File(rootfs, ProotSession.READY_MARKER).isFile());
        assertFalse("остался незавершённый каталог", partial.exists());
        assertTrue(installer(gigabytes(4)).isInstalled());
    }

    @Test
    public void desktopAppearsOnlyWhenItIsWholeAndMarkerComesLast() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);

        RootfsInstaller installer = installer(gigabytes(4));
        progress.watcher = () -> {
            assertFalse("стол показался раньше времени", rootfs.exists());
            assertFalse("недособранный стол считается собранным", installer.isInstalled());
        };
        installer.install(progress);

        assertTrue(new File(rootfs, ProotSession.READY_MARKER).isFile());
        assertEquals(InstallProgress.Stage.FINISH, progress.stages.get(progress.stages.size() - 1));
    }

    @Test
    public void secondInstallDoesNotTouchTheImageAgain() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);
        installer(gigabytes(4)).install(progress);
        int opens = assets.opens("rootfs.tar.zst");

        installer(gigabytes(4)).install(new Recorder());

        assertEquals("собранный стол пересобирается заново", opens, assets.opens("rootfs.tar.zst"));
    }

    // ---------- целостность ----------

    @Test
    public void brokenImageIsCaughtBeforeUnpacking() throws Exception {
        byte[] image = desktopImage();
        image[image.length / 2] ^= 0x5a;
        publish(image, sha256(desktopImage()), image.length * 4L);

        InstallFailedException failed = installFails(gigabytes(4));

        assertEquals(InstallFailedException.Reason.BROKEN_CHECKSUM, failed.reason());
        assertTrue("сообщение не называет сумму", failed.getMessage().contains("сумма"));
        assertFalse("распаковка началась при разошедшейся сумме", partial.exists());
        assertFalse(rootfs.exists());
    }

    @Test
    public void archiveThatMatchesItsChecksumButIsGarbageFailsCleanly() throws Exception {
        byte[] garbage = new byte[4096];
        for (int at = 0; at < garbage.length; at++) {
            garbage[at] = (byte) at;
        }
        publish(garbage, sha256(garbage), 65536L);

        InstallFailedException failed = installFails(gigabytes(4));

        assertEquals(InstallFailedException.Reason.BROKEN_ARCHIVE, failed.reason());
        assertFalse("недособранный каталог остался", partial.exists());
        assertFalse(rootfs.exists());
    }

    @Test
    public void entryPointingOutsideTheImageIsRejected() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            addFile(tar, "../сбежал.txt", "чужое", 0644);
        }
        byte[] image = zstd(raw.toByteArray());
        publish(image, sha256(image), raw.size());

        InstallFailedException failed = installFails(gigabytes(4));

        assertEquals(InstallFailedException.Reason.BROKEN_ARCHIVE, failed.reason());
        assertFalse("запись легла мимо образа", new File(files, "сбежал.txt").exists());
        assertFalse(partial.exists());
    }

    // ---------- место на диске ----------

    @Test
    public void tooLittleSpaceIsRefusedWithBothNumbers() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), 3L * 1024 * 1024 * 1024);

        InstallFailedException failed = installFails(2L * 1024 * 1024 * 1024);

        assertEquals(InstallFailedException.Reason.NOT_ENOUGH_SPACE, failed.reason());
        assertEquals(3L * 1024 * 1024 * 1024, failed.requiredBytes());
        assertEquals(2L * 1024 * 1024 * 1024, failed.availableBytes());
        assertTrue("сообщение без чисел: " + failed.getMessage(),
                failed.getMessage().contains("3,0 ГБ") && failed.getMessage().contains("2,0 ГБ"));
        assertEquals("образ трогали при нехватке места", 0, assets.opens("rootfs.tar.zst"));
        assertFalse(partial.exists());
    }

    @Test
    public void spaceExactlyEqualToTheImageIsEnough() throws Exception {
        byte[] image = desktopImage();
        long unpacked = 16L * 1024 * 1024;
        publish(image, sha256(image), unpacked);

        installer(unpacked).install(progress);

        assertTrue(new File(rootfs, ProotSession.READY_MARKER).isFile());
    }

    // ---------- прерывание ----------

    @Test
    public void leftoverFromKilledRunIsThrownAwayAndUnpackedAgain() throws Exception {
        assertTrue(new File(partial, "usr/bin").mkdirs());
        write(new File(partial, "usr/bin/огрызок"), "половина прошлой распаковки");
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);

        installer(gigabytes(4)).install(progress);

        assertFalse("огрызок прошлой попытки попал в стол",
                new File(rootfs, "usr/bin/огрызок").exists());
        assertFalse(partial.exists());
        assertEquals("привет", read(new File(rootfs, "usr/bin/hello")));
    }

    @Test
    public void desktopWithoutMarkerIsNotStartedButRebuilt() throws Exception {
        assertTrue(new File(rootfs, "usr/bin").mkdirs());
        write(new File(rootfs, "usr/bin/огрызок"), "недособранный стол");
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);
        RootfsInstaller installer = installer(gigabytes(4));
        assertFalse("недособранный стол сошёл за собранный", installer.isInstalled());

        installer.install(progress);

        assertFalse(new File(rootfs, "usr/bin/огрызок").exists());
        assertTrue(installer.isInstalled());
    }

    @Test
    public void cleanupDoesNotFollowSymlinksOutOfTheImage() throws Exception {
        File outside = folder.newFolder("чужое");
        write(new File(outside, "документ.txt"), "не наше");
        assertTrue(partial.mkdirs());
        Files.createSymbolicLink(new File(partial, "мост").toPath(), outside.toPath());
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);

        installer(gigabytes(4)).install(progress);

        assertTrue("удаление ушло по ссылке наружу", new File(outside, "документ.txt").isFile());
        assertFalse(new File(rootfs, "мост").exists());
    }

    // ---------- ход установки ----------

    @Test
    public void everyStageIsAnnouncedInOrderWithItsSize() throws Exception {
        byte[] image = desktopImage();
        long unpacked = image.length * 4L;
        publish(image, sha256(image), unpacked);

        installer(gigabytes(4)).install(progress);

        assertEquals(java.util.Arrays.asList(InstallProgress.Stage.SPACE,
                InstallProgress.Stage.VERIFY, InstallProgress.Stage.UNPACK,
                InstallProgress.Stage.FINISH), progress.stages);
        assertEquals(unpacked, (long) progress.totals.get(0));
        assertEquals(image.length, (long) progress.totals.get(1));
        assertEquals(unpacked, (long) progress.totals.get(2));
        assertTrue("ход установки наружу не пошёл", progress.bytes.size() >= 2);
        progress.assertGrowsWithinStage();
        assertEquals("сумма считалась не по всему образу",
                image.length, progress.lastOf(InstallProgress.Stage.VERIFY));
        assertTrue("распаковано ноль байт",
                progress.lastOf(InstallProgress.Stage.UNPACK) > 0);
    }

    // ---------- обновление ----------

    @Test
    public void updateOnFreshDeviceInstallsFromScratch() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);

        boolean changed = installer(gigabytes(4)).update(progress);

        assertTrue("свежая установка не считается изменением", changed);
        assertTrue(new File(rootfs, ProotSession.READY_MARKER).isFile());
        assertEquals("2026.08.1", installer(gigabytes(4)).installedVersion());
    }

    @Test
    public void updateOnSameVersionTouchesNothing() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);
        RootfsInstaller installer = installer(gigabytes(4));
        installer.install(progress);
        int opens = assets.opens("rootfs.tar.zst");

        boolean changed = installer.update(new Recorder());

        assertFalse("та же версия — а обновление сказало, что что-то поменялось", changed);
        assertEquals("совпадающая версия полезла в образ снова",
                opens, assets.opens("rootfs.tar.zst"));
    }

    @Test
    public void updateWithoutOverlayJustRaisesTheVersionNumber() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);
        RootfsInstaller installer = installer(gigabytes(4));
        installer.install(progress);
        write(new File(rootfs, "usr/bin/hello"), "старый файл цел");

        publishNewVersion("2026.09.1", null, null, 0);
        boolean changed = installer.update(new Recorder());

        assertFalse("без слоя обновления на диске менять нечего", changed);
        assertEquals("2026.09.1", installer.installedVersion());
        assertEquals("слой без сборки не должен трогать файлы стола",
                "старый файл цел", read(new File(rootfs, "usr/bin/hello")));
    }

    @Test
    public void updateWithOverlayReplacesOnlyOverlayFilesAndKeepsTheRestIntact() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);
        RootfsInstaller installer = installer(gigabytes(4));
        installer.install(progress);
        write(new File(rootfs, "root/свои-заметки.txt"), "не трогать никогда");

        byte[] overlay = overlayImage();
        publishNewVersion("2026.09.1", "overlay.tar.zst", sha256(overlay), overlay.length);
        assets.put("overlay.tar.zst", overlay);

        boolean changed = installer.update(new Recorder());

        assertTrue("слой был, а update() сказал, что ничего не поменялось", changed);
        assertEquals("2026.09.1", installer.installedVersion());
        assertEquals("новые обои", read(new File(rootfs, "usr/share/backgrounds/wallpaper.png")));
        assertEquals("слой затронул домашний каталог",
                "не трогать никогда", read(new File(rootfs, "root/свои-заметки.txt")));
        assertEquals("привет", read(new File(rootfs, "usr/bin/hello")));
    }

    @Test
    public void updateWithBrokenOverlayChecksumLeavesOldFilesUntouched() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);
        RootfsInstaller installer = installer(gigabytes(4));
        installer.install(progress);

        byte[] overlay = overlayImage();
        publishNewVersion("2026.09.1", "overlay.tar.zst", sha256(overlay), overlay.length);
        byte[] corrupted = overlay.clone();
        corrupted[corrupted.length / 2] ^= 0x5a;
        assets.put("overlay.tar.zst", corrupted);

        try {
            installer.update(new Recorder());
            fail("порченый слой обновления должен отказать");
        } catch (InstallFailedException failed) {
            assertEquals(InstallFailedException.Reason.BROKEN_CHECKSUM, failed.reason());
        }
        assertEquals("версия поднялась, хотя слой не встал", "2026.08.1", installer.installedVersion());
        assertFalse("обои появились из непроверенного слоя",
                new File(rootfs, "usr/share/backgrounds/wallpaper.png").exists());
    }

    @Test
    public void updateOnUnfinishedInstallBuildsFromScratchLikeInstall() throws Exception {
        assertTrue(new File(rootfs, "usr/bin").mkdirs());
        write(new File(rootfs, "usr/bin/огрызок"), "недособранный стол");
        byte[] image = desktopImage();
        publish(image, sha256(image), image.length * 4L);

        boolean changed = installer(gigabytes(4)).update(progress);

        assertTrue(changed);
        assertFalse(new File(rootfs, "usr/bin/огрызок").exists());
        assertTrue(installer(gigabytes(4)).isInstalled());
    }

    // ---------- описание образа ----------

    @Test
    public void imageWithoutDescriptionIsNamedPlainly() {
        InstallFailedException failed = installFails(gigabytes(4));
        assertEquals(InstallFailedException.Reason.NO_IMAGE, failed.reason());
    }

    @Test
    public void mangledDescriptionIsRefused() throws Exception {
        assets.put("image.json", ("{\"version\":\"2026.08.1\",\"asset\":\"rootfs.tar.zst\","
                + "\"sha256\":\"коротко\",\"bytes\":1,\"unpacked_bytes\":1}")
                .getBytes(StandardCharsets.UTF_8));
        assets.put("rootfs.tar.zst", desktopImage());

        InstallFailedException failed = installFails(gigabytes(4));

        assertEquals(InstallFailedException.Reason.NO_IMAGE, failed.reason());
    }

    @Test
    public void wizardCanReadTheNumbersBeforeStarting() throws Exception {
        byte[] image = desktopImage();
        publish(image, sha256(image), 2147483648L);

        RootfsInstaller installer = installer(gigabytes(4));
        ImageManifest manifest = installer.manifest();

        assertEquals("2026.08.1", manifest.version());
        assertEquals("rootfs.tar.zst", manifest.asset());
        assertEquals(sha256(image), manifest.sha256());
        assertEquals(image.length, manifest.bytes());
        assertEquals(2147483648L, manifest.unpackedBytes());
        assertEquals(28, manifest.minAndroid());
        assertEquals(gigabytes(4), installer.availableBytes());
    }

    // ---------- снаряжение ----------

    private RootfsInstaller installer(long freeBytes) {
        return new RootfsInstaller(assets, rootfs, dir -> freeBytes);
    }

    private InstallFailedException installFails(long freeBytes) {
        try {
            installer(freeBytes).install(progress);
            fail("установка должна была отказать");
            return null;
        } catch (InstallFailedException failed) {
            assertNotNull(failed.getMessage());
            return failed;
        }
    }

    private void publish(byte[] image, String sha256, long unpackedBytes) {
        assets.put("image.json", String.format(Locale.ROOT,
                "{\"version\":\"2026.08.1\",\"asset\":\"rootfs.tar.zst\",\"sha256\":\"%s\","
                        + "\"bytes\":%d,\"unpacked_bytes\":%d,\"min_android\":28}",
                sha256, image.length, unpackedBytes).getBytes(StandardCharsets.UTF_8));
        assets.put("rootfs.tar.zst", image);
    }

    /**
     * Как {@link #publish}, но версия другая и, если названы, есть поля слоя
     * обновления. Поля самого базового образа (сумма, размеры) здесь ни при чём:
     * {@code update()} на уже собранном столе их не читает вовсе, а только версию и
     * слой — поэтому здесь произвольная валидная сумма, не настоящий образ.
     */
    private void publishNewVersion(String version, String overlayAsset, String overlaySha256,
                                    int overlayBytes) {
        String overlayFields = overlayAsset == null ? "" : String.format(Locale.ROOT,
                ",\"overlay_asset\":\"%s\",\"overlay_sha256\":\"%s\",\"overlay_bytes\":%d",
                overlayAsset, overlaySha256, overlayBytes);
        assets.put("image.json", String.format(Locale.ROOT,
                "{\"version\":\"%s\",\"asset\":\"rootfs.tar.zst\",\"sha256\":\"%s\","
                        + "\"bytes\":1,\"unpacked_bytes\":1,\"min_android\":28%s}",
                version, "0".repeat(64), overlayFields)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Крошечный слой обновления: одни обои поверх уже собранного стола. */
    private static byte[] overlayImage() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            addDir(tar, "usr/share/backgrounds");
            addFile(tar, "usr/share/backgrounds/wallpaper.png", "новые обои", 0644);
        }
        return zstd(raw.toByteArray());
    }

    /** Тот же состав, что у настоящего образа: каталоги, права, ссылки — только крошечный. */
    private static byte[] desktopImage() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(raw)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            addDir(tar, "usr/bin");
            addDir(tar, "usr/share/stol");
            addFile(tar, "usr/bin/hello", "привет", 0755);
            addFile(tar, "etc/os-release", "PRETTY_NAME=\"Debian GNU/Linux 13 (trixie)\"", 0644);
            addLink(tar, "usr/bin/sh", "dash", TarConstants.LF_SYMLINK);
            addLink(tar, "usr/bin/hello-again", "usr/bin/hello", TarConstants.LF_LINK);
            addFile(tar, "opt/stol/start-gui.sh", "#!/bin/sh\n", 0755);
        }
        return zstd(raw.toByteArray());
    }

    private static void addDir(TarArchiveOutputStream tar, String name) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name + "/");
        entry.setMode(0755);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static void addFile(TarArchiveOutputStream tar, String name, String content, int mode)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        entry.setMode(mode);
        tar.putArchiveEntry(entry);
        tar.write(bytes);
        tar.closeArchiveEntry();
    }

    private static void addLink(TarArchiveOutputStream tar, String name, String to, byte kind)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name, kind);
        entry.setLinkName(to);
        entry.setMode(kind == TarConstants.LF_SYMLINK ? 0777 : 0755);
        tar.putArchiveEntry(entry);
        tar.closeArchiveEntry();
    }

    private static byte[] zstd(byte[] plain) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (OutputStream out = new ZstdOutputStream(packed)) {
            out.write(plain);
        }
        return packed.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    private static long gigabytes(int count) {
        return count * 1024L * 1024 * 1024;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())),
                StandardCharsets.UTF_8);
    }

    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("не создать " + parent);
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class FakeAssets implements AssetSource {

        private final Map<String, byte[]> content = new HashMap<>();
        private final Map<String, Integer> opened = new HashMap<>();

        void put(String name, byte[] bytes) {
            content.put(name, bytes);
        }

        int opens(String name) {
            Integer count = opened.get(name);
            return count == null ? 0 : count;
        }

        @Override
        public InputStream open(String name) throws IOException {
            byte[] bytes = content.get(name);
            if (bytes == null) {
                throw new FileNotFoundException(name);
            }
            opened.put(name, opens(name) + 1);
            return new ByteArrayInputStream(bytes);
        }
    }

    private static final class Recorder implements InstallProgress {

        final List<Stage> stages = new ArrayList<>();
        final List<Long> totals = new ArrayList<>();
        final List<Long> bytes = new ArrayList<>();
        private final List<Stage> byteStages = new ArrayList<>();
        Runnable watcher;

        @Override
        public void onStage(Stage stage, long totalBytes) {
            stages.add(stage);
            totals.add(totalBytes);
            if (watcher != null) {
                watcher.run();
            }
        }

        @Override
        public void onProgress(long doneBytes) {
            bytes.add(doneBytes);
            byteStages.add(stages.get(stages.size() - 1));
            if (watcher != null) {
                watcher.run();
            }
        }

        long lastOf(Stage stage) {
            long last = -1;
            for (int at = 0; at < bytes.size(); at++) {
                if (byteStages.get(at) == stage) {
                    last = bytes.get(at);
                }
            }
            return last;
        }

        void assertGrowsWithinStage() {
            Map<Stage, Long> seen = new HashMap<>();
            for (int at = 0; at < bytes.size(); at++) {
                Stage stage = byteStages.get(at);
                Long previous = seen.get(stage);
                if (previous != null) {
                    assertTrue("счётчик байт поехал назад на шаге " + stage,
                            bytes.get(at) >= previous);
                }
                seen.put(stage, bytes.get(at));
            }
        }
    }
}
