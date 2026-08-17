package ru.codoceh.stol.setup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Описание образа из {@code assets/image.json} (шов 1):
 *
 * <pre>
 * {"version":"2026.08.1","asset":"rootfs.tar.zst","sha256":"…",
 *  "bytes":419430400,"unpacked_bytes":2147483648,"min_android":28}
 * </pre>
 *
 * Файл наш и плоский, поэтому читается своим разбором: {@code org.json} на стороне
 * Android — заглушка в тестах на JVM, а тащить ради шести полей чужую библиотеку незачем.
 */
public final class ImageManifest {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final String version;
    private final String asset;
    private final String sha256;
    private final long bytes;
    private final long unpackedBytes;
    private final int minAndroid;

    private ImageManifest(String version, String asset, String sha256,
                          long bytes, long unpackedBytes, int minAndroid) {
        this.version = version;
        this.asset = asset;
        this.sha256 = sha256;
        this.bytes = bytes;
        this.unpackedBytes = unpackedBytes;
        this.minAndroid = minAndroid;
    }

    static ImageManifest read(InputStream json) throws InstallFailedException {
        String text;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = json.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            text = new String(buffer.toByteArray(), Charset.forName("UTF-8"));
        } catch (IOException failed) {
            throw new InstallFailedException(
                    InstallFailedException.Reason.NO_IMAGE, "Описание образа не читается", failed);
        }
        return parse(text);
    }

    static ImageManifest parse(String json) throws InstallFailedException {
        String version = text(json, "version");
        String asset = text(json, "asset");
        String sha256 = text(json, "sha256").toLowerCase(java.util.Locale.ROOT);
        long bytes = number(json, "bytes");
        long unpacked = number(json, "unpacked_bytes");
        long minAndroid = Math.max(0L, optionalNumber(json, "min_android"));
        if (!SHA256.matcher(sha256).matches()) {
            throw broken("sha256 образа не похожа на сумму: " + sha256);
        }
        if (bytes <= 0 || unpacked <= 0) {
            throw broken("в описании образа нет размеров");
        }
        return new ImageManifest(version, asset, sha256, bytes, unpacked, (int) minAndroid);
    }

    /** Версия образа — та же строка, что в отчёте сборки. */
    public String version() {
        return version;
    }

    /** Имя файла образа в assets. */
    public String asset() {
        return asset;
    }

    /** Ожидаемая sha256 сжатого образа, нижним регистром. */
    public String sha256() {
        return sha256;
    }

    /** Размер сжатого образа. */
    public long bytes() {
        return bytes;
    }

    /** Сколько займёт развёрнутый образ — по этому числу и проверяется место. */
    public long unpackedBytes() {
        return unpackedBytes;
    }

    /** Минимальная версия Android, под которую собран образ; 0, если не указана. */
    public int minAndroid() {
        return minAndroid;
    }

    private static String text(String json, String key) throws InstallFailedException {
        Matcher found = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!found.find() || found.group(1).trim().isEmpty()) {
            throw broken("в описании образа нет поля " + key);
        }
        return found.group(1).trim();
    }

    private static long number(String json, String key) throws InstallFailedException {
        long value = optionalNumber(json, key);
        if (value < 0) {
            throw broken("в описании образа нет поля " + key);
        }
        return value;
    }

    private static long optionalNumber(String json, String key) {
        Matcher found = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return found.find() ? Long.parseLong(found.group(1)) : -1L;
    }

    private static InstallFailedException broken(String what) {
        return new InstallFailedException(InstallFailedException.Reason.NO_IMAGE,
                "Образ в приложении испорчен: " + what);
    }
}
