package ru.codoceh.stol.setup;

import android.content.Context;

import com.github.luben.zstd.ZstdInputStream;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import ru.codoceh.stol.runtime.NativeAssets;
import ru.codoceh.stol.runtime.ProotSession;

/**
 * Разворачивает образ из assets в {@code files/rootfs} (шов 1). Порядок держит R02.4:
 * распаковка идёт в {@code files/rootfs.partial}, переименование — предпоследним шагом,
 * метка {@link ProotSession#READY_MARKER} — последним. Убитое на середине приложение при
 * следующем запуске видит недособранный стол и начинает заново.
 *
 * <p>Чужое здесь только разрешительное: чтение {@code .tar} — Commons Compress
 * (Apache-2.0), распаковка {@code .zst} — zstd-jni (BSD 2-Clause). Копилефта нет.
 */
public final class RootfsInstaller {

    /** Описание образа рядом с ним же, в assets. */
    public static final String MANIFEST_ASSET = "image.json";

    private static final String PARTIAL_SUFFIX = ".partial";
    private static final long PROGRESS_STEP = 4L * 1024 * 1024;
    /** Версия развёрнутого стола — рядом с ним же, не внутри: обновление не должно
     * зависеть от того, куда именно внутри образа мы её положим. */
    private static final String VERSION_MARKER = "rootfs.version";

    /** Свободное место на разделе. Шов ради теста «диск полон» без настоящего диска. */
    interface FreeSpace {
        long usableBytes(File dir);
    }

    private final AssetSource assets;
    private final File rootfs;
    private final File partial;
    private final FreeSpace freeSpace;

    /** Рабочий установщик приложения: образ из APK, каталог — тот же, что знает запуск. */
    public static RootfsInstaller forApp(Context context) {
        return new RootfsInstaller(
                new ApkAssetSource(context.getAssets()), NativeAssets.rootfsDir(context));
    }

    public RootfsInstaller(AssetSource assets, File rootfs) {
        this(assets, rootfs, new FreeSpace() {
            @Override
            public long usableBytes(File dir) {
                return dir.getUsableSpace();
            }
        });
    }

    RootfsInstaller(AssetSource assets, File rootfs, FreeSpace freeSpace) {
        this.assets = assets;
        this.rootfs = rootfs;
        this.partial = new File(rootfs.getParentFile(), rootfs.getName() + PARTIAL_SUFFIX);
        this.freeSpace = freeSpace;
    }

    /** Собран ли стол. Недособранный не считается: метку ставит последняя операция. */
    public boolean isInstalled() {
        return new File(rootfs, ProotSession.READY_MARKER).isFile();
    }

    /** Версия собранного стола; пустая строка, если стол собран до появления версии
     * на диске — тогда обновление увидит несовпадение и заменит слой один раз. */
    public String installedVersion() {
        try {
            return new String(Files.readAllBytes(versionMarker().toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException notThere) {
            return "";
        }
    }

    private File versionMarker() {
        return new File(rootfs.getParentFile(), VERSION_MARKER);
    }

    private void writeInstalledVersion(String version) throws InstallFailedException {
        try {
            Files.write(versionMarker().toPath(),
                    version.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException failed) {
            throw new InstallFailedException(InstallFailedException.Reason.DISK,
                    "Не записать версию стола", failed);
        }
    }

    /** Что за образ лежит в APK — мастеру, чтобы назвать числа до начала работы. */
    public ImageManifest manifest() throws InstallFailedException {
        try (InputStream json = assets.open(MANIFEST_ASSET)) {
            return ImageManifest.read(json);
        } catch (IOException failed) {
            throw new InstallFailedException(InstallFailedException.Reason.NO_IMAGE,
                    "В приложении нет образа стола", failed);
        }
    }

    /** Сколько байт свободно там, где будет лежать стол. */
    public long availableBytes() {
        return freeSpace.usableBytes(rootfs.getParentFile());
    }

    /**
     * Разворачивает образ. Повторный вызов на собранном столе ничего не делает.
     *
     * @return каталог собранного стола
     */
    public File install(InstallProgress progress) throws InstallFailedException {
        if (isInstalled()) {
            return rootfs;
        }
        ImageManifest manifest = manifest();

        progress.onStage(InstallProgress.Stage.SPACE, manifest.unpackedBytes());
        long available = availableBytes();
        if (available < manifest.unpackedBytes()) {
            throw InstallFailedException.notEnoughSpace(manifest.unpackedBytes(), available);
        }

        // Следы прошлой попытки — и недособранный стол, и брошенный .partial — сносим
        // до распаковки: запускать битое нельзя, дособрать его тоже нельзя.
        wipe(partial);
        wipe(rootfs);

        verify(manifest, progress);
        try {
            unpack(manifest, progress);
        } catch (InstallFailedException | RuntimeException failed) {
            wipe(partial);
            throw failed;
        }

        progress.onStage(InstallProgress.Stage.FINISH, 0L);
        finish();
        writeInstalledVersion(manifest.version());
        return rootfs;
    }

    /**
     * Обновляет уже собранный стол до версии из APK — не переустановкой, а слоем
     * поверх debootstrap (тема XFCE, обои, каталог магазина и подобное; список путей —
     * {@code tools/mkimage.sh} и {@code tools/checkimage.sh}). Пакеты apt сюда не
     * входят — их обновляет apt внутри самого стола, не APK. {@code /root} слой не
     * трогает: обновление живёт вне домашнего каталога так же, как и сам образ.
     *
     * <p>Стол не собран — ведёт себя как {@link #install}. Собран и версия совпадает —
     * не трогает диск. Собран, версия другая, а в этой сборке APK нет слоя обновления
     * (старая сборка без него) — просто поднимает записанную версию, распаковывать
     * нечего.
     *
     * @return true, если на диске что-то поменялось
     */
    public boolean update(InstallProgress progress) throws InstallFailedException {
        if (!isInstalled()) {
            install(progress);
            return true;
        }
        ImageManifest manifest = manifest();
        if (manifest.version().equals(installedVersion())) {
            return false;
        }
        if (manifest.overlayAsset() == null) {
            writeInstalledVersion(manifest.version());
            return false;
        }
        verifyOverlay(manifest, progress);
        unpackOverlay(manifest, progress);
        progress.onStage(InstallProgress.Stage.FINISH, 0L);
        writeInstalledVersion(manifest.version());
        return true;
    }

    /** Тот же порядок, что у {@link #verify}: сумма сверяется до того, как слой
     * коснётся диска. */
    private void verifyOverlay(ImageManifest manifest, InstallProgress progress)
            throws InstallFailedException {
        progress.onStage(InstallProgress.Stage.VERIFY, manifest.overlayBytes());
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new InstallFailedException(InstallFailedException.Reason.BROKEN_CHECKSUM,
                    "Проверка суммы недоступна", impossible);
        }
        byte[] buffer = new byte[65536];
        long done = 0;
        long reported = 0;
        try (InputStream image = assets.open(manifest.overlayAsset())) {
            int read;
            while ((read = image.read(buffer)) > 0) {
                sha256.update(buffer, 0, read);
                done += read;
                if (done - reported >= PROGRESS_STEP) {
                    reported = done;
                    progress.onProgress(done);
                }
            }
        } catch (IOException failed) {
            throw new InstallFailedException(InstallFailedException.Reason.NO_IMAGE,
                    "Слой обновления в приложении не читается", failed);
        }
        progress.onProgress(done);

        String actual = hex(sha256.digest());
        if (!actual.equals(manifest.overlaySha256())) {
            throw new InstallFailedException(InstallFailedException.Reason.BROKEN_CHECKSUM,
                    "Слой обновления повреждён: сумма " + actual.substring(0, 16)
                            + "… вместо " + manifest.overlaySha256().substring(0, 16) + "…");
        }
    }

    /** В отличие от {@link #unpack}, разворачивает прямо в {@code rootfs} — слой
     * ложится поверх уже собранного стола, а не в отдельный {@code .partial}: обрыв
     * посередине оставляет часть файлов уже новыми, часть старыми, но не битый стол —
     * пакеты apt и {@code /root} слоя не касаются вовсе. */
    private void unpackOverlay(ImageManifest manifest, InstallProgress progress)
            throws InstallFailedException {
        progress.onStage(InstallProgress.Stage.UNPACK, manifest.overlayBytes());
        try (InputStream image = assets.open(manifest.overlayAsset());
             InputStream tar = new Counting(new ZstdInputStream(image), progress)) {
            TarUnpacker.unpack(tar, rootfs);
        } catch (IOException failed) {
            throw new InstallFailedException(InstallFailedException.Reason.BROKEN_ARCHIVE,
                    "Слой обновления не разворачивается: " + failed.getMessage(), failed);
        }
    }

    /** Сумма сверяется до распаковки: порча образа не должна дать битый стол (R11.2). */
    private void verify(ImageManifest manifest, InstallProgress progress)
            throws InstallFailedException {
        progress.onStage(InstallProgress.Stage.VERIFY, manifest.bytes());
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new InstallFailedException(InstallFailedException.Reason.BROKEN_CHECKSUM,
                    "Проверка суммы недоступна", impossible);
        }
        byte[] buffer = new byte[65536];
        long done = 0;
        long reported = 0;
        try (InputStream image = assets.open(manifest.asset())) {
            int read;
            while ((read = image.read(buffer)) > 0) {
                sha256.update(buffer, 0, read);
                done += read;
                if (done - reported >= PROGRESS_STEP) {
                    reported = done;
                    progress.onProgress(done);
                }
            }
        } catch (IOException failed) {
            throw new InstallFailedException(InstallFailedException.Reason.NO_IMAGE,
                    "Образ в приложении не читается", failed);
        }
        progress.onProgress(done);

        String actual = hex(sha256.digest());
        if (!actual.equals(manifest.sha256())) {
            throw new InstallFailedException(InstallFailedException.Reason.BROKEN_CHECKSUM,
                    "Образ повреждён: сумма " + actual.substring(0, 16)
                            + "… вместо " + manifest.sha256().substring(0, 16) + "…");
        }
    }

    private void unpack(ImageManifest manifest, InstallProgress progress)
            throws InstallFailedException {
        progress.onStage(InstallProgress.Stage.UNPACK, manifest.unpackedBytes());
        if (!partial.mkdirs()) {
            throw new InstallFailedException(InstallFailedException.Reason.DISK,
                    "Не создать каталог " + partial.getName());
        }
        try (InputStream image = assets.open(manifest.asset());
             InputStream tar = new Counting(new ZstdInputStream(image), progress)) {
            TarUnpacker.unpack(tar, partial);
        } catch (IOException failed) {
            throw new InstallFailedException(InstallFailedException.Reason.BROKEN_ARCHIVE,
                    "Образ не разворачивается: " + failed.getMessage(), failed);
        }
    }

    /** Два последних шага и их порядок — единственная защита от битого стола. */
    private void finish() throws InstallFailedException {
        if (!partial.renameTo(rootfs)) {
            wipe(partial);
            throw new InstallFailedException(InstallFailedException.Reason.DISK,
                    "Не переименовать " + partial.getName() + " в " + rootfs.getName());
        }
        File marker = new File(rootfs, ProotSession.READY_MARKER);
        try {
            if (!marker.createNewFile()) {
                throw new IOException("метка уже есть");
            }
        } catch (IOException failed) {
            wipe(rootfs);
            throw new InstallFailedException(InstallFailedException.Reason.DISK,
                    "Не поставить метку готовности", failed);
        }
    }

    /** Сносит каталог целиком. По ссылкам не ходит: в образе их много. */
    private static void wipe(File file) {
        if (!file.exists() && !Files.isSymbolicLink(file.toPath())) {
            return;
        }
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    wipe(child);
                }
            }
        }
        file.delete();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            text.append(String.format(Locale.ROOT, "%02x", b));
        }
        return text.toString();
    }

    /** Считает распакованные байты и отдаёт их мастеру, не чаще чем раз в несколько мегабайт. */
    private static final class Counting extends FilterInputStream {

        private final InstallProgress progress;
        private long done;
        private long reported;
        private boolean closed;

        Counting(InputStream in, InstallProgress progress) {
            super(in);
            this.progress = progress;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            long skipped = super.skip(count);
            if (skipped > 0) {
                count(skipped);
            }
            return skipped;
        }

        private void count(long bytes) {
            done += bytes;
            if (done - reported >= PROGRESS_STEP) {
                reported = done;
                progress.onProgress(done);
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            super.close();
            progress.onProgress(done);
        }
    }
}
