package ru.codoceh.stol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * R13i.1: всё родное в assets собрано под arm64. Читается заголовок ELF, а не имя файла.
 * Заодно проверяется, что запись о происхождении в SOURCES.md не отстала от файлов.
 */
public class NativeBinariesArm64Test {

    private static final int ELF_CLASS_64 = 2;
    private static final int ELF_DATA_LSB = 1;
    private static final int EM_AARCH64 = 183;

    private static final List<String> REQUIRED = Arrays.asList(
            "bin/proot", "bin/dash", "bin/zstd", "libexec/proot/loader");

    @Test
    public void everyNativeFileIsAarch64Elf() throws IOException {
        List<File> files = executables();
        assertTrue("в assets/native не найдено ни одного файла", files.size() >= REQUIRED.size());
        for (File file : files) {
            byte[] header = header(file);
            assertTrue(file + ": не ELF", header[0] == 0x7f && header[1] == 'E'
                    && header[2] == 'L' && header[3] == 'F');
            assertEquals(file + ": не 64-битный", ELF_CLASS_64, header[4]);
            assertEquals(file + ": не little-endian", ELF_DATA_LSB, header[5]);
            int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
            assertEquals(file + ": машина не AArch64", EM_AARCH64, machine);
        }
    }

    @Test
    public void requiredBinariesArePresent() {
        for (String required : REQUIRED) {
            File file = new File(nativeDir(), required);
            assertTrue("нет " + required, file.isFile());
        }
    }

    @Test
    public void provenanceIsRecordedAndChecksumsMatch() throws Exception {
        File sources = new File(nativeDir(), "SOURCES.md");
        assertTrue("нет SOURCES.md рядом с бинарниками", sources.isFile());
        String text = new String(Files.readAllBytes(sources.toPath()), StandardCharsets.UTF_8);
        for (File file : executables()) {
            String relative = relative(file);
            assertTrue("в SOURCES.md нет записи про " + relative, text.contains(relative));
            String actual = sha256(file);
            assertTrue("sha256 в SOURCES.md разошлась с файлом " + relative,
                    text.contains(actual.substring(0, 16)));
        }
    }

    @Test
    public void everyPackageShipsItsLicenseText() {
        File licenses = new File(nativeDir(), "licenses");
        assertTrue("нет каталога licenses", licenses.isDirectory());
        File[] packages = licenses.listFiles();
        assertTrue("каталог licenses пуст", packages != null && packages.length > 0);
        for (File pkg : packages) {
            File[] texts = pkg.listFiles();
            assertTrue("нет текста лицензии для " + pkg.getName(), texts != null && texts.length > 0);
        }
    }

    private static byte[] header(File file) throws IOException {
        byte[] header = new byte[20];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.readFully(header);
        }
        return header;
    }

    private static List<File> executables() {
        List<File> found = new ArrayList<>();
        for (String tree : new String[]{"bin", "lib", "libexec"}) {
            collect(new File(nativeDir(), tree), found);
        }
        Collections.sort(found);
        return found;
    }

    private static void collect(File dir, List<File> found) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, found);
            } else {
                found.add(child);
            }
        }
    }

    private static String relative(File file) {
        return file.getAbsolutePath()
                .substring(nativeDir().getAbsolutePath().length() + 1)
                .replace(File.separatorChar, '/');
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    static File nativeDir() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            File candidate = new File(dir, "src/main/assets/native");
            if (candidate.isDirectory()) {
                return candidate;
            }
            candidate = new File(dir, "app/src/main/assets/native");
            if (candidate.isDirectory()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл assets/native от " + System.getProperty("user.dir"));
        return null;
    }
}
