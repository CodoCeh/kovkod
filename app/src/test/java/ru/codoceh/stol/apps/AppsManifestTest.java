package ru.codoceh.stol.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * Задача 11: {@code assets/apps.json} — набор программ эталонного стола (R04, R13i.2).
 * Проверяется настоящий файл из сборки, а не выдуманная строка: движок исполняет именно
 * его, и порча файла обязана краснеть здесь, а не на телефоне человека.
 *
 * <p>Пути установки не наши: значки стола образ кладёт из {@code reference/desktop-entries}
 * как есть, поэтому {@code target} обязан совпадать с тем, что написано в эталонном ярлыке
 * ({@code /opt/zcode-app}, {@code /opt/obsidian-app}, {@code /opt/Antigravity-arm64}) —
 * иначе значок на столе будет указывать в пустоту.
 *
 * <p>Проверка адреса двухслойная. Без сети слой имени всё равно ловит подсунутую
 * x86-ссылку, а слой содержимого честно помечается пропущенным вместо ложного зелёного:
 * {@link Assume} различает «сети нет» и «источник ответил не тем».
 */
public class AppsManifestTest {

    /** Значки эталонного стола — тем же порядком, что в AGENTS.md. */
    private static final List<String> DESK = Collections.unmodifiableList(Arrays.asList(
            "Antigravity IDE", "Antigravity 2.0", "Claude", "Visual Studio Code",
            "Obsidian", "ZCode", "Gemini"));

    /**
     * Помеченный пропуск, а не забытая программа. У Antigravity IDE 2.5.5 официальная
     * сборка под Linux одна — {@code .tar.gz} (проверено: {@code .deb}, {@code .rpm} и
     * AppImage у неё нет ни под arm64, ни под x64), а видов установки четыре и архива
     * среди них нет. Последний {@code .deb} этой линейки — 1.23.2 из репозитория Google —
     * не годится: он зовётся тем же пакетом {@code antigravity}, что и Antigravity 2.0, и
     * ложится в {@code /usr/share/antigravity}, мимо {@code /opt/Antigravity IDE} из
     * эталонного ярлыка. Появится вид «архив» — программа добавляется строкой в файл, и
     * этот тест обязан покраснеть, чтобы отметку сняли.
     */
    private static final String WITHOUT_A_PACKAGE = "Antigravity IDE";

    /** Сколько читаем от источника: заголовка хватает, качать сотни мегабайт незачем. */
    private static final int PROBE_BYTES = 4096;

    private static final int ELF_CLASS_64 = 2;
    private static final int ELF_DATA_LSB = 1;
    private static final int EM_AARCH64 = 183;

    private static List<AppSpec> apps;

    @BeforeClass
    public static void readTheFileTheEngineWillRead() throws IOException {
        try (InputStream json = new FileInputStream(manifest())) {
            apps = AppsCatalog.read(json);
        }
    }

    @Test
    public void holdsTheProgramsOfTheDeskInTheOrderOfTheDesk() {
        List<String> expected = new ArrayList<>(DESK);
        expected.remove(WITHOUT_A_PACKAGE);
        assertEquals("набор программ разошёлся с эталонным столом", expected, names());
    }

    /** Отметка пропуска живёт в тесте: молча потерянную программу это ловит, тихо добавленную — тоже. */
    @Test
    public void theOnlyProgramLeftOutIsTheOneWithoutAPackage() {
        List<String> missing = new ArrayList<>(DESK);
        missing.removeAll(names());
        assertEquals("список отличается от стола не только помеченным пропуском",
                Collections.singletonList(WITHOUT_A_PACKAGE), missing);
    }

    @Test
    public void everyKindComesFromTheClosedList() {
        for (AppSpec app : apps) {
            assertEquals("вид установки " + app.id() + " не из закрытого списка",
                    app.kind(), AppSpec.Kind.of(app.kind().word()));
        }
    }

    @Test
    public void everyProgramCarriesWhatItsKindNeeds() {
        for (AppSpec app : apps) {
            assertTrue(app.id() + ": адрес не по защищённому протоколу: " + app.url(),
                    app.url().startsWith("https://"));
            switch (app.kind()) {
                case APPIMAGE:
                    assertTrue(app.id() + ": некуда раскладывать: " + app.target(),
                            app.target().startsWith("/"));
                    assertTrue(app.id() + ": значок не внутри " + app.target()
                                    + ", будет общий: " + app.icon(),
                            app.icon().startsWith(app.target() + "/"));
                    assertTrue(app.id() + ": ярлык для распакованной программы пишем мы,"
                            + " имя пакета тут выдумка", app.desktop().isEmpty());
                    break;
                case WEBAPP:
                    assertTrue(app.id() + ": окну негде держать свои данные: " + app.profile(),
                            app.profile().startsWith("/"));
                    assertTrue(app.id() + ": ярлык окна пишем мы", app.desktop().isEmpty());
                    break;
                case DEB:
                    assertTrue(app.id() + ": ярлык приносит пакет, его имя обязано быть"
                                    + " названо: " + app.desktop(),
                            app.desktop().endsWith(".desktop"));
                    break;
                default:
                    fail(app.id() + ": вид " + app.kind() + " в этом наборе не ожидался");
            }
        }
    }

    /**
     * Четыре ключа запуска обязательны там, где ярлык пишем мы: без них окно Electron под
     * proot не появляется вовсе. У пакетов ярлык свой — на эталонном столе владельца
     * {@code code.desktop} и ярлык Claude живут без этих ключей и работают, поэтому чужой
     * ярлык мы не переписываем и не проверяем на них.
     */
    @Test
    public void everyProgramWeLaunchOurselvesCarriesTheFourFlags() {
        AppInstaller installer = new AppInstaller(apps, new File("."), command -> 0, () -> true);
        int checked = 0;
        for (AppSpec app : apps) {
            if (!app.desktop().isEmpty()) {
                continue;
            }
            String launcher = installer.installCommand(app);
            for (String flag : AppInstaller.REQUIRED_FLAGS) {
                assertTrue(app.id() + ": в ярлыке нет ключа " + flag,
                        launcher.contains(flag));
                assertTrue(app.id() + ": ключ " + flag + " не назван в самом списке программ",
                        app.execFlags().contains(flag));
            }
            checked++;
        }
        assertEquals("программы, которым мы пишем ярлык, куда-то делись", 4, checked);
    }

    /** Слой имени: работает без сети и ловит подсунутую x86-ссылку. */
    @Test
    public void everyAddressIsNamedArm64() {
        for (AppSpec app : apps) {
            if (!app.kind().needsDownload()) {
                continue;
            }
            String address = app.url().toLowerCase(Locale.ROOT);
            assertTrue(app.id() + ": в адресе не сказано arm64: " + app.url(),
                    address.contains("arm64") || address.contains("linux-arm"));
            assertFalse(app.id() + ": в адресе стоит чужая архитектура: " + app.url(),
                    address.contains("amd64") || address.contains("x86") || address.contains("x64"));
        }
    }

    /**
     * Слой содержимого (R13i.2): «страница существует» доказательством не считается.
     * У AppImage читается заголовок ELF — это прямое доказательство arm64. У пакета
     * Debian архитектура лежит в {@code control.tar.xz}, а распаковщика xz на этой стороне
     * нет, поэтому доказываются две вещи послабее: источник отдаёт настоящий пакет Debian
     * и сам производитель называет отданный файл arm64 (адрес после перенаправлений).
     */
    @Test
    public void everySourceServesAnArm64Build() throws IOException {
        for (AppSpec app : apps) {
            if (!app.kind().needsDownload()) {
                continue;
            }
            Head head = head(app.url());
            if (app.kind() == AppSpec.Kind.APPIMAGE) {
                assertAppImageForArm64(app, head.bytes);
            } else {
                assertDebianPackage(app, head.bytes);
                assertTrue(app.id() + ": производитель отдал файл не arm64: " + head.address,
                        head.address.toLowerCase(Locale.ROOT).contains("arm64"));
            }
        }
    }

    // ---------- проверки содержимого ----------

    private static void assertAppImageForArm64(AppSpec app, byte[] head) {
        assertTrue(app.id() + ": отдан не исполняемый образ",
                head.length > 20 && head[0] == 0x7f && head[1] == 'E'
                        && head[2] == 'L' && head[3] == 'F');
        assertEquals(app.id() + ": образ не 64-разрядный", ELF_CLASS_64, head[4]);
        assertEquals(app.id() + ": образ с обратным порядком байтов", ELF_DATA_LSB, head[5]);
        assertTrue(app.id() + ": это не AppImage — нечего распаковывать",
                head[8] == 'A' && head[9] == 'I' && head[10] == 2);
        int machine = (head[18] & 0xff) | ((head[19] & 0xff) << 8);
        assertEquals(app.id() + ": сборка не под arm64", EM_AARCH64, machine);
    }

    private static void assertDebianPackage(AppSpec app, byte[] head) {
        String start = new String(head, 0, Math.min(head.length, 24),
                java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(app.id() + ": источник отдал не пакет Debian: " + start.trim(),
                start.startsWith("!<arch>\ndebian-binary"));
    }

    // ---------- сеть ----------

    /** Начало файла и адрес, на котором источник остановился после перенаправлений. */
    private static final class Head {
        final String address;
        final byte[] bytes;

        Head(String address, byte[] bytes) {
            this.address = address;
            this.bytes = bytes;
        }
    }

    private static Head head(String address) throws IOException {
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Range", "bytes=0-" + (PROBE_BYTES - 1));
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect();
                fail("источник " + address + " ответил " + code);
            }
        } catch (UnknownHostException | SocketException | SocketTimeoutException
                | SSLException offline) {
            // Сети нет — проверка не проведена, и это говорится вслух. Молча зеленеть
            // здесь опаснее, чем пропустить: зелёный без сети означал бы, что адреса
            // проверены, а они не проверены.
            Assume.assumeNoException("сети нет, содержимое источника не проверено: " + address,
                    offline);
            throw new AssertionError("недостижимо");
        }
        try (InputStream body = connection.getInputStream()) {
            byte[] buffer = new byte[PROBE_BYTES];
            int done = 0;
            while (done < buffer.length) {
                int read = body.read(buffer, done, buffer.length - done);
                if (read < 0) {
                    break;
                }
                done += read;
            }
            return new Head(connection.getURL().toString(), Arrays.copyOf(buffer, done));
        } finally {
            connection.disconnect();
        }
    }

    // ---------- где лежит файл ----------

    private static List<String> names() {
        List<String> names = new ArrayList<>();
        for (AppSpec app : apps) {
            names.add(app.name());
        }
        return names;
    }

    private static File manifest() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{"src/main/assets", "app/src/main/assets"}) {
                File file = new File(new File(dir, candidate), AppsCatalog.ASSET);
                if (file.isFile()) {
                    return file;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл " + AppsCatalog.ASSET + " от " + System.getProperty("user.dir"));
        return null;
    }
}
