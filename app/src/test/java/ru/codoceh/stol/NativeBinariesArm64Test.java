package ru.codoceh.stol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * R13i.1: всё родное собрано под arm64. Читается заголовок ELF, а не имя файла.
 *
 * <p>R19D.1: родные бинарники лежат в {@code jniLibs}, а не в {@code assets}, и названы
 * так, чтобы установщик пакетов их распаковал — иначе запускать будет нечего.
 *
 * <p>Заодно проверяется, что запись о происхождении в SOURCES.md не отстала от файлов.
 */
public class NativeBinariesArm64Test {

    private static final int ELF_CLASS_64 = 2;
    private static final int ELF_DATA_LSB = 1;
    private static final int EM_AARCH64 = 183;

    private static final List<String> REQUIRED = Arrays.asList(
            "libproot.so", "libproot-loader.so",
            "libtalloc.so", "libandroid-shmem.so");

    /** Эти даёт сама Android, класть их в APK не нужно и нельзя. */
    private static final Set<String> SYSTEM = new HashSet<>(Arrays.asList(
            "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"));

    @Test
    public void everyNativeFileIsAarch64Elf() throws IOException {
        List<File> files = shipped();
        assertTrue("в jniLibs не найдено ни одного файла", files.size() >= REQUIRED.size());
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
            assertTrue("нет " + required, new File(jniLibs(), required).isFile());
        }
    }

    /**
     * Установщик пакетов распаковывает из APK только файлы вида {@code lib*.so}. Файл с
     * другим именем до устройства не доедет, и понятно это станет только на телефоне.
     */
    @Test
    public void everyNameSurvivesInstallation() {
        for (File file : shipped()) {
            String name = file.getName();
            assertTrue("установщик не распакует " + name,
                    name.startsWith("lib") && name.endsWith(".so"));
        }
    }

    /** R19D.1: в assets исполняемых файлов не остаётся — оттуда их запускать нельзя. */
    @Test
    public void assetsHoldNoExecutables() throws IOException {
        for (File file : collect(new File(mainDir(), "assets"))) {
            if (file.length() < 4) {
                continue;
            }
            byte[] header = header(file, 4);
            assertFalse("в assets лежит ELF: " + file.getName(),
                    header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F');
        }
    }

    /**
     * Каждая библиотека, которую просит бинарник, либо системная, либо лежит рядом. Иначе
     * запуск падает на устройстве строкой линкера, а не здесь.
     */
    @Test
    public void everyRequestedLibraryIsShippedOrSystem() throws IOException {
        Set<String> available = new HashSet<>(SYSTEM);
        for (File file : shipped()) {
            String soname = soname(file);
            available.add(soname == null ? file.getName() : soname);
        }
        for (File file : shipped()) {
            for (String needed : needed(file)) {
                assertTrue(file.getName() + " просит " + needed + ", а его нет: " + available,
                        available.contains(needed));
            }
        }
    }

    @Test
    public void provenanceIsRecordedAndChecksumsMatch() throws Exception {
        File sources = sourcesFile();
        assertTrue("нет SOURCES.md", sources.isFile());
        String text = new String(Files.readAllBytes(sources.toPath()), StandardCharsets.UTF_8);
        for (File file : shipped()) {
            assertTrue("в SOURCES.md нет записи про " + file.getName(),
                    text.contains(file.getName()));
            String actual = sha256(file);
            assertTrue("sha256 в SOURCES.md разошлась с файлом " + file.getName(),
                    text.contains(actual.substring(0, 16)));
        }
    }

    /** Лицензия нужна тому, что реально уехало в APK, — и только ему. */
    @Test
    public void everyPackageShipsItsLicenseText() {
        File licenses = new File(sourcesFile().getParentFile(), "licenses");
        assertTrue("нет каталога licenses", licenses.isDirectory());
        File[] packages = licenses.listFiles();
        assertTrue("каталог licenses пуст", packages != null && packages.length > 0);
        for (File pkg : packages) {
            File[] texts = pkg.listFiles();
            assertTrue("нет текста лицензии для " + pkg.getName(), texts != null && texts.length > 0);
        }
    }

    private static byte[] header(File file) throws IOException {
        return header(file, 20);
    }

    private static byte[] header(File file, int length) throws IOException {
        byte[] header = new byte[length];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.readFully(header);
        }
        return header;
    }

    private static List<File> shipped() {
        List<File> found = collect(jniLibs());
        Collections.sort(found);
        return found;
    }

    private static List<File> collect(File dir) {
        List<File> found = new ArrayList<>();
        collect(dir, found);
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

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
    }

    // --- разбор ELF: имя библиотеки (DT_SONAME) и её зависимости (DT_NEEDED) ---

    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final long DT_NULL = 0;
    private static final long DT_NEEDED = 1;
    private static final long DT_SONAME = 14;
    private static final long DT_STRTAB = 5;

    private static List<String> needed(File file) throws IOException {
        return dynamicStrings(file, DT_NEEDED);
    }

    private static String soname(File file) throws IOException {
        List<String> names = dynamicStrings(file, DT_SONAME);
        return names.isEmpty() ? null : names.get(0);
    }

    private static List<String> dynamicStrings(File file, long tag) throws IOException {
        ByteBuffer elf = ByteBuffer.wrap(Files.readAllBytes(file.toPath()))
                .order(ByteOrder.LITTLE_ENDIAN);
        long headers = elf.getLong(0x20);
        int size = elf.getShort(0x36) & 0xffff;
        int count = elf.getShort(0x38) & 0xffff;
        List<long[]> loads = new ArrayList<>();
        long dynamic = -1;
        long dynamicSize = 0;
        for (int i = 0; i < count; i++) {
            int at = (int) (headers + (long) i * size);
            int type = elf.getInt(at);
            long offset = elf.getLong(at + 0x08);
            long vaddr = elf.getLong(at + 0x10);
            long bytes = elf.getLong(at + 0x20);
            if (type == PT_LOAD) {
                loads.add(new long[]{vaddr, offset, bytes});
            } else if (type == PT_DYNAMIC) {
                dynamic = offset;
                dynamicSize = bytes;
            }
        }
        List<String> found = new ArrayList<>();
        if (dynamic < 0) {
            return found;
        }
        List<Long> wanted = new ArrayList<>();
        long strings = -1;
        for (long at = dynamic; at < dynamic + dynamicSize; at += 16) {
            long entry = elf.getLong((int) at);
            long value = elf.getLong((int) at + 8);
            if (entry == DT_NULL) {
                break;
            }
            if (entry == tag) {
                wanted.add(value);
            } else if (entry == DT_STRTAB) {
                strings = value;
            }
        }
        if (strings < 0) {
            return found;
        }
        long table = fileOffset(loads, strings);
        for (long offset : wanted) {
            found.add(text(elf, table + offset));
        }
        return found;
    }

    private static long fileOffset(List<long[]> loads, long vaddr) {
        for (long[] load : loads) {
            if (vaddr >= load[0] && vaddr < load[0] + load[2]) {
                return vaddr - load[0] + load[1];
            }
        }
        fail("адрес " + vaddr + " не попал ни в один сегмент");
        return -1;
    }

    private static String text(ByteBuffer elf, long at) {
        StringBuilder name = new StringBuilder();
        for (int i = (int) at; elf.get(i) != 0; i++) {
            name.append((char) (elf.get(i) & 0xff));
        }
        return name.toString();
    }

    static File jniLibs() {
        return new File(mainDir(), "jniLibs/arm64-v8a");
    }

    private static File sourcesFile() {
        return new File(mainDir(), "assets/native/SOURCES.md");
    }

    private static File mainDir() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{"src/main", "app/src/main"}) {
                File main = new File(dir, candidate);
                if (new File(main, "jniLibs").isDirectory()) {
                    return main;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл src/main от " + System.getProperty("user.dir"));
        return null;
    }
}
