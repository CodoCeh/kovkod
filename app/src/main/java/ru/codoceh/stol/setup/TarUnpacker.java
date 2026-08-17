package ru.codoceh.stol.setup;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Разбор {@code .tar} на стороне Android. GNU tar не берём: он под GPL-3.0, а продукт
 * закрытый. Чтение архива — Apache Commons Compress (Apache-2.0, разрешительная),
 * распаковка на диск — свой код.
 */
final class TarUnpacker {

    private TarUnpacker() {
    }

    /**
     * Разворачивает архив в каталог. Каталог должен быть пустым: чужого содержимого
     * распаковка не ждёт.
     *
     * @return сколько записей разложено
     */
    static int unpack(InputStream tar, File target) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("не создать каталог " + target);
        }
        String root = target.getCanonicalPath();
        int entries = 0;
        try (TarArchiveInputStream archive = new TarArchiveInputStream(tar)) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                File file = resolve(root, target, entry.getName());
                if (entry.isDirectory()) {
                    makeDir(file);
                } else if (entry.isSymbolicLink()) {
                    makeDir(file.getParentFile());
                    Files.createSymbolicLink(file.toPath(), Paths.get(entry.getLinkName()));
                } else if (entry.isLink()) {
                    makeDir(file.getParentFile());
                    File to = resolve(root, target, entry.getLinkName());
                    Files.createLink(file.toPath(), to.toPath());
                } else if (entry.isFile()) {
                    makeDir(file.getParentFile());
                    write(archive, file);
                } else {
                    // Устройства и каналы под Android без прав root не создать; они и не нужны:
                    // /dev внутрь образа приходит привязкой.
                    continue;
                }
                applyMode(file, entry.getMode());
                entries++;
            }
        }
        return entries;
    }

    /** Запись, метящая наружу каталога, — это порча образа, а не распаковка. */
    private static File resolve(String root, File target, String name) throws IOException {
        File file = new File(target, name);
        String path = file.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) {
            throw new IOException("запись ведёт наружу образа: " + name);
        }
        return file;
    }

    private static void makeDir(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("не создать каталог " + dir);
        }
    }

    private static void write(InputStream from, File file) throws IOException {
        byte[] buffer = new byte[65536];
        try (OutputStream out = new FileOutputStream(file)) {
            int read;
            while ((read = from.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Внутри образа всё живёт под одним пользователем, поэтому важны только права
     * владельца. Ставятся средствами Java: то же поведение и на устройстве, и в тестах.
     */
    private static void applyMode(File file, int mode) {
        if (Files.isSymbolicLink(file.toPath())) {
            return;
        }
        if (file.isDirectory()) {
            // Каталог с правами только на чтение перекрыл бы распаковку его же содержимого,
            // а внутри образа права всё равно подменяет запуск от имени root.
            file.setReadable(true, true);
            file.setWritable(true, true);
            file.setExecutable(true, true);
            return;
        }
        file.setReadable((mode & 0400) != 0, true);
        file.setWritable((mode & 0200) != 0, true);
        file.setExecutable((mode & 0100) != 0, true);
    }
}
