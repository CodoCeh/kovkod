package ru.codoceh.stol.about;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Проверка письменного предложения выдать исходный код (R16b, R16.5).
 */
public class SourceOfferTest {

    @Test
    public void sourceOfferAssetExistsAndIsNotEmpty() throws IOException {
        File file = new File(assetsDir(), SourceOffer.OFFER_ASSET);
        assertTrue("нет файла " + file, file.isFile());
        String text = read(file);
        assertTrue("текст предложения слишком короткий", text.length() > 500);
        assertTrue("текст не по-русски", Pattern.compile("[а-яА-ЯёЁ]").matcher(text).find());
        assertTrue("в предложении не упомянута лицензия GPL", text.contains("GNU GPL"));
        assertTrue("в предложении не упомянут 3-летний срок", text.contains("три года"));
        assertTrue("в предложении нет адреса/заглушки для обращений",
                text.contains("Куда обращаться"));
    }

    @Test
    public void packagesParserAcceptsValidFormatAndFiltersComments() {
        String input = "# Заголовок списка пакетов\n"
                + "\n"
                + "bash 5.2.15-2+b7\n"
                + "coreutils 9.4-3.1\n"
                + "# комментарий в середине\n"
                + "dpkg 1.22.6\n";

        List<String> pkgs = SourceOffer.packages(input);
        assertEquals(Arrays.asList("bash 5.2.15-2+b7", "coreutils 9.4-3.1", "dpkg 1.22.6"), pkgs);
    }

    @Test(expected = IllegalArgumentException.class)
    public void packagesParserRejectsInvalidEntry() {
        SourceOffer.packages("invalid_line_without_version\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void packagesParserRejectsEmptyList() {
        SourceOffer.packages("# Only comments\n\n");
    }

    @Test
    public void readHelperReadsStreamCorrectly() throws IOException {
        String sample = "Привет, мир!\nВторая строка.";
        ByteArrayInputStream in = new ByteArrayInputStream(sample.getBytes(StandardCharsets.UTF_8));
        assertEquals(sample, SourceOffer.read(in));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File assetsDir() {
        return new File(moduleDir(), "src/main/assets");
    }

    private static File moduleDir() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{".", "app"}) {
                File module = new File(dir, candidate);
                if (new File(module, "src/main/assets").isDirectory()) {
                    return module;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл модуль приложения от " + System.getProperty("user.dir"));
        return null;
    }
}
