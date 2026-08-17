package ru.codoceh.stol.about;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Реестр заимствованных частей не должен протухнуть (R10i и R16). Тест сверяет реестр с
 * тем, что реально едет в сборке: с файлами в каталоге родных библиотек, со списком
 * библиотек java в файле сборки и с текстами условий в assets.
 *
 * <p>Смысл именно в двусторонней сверке: запись без файла — вранье на экране, файл без
 * записи — необъявленное чужое в продаваемой сборке. Оба случая красят сборку.
 *
 * <p>Отдельно заморожен список частей с сильными условиями. Появление третьей — не
 * мелочь, а разговор с владельцем, поэтому она обязана свалить сборку с внятным текстом,
 * а не проехать незамеченной.
 */
public class AboutRegistryTest {

    /**
     * Части, которые требуют открывать исходный код производных работ. Держатся
     * неизменёнными и запускаются отдельными программами: система стола едет внутри
     * приложения целиком, две другие — готовые бинарники.
     */
    private static final Set<String> KNOWN_COPYLEFT =
            new TreeSet<>(Arrays.asList("desktop-system", "starter", "talloc"));

    /** Проверять на глаз нечего: текст условий короче этого — обрывок, а не текст. */
    private static final int SHORTEST_LICENSE = 400;

    private static List<Attribution> parts;

    @BeforeClass
    public static void readRegistry() throws IOException {
        File registry = new File(assets(), Attributions.ASSET);
        assertTrue("нет реестра " + registry, registry.isFile());
        parts = Attributions.parse(read(registry));
        assertTrue("в реестре подозрительно мало частей", parts.size() >= 5);
    }

    // ---------- сверка со сборкой ----------

    @Test
    public void everyNativeFileHasAnEntryAndEveryEntryHasItsFile() {
        Set<String> recorded = shipped(Attribution.Kind.NATIVE);
        Set<String> onDisk = new TreeSet<>();
        File[] files = nativeDir().listFiles();
        assertNotNull("не нашёлся каталог родных библиотек", files);
        for (File file : files) {
            onDisk.add(file.getName());
        }
        assertEquals("реестр разошёлся с каталогом родных библиотек: запись без файла — "
                + "вранье на экране, файл без записи — необъявленное чужое в сборке",
                onDisk, recorded);
    }

    @Test
    public void everyShippedLibraryHasAnEntryAndEveryEntryIsShipped() throws IOException {
        Set<String> recorded = shipped(Attribution.Kind.LIBRARY);
        Set<String> inBuild = new TreeSet<>();
        Matcher found = Pattern.compile(
                "(?m)^\\s*(?:implementation|api|runtimeOnly)[\\s(]+['\"]([^'\"]+)['\"]")
                .matcher(read(new File(moduleDir(), "build.gradle")));
        while (found.find()) {
            String[] coordinate = found.group(1).split(":");
            assertTrue("непонятная зависимость " + found.group(1), coordinate.length >= 2);
            inBuild.add(coordinate[0] + ":" + coordinate[1]);
        }
        assertFalse("в файле сборки не нашлось ни одной библиотеки", inBuild.isEmpty());
        assertEquals("реестр разошёлся с зависимостями сборки: новая библиотека едет в "
                + "приложении, а на экране «О программе» её нет", inBuild, recorded);
    }

    @Test
    public void nativeFilesAgreeWithTheirProvenanceRecord() throws IOException {
        String sources = read(new File(assets(), "native/SOURCES.md"));
        for (Attribution part : parts) {
            if (part.kind() != Attribution.Kind.NATIVE) {
                continue;
            }
            for (String file : part.ships()) {
                assertTrue("в описании родных бинарников нет записи про " + file,
                        sources.contains(file));
            }
        }
    }

    // ---------- тексты условий лежат внутри приложения ----------

    @Test
    public void everyPartShipsFullLicenseTextInsideTheApplication() {
        for (Attribution part : parts) {
            assertFalse("у части " + part.id() + " не сказано, где текст условий",
                    part.licenseTexts().isEmpty());
            long total = 0;
            for (String path : part.licenseTexts()) {
                File at = new File(assets(), path);
                assertTrue("у части " + part.id() + " текста условий нет в приложении: "
                        + path, at.exists());
                for (File text : texts(at)) {
                    assertTrue("файл условий " + text.getName() + " у части " + part.id()
                            + " пуст", text.length() > 0);
                    total += text.length();
                }
            }
            // Рядом с условиями лежат короткие обязательные приписки вроде NOTICE, поэтому
            // мерится всё, что показывает экран, а не каждый файл по отдельности.
            assertTrue("условия части " + part.id() + " короче, чем бывают условия: " + total,
                    total >= SHORTEST_LICENSE);
        }
    }

    @Test
    public void noLicenseTextLiesInTheApplicationUnclaimed() {
        Set<String> claimed = new LinkedHashSet<>();
        for (Attribution part : parts) {
            claimed.addAll(part.licenseTexts());
        }
        for (String root : new String[]{"native/licenses", "about/licenses"}) {
            File[] folders = new File(assets(), root).listFiles();
            assertNotNull("нет каталога условий " + root, folders);
            for (File folder : folders) {
                assertTrue("текст условий " + root + "/" + folder.getName()
                                + " лежит в приложении, а в реестре его никто не назвал",
                        claimed.contains(root + "/" + folder.getName()));
            }
        }
    }

    // ---------- право ----------

    @Test
    public void partsWithStrongCopyleftAreExactlyTheKnownOnes() {
        Set<String> copyleft = new TreeSet<>();
        for (Attribution part : parts) {
            if (part.isCopyleft()) {
                copyleft.add(part.id());
            }
        }
        assertEquals("список частей с сильными условиями изменился. Это не правка реестра,"
                        + " а разговор с владельцем: каждая такая часть обязана лежать"
                        + " неизменённой и запускаться отдельной программой, иначе"
                        + " сборку нельзя продавать (R16)",
                KNOWN_COPYLEFT, copyleft);
    }

    @Test
    public void nothingWithStrongCopyleftIsModifiedByUs() {
        for (Attribution part : parts) {
            if (part.isCopyleft()) {
                assertFalse("часть " + part.id() + " с сильными условиями помечена"
                        + " изменённой: так сборку продавать нельзя (R16.2)",
                        part.isModified());
            }
        }
    }

    @Test
    public void everyPartNamesItsAuthorLicenseAndSourceCode() {
        for (Attribution part : parts) {
            assertFalse(part.id() + ": не сказано, чья работа", part.author().isEmpty());
            assertFalse(part.id() + ": не сказано, на каких условиях", part.license().isEmpty());
            assertTrue(part.id() + ": адрес исходного кода не адрес: " + part.source(),
                    part.source().startsWith("https://"));
            assertFalse(part.id() + ": не сказано, что это за кусок", part.note().isEmpty());
        }
    }

    // ---------- экран показывает то, что записано ----------

    @Test
    public void theScreenShowsEveryRecordedField() throws IOException {
        String screen = read(new File(sourceDir(), "AboutActivity.java"));
        for (String field : new String[]{"name()", "note()", "author()", "license()",
                "isModified()", "source()", "licenseTexts()"}) {
            assertTrue("экран «О программе» не показывает " + field, screen.contains(field));
        }
    }

    @Test
    public void everyCaptionOfTheScreenIsUsedAndSaidInRussian() throws IOException {
        String screen = read(new File(sourceDir(), "AboutActivity.java"));
        String strings = read(new File(resDir(), "values/strings.xml"));
        Matcher found = Pattern.compile("<string\\s+name=\"(about_[^\"]+)\"[^>]*>(.*?)</string>",
                Pattern.DOTALL).matcher(strings);
        int captions = 0;
        while (found.find()) {
            captions++;
            String name = found.group(1);
            String text = found.group(2).trim();
            assertFalse("подпись " + name + " пустая", text.isEmpty());
            assertTrue("подпись " + name + " не по-русски: " + text,
                    Pattern.compile("[а-яА-ЯёЁ]").matcher(text).find());
            assertTrue("подпись " + name + " написана, но на экране не показывается",
                    screen.contains("R.string." + name));
        }
        assertTrue("подписей экрана не нашлось", captions >= 10);
    }

    /**
     * Имена чужих работ на экране — не жаргон, но и в строковых ресурсах им не место:
     * там их ловит словарная проверка мастера. Они приходят из реестра.
     */
    @Test
    public void namesOfBorrowedPartsComeFromTheRegistryAndNotFromResources() throws IOException {
        String strings = read(new File(resDir(), "values/strings.xml")).toLowerCase(Locale.ROOT);
        for (Attribution part : parts) {
            String name = part.name().split(" ")[0].toLowerCase(Locale.ROOT);
            assertFalse("имя чужой работы «" + name + "» попало в строковые ресурсы",
                    strings.contains(name.toLowerCase(Locale.ROOT)));
        }
    }

    // ---------- вспомогательное ----------

    private static Set<String> shipped(Attribution.Kind kind) {
        Set<String> names = new TreeSet<>();
        for (Attribution part : parts) {
            if (part.kind() == kind) {
                names.addAll(part.ships());
            }
        }
        return names;
    }

    private static List<File> texts(File at) {
        List<File> found = new ArrayList<>();
        if (at.isDirectory()) {
            File[] children = at.listFiles();
            assertNotNull("каталог условий пуст: " + at, children);
            assertTrue("каталог условий пуст: " + at, children.length > 0);
            found.addAll(Arrays.asList(children));
        } else {
            found.add(at);
        }
        return found;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File assets() {
        return new File(mainDir(), "assets");
    }

    private static File resDir() {
        return new File(mainDir(), "res");
    }

    private static File sourceDir() {
        return new File(mainDir(), "java/ru/codoceh/stol/about");
    }

    private static File nativeDir() {
        return new File(mainDir(), "jniLibs/arm64-v8a");
    }

    private static File mainDir() {
        return new File(moduleDir(), "src/main");
    }

    private static File moduleDir() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{".", "app"}) {
                File module = new File(dir, candidate);
                if (new File(module, "src/main/jniLibs").isDirectory()) {
                    return module;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл модуль приложения от " + System.getProperty("user.dir"));
        return null;
    }
}
