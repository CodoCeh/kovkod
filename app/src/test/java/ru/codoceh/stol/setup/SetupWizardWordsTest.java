package ru.codoceh.stol.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Словарь мастера (R18.4 и R18.5). Тест читает строковые ресурсы приложения и падает на
 * жаргоне, на номерах портов, на путях файловой системы, на «ты» и на отказе без
 * следующего действия.
 *
 * <p>Смысл проверки — пережить нас: слова человеческие держатся не аккуратностью того,
 * кто пишет строку сегодня, а тем, что сборка краснеет от неаккуратной правки завтра.
 */
public class SetupWizardWordsTest {

    /** Слова, которых человек на экране видеть не должен (R18.4). */
    private static final String[] JARGON = {
            "proot", "rootfs", "vnc", "rfb", "chroot", "sudo", "apt",
            "tigervnc", "xfce", "zstd", "tar", "dpkg", "adb", "x11"
    };

    /** Обращение только на «вы»: повелительное наклонение на «ты» запрещено. */
    private static final Pattern FAMILIAR = Pattern.compile(
            "\\b(нажми|подожди|попробуй|освободи|установи|перезапусти|введи|открой|закрой"
                    + "|включи|выключи|скачай|проверь|смотри|подключи)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Номер порта и адрес петли: человеку они ничего не говорят. */
    private static final Pattern PORT = Pattern.compile(":\\d{2,5}\\b|\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");

    /** Путь файловой системы: «/etc», «/opt/stol», «/data/data/…». */
    private static final Pattern PATH = Pattern.compile("(?<![\\w:/%$.])/[a-zA-Z]{2,}");

    /** Отказ без причины: «неизвестная ошибка» и голые коды возврата (R18.5). */
    private static final Pattern FACELESS = Pattern.compile(
            "неизвестн|непредвиденн|ошибка\\s*[№#]|код\\s*(ошибки|возврата)|errno|exit code",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static Map<String, String> byName;
    private static Map<Integer, String> byId;

    @BeforeClass
    public static void readResources() throws Exception {
        byName = new LinkedHashMap<>();
        for (File file : stringFiles()) {
            String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher found = Pattern.compile(
                    "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL)
                    .matcher(xml);
            while (found.find()) {
                byName.put(found.group(1), found.group(2).trim());
            }
        }
        assertTrue("строковые ресурсы не прочитались", byName.size() > 10);

        byId = new HashMap<>();
        for (Field field : Class.forName("ru.codoceh.stol.R$string").getFields()) {
            String text = byName.get(field.getName());
            if (text != null) {
                byId.put(field.getInt(null), text);
            }
        }
        assertEquals("не все строки нашлись в R", byName.size(), byId.size());
    }

    // ---------- словарь ----------

    @Test
    public void noJargonOnAnyScreen() {
        for (Map.Entry<String, String> string : byName.entrySet()) {
            String text = string.getValue().toLowerCase(Locale.ROOT);
            for (String word : JARGON) {
                assertFalse("строка " + string.getKey() + " показывает человеку жаргон «"
                                + word + "»: " + string.getValue(),
                        Pattern.compile("\\b" + word + "\\b").matcher(text).find());
            }
        }
    }

    @Test
    public void noPortsAndNoFilesystemPathsOnAnyScreen() {
        for (Map.Entry<String, String> string : byName.entrySet()) {
            assertFalse("строка " + string.getKey() + " показывает номер порта: "
                            + string.getValue(),
                    PORT.matcher(string.getValue()).find());
            assertFalse("строка " + string.getKey() + " показывает путь: " + string.getValue(),
                    PATH.matcher(string.getValue()).find());
        }
    }

    @Test
    public void everyStringIsRussianAndAddressesTheReaderPolitely() {
        for (Map.Entry<String, String> string : byName.entrySet()) {
            assertTrue("строка " + string.getKey() + " не по-русски: " + string.getValue(),
                    Pattern.compile("[а-яА-ЯёЁ]").matcher(string.getValue()).find());
            assertFalse("строка " + string.getKey() + " обращается на «ты»: "
                            + string.getValue(),
                    FAMILIAR.matcher(string.getValue()).find());
        }
    }

    @Test
    public void noScreenAsksToTypeACommand() {
        for (Map.Entry<String, String> string : byName.entrySet()) {
            String text = string.getValue().toLowerCase(Locale.ROOT);
            for (String word : new String[]{"команду", "терминал", "консоль", "наберите",
                    "введите команду", "$ ", "#!"}) {
                assertFalse("строка " + string.getKey() + " зовёт человека в терминал: "
                        + string.getValue(), text.contains(word));
            }
        }
    }

    // ---------- каждое состояние названо ----------

    @Test
    public void everyPhaseHasATitleAndAHint() {
        for (SetupWizard.Phase phase : SetupWizard.Phase.values()) {
            assertHuman("заголовок " + phase, SetupTexts.title(phase));
            if (phase == SetupWizard.Phase.FAILED) {
                assertEquals("у отказа подпись складывается из причины и действия",
                        0, SetupTexts.hint(phase));
            } else {
                assertHuman("подпись " + phase, SetupTexts.hint(phase));
            }
        }
    }

    @Test
    public void everyBuildingStepHasAHumanCaption() {
        List<String> captions = new ArrayList<>();
        for (InstallProgress.Stage stage : InstallProgress.Stage.values()) {
            String caption = assertHuman("подпись шага " + stage, SetupTexts.step(stage));
            assertFalse("шаг " + stage + " назван теми же словами, что предыдущий",
                    captions.contains(caption));
            captions.add(caption);
        }
        assertEquals(SetupWizard.TOTAL_STEPS, captions.size());
        assertTrue("нет счётчика «Шаг N из N»",
                assertHuman("счётчик шагов", SetupTexts.stepCounter()).contains("%1$d"));
    }

    @Test
    public void everyActionHasALabel() {
        for (SetupWizard.Action action : SetupWizard.Action.values()) {
            assertHuman("надпись на кнопке " + action, SetupTexts.action(action));
        }
    }

    // ---------- каждый отказ назван причиной и действием ----------

    @Test
    public void everyFailureNamesItsCauseAndWhatToDoNext() {
        for (InstallFailedException.Reason reason : InstallFailedException.Reason.values()) {
            String cause = assertHuman("причина отказа " + reason,
                    SetupTexts.failureCause(reason));
            String next = assertHuman("следующее действие при отказе " + reason,
                    SetupTexts.failureAction(reason));
            assertFalse("отказ " + reason + " не объясняет причину: " + cause,
                    FACELESS.matcher(cause).find());
            assertFalse("отказ " + reason + " не говорит, что делать: " + next,
                    FACELESS.matcher(next).find());
            assertFalse("причина и действие при отказе " + reason + " — одно и то же",
                    cause.equals(next));
            assertTrue("действие при отказе " + reason + " не просит ничего сделать: " + next,
                    Pattern.compile("(Установите|Освободите|Нажмите|Перезапустите)")
                            .matcher(next).find());
        }
    }

    @Test
    public void notEnoughSpaceIsSaidWithBothNumbers() {
        String cause = byId.get(SetupTexts.failureCause(
                InstallFailedException.Reason.NOT_ENOUGH_SPACE));
        assertTrue("нехватка места названа без чисел: " + cause,
                cause.contains("%1$s") && cause.contains("%2$s"));
        assertEquals("В памяти телефона не хватает места: нужно 2,0 ГБ, а свободно 300,0 МБ.",
                String.format(Locale.forLanguageTag("ru"), cause,
                        InstallFailedException.human(2L * 1024 * 1024 * 1024),
                        InstallFailedException.human(300L * 1024 * 1024)));
    }

    private static String assertHuman(String what, int resource) {
        assertTrue(what + ": строки нет", resource != 0);
        String text = byId.get(resource);
        assertNotNull(what + ": ресурс не нашёлся среди строк приложения", text);
        assertFalse(what + ": строка пустая", text.isEmpty());
        return text;
    }

    private static List<File> stringFiles() throws IOException {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{"src/main/res", "app/src/main/res"}) {
                File res = new File(dir, candidate);
                if (res.isDirectory()) {
                    List<File> found = new ArrayList<>();
                    File[] values = res.listFiles();
                    assertNotNull(values);
                    for (File folder : values) {
                        File strings = new File(folder, "strings.xml");
                        if (strings.isFile()) {
                            found.add(strings);
                        }
                    }
                    assertFalse("strings.xml не найден в " + res, found.isEmpty());
                    return found;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл ресурсы от " + System.getProperty("user.dir"));
        return null;
    }
}
