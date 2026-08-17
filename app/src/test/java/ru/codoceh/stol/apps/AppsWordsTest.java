package ru.codoceh.stol.apps;

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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Слова про программы (R18.4 и R18.5). Общий словарный запрет по всем строкам приложения
 * держит проверка мастера; здесь — что у каждого итога установки есть человеческое имя, а
 * у каждого отказа названы причина и следующее действие.
 *
 * <p>Отказ без следующего действия — самый вероятный способ уронить простоту незаметно:
 * состояний три, причин четыре, и текст к ним пишется в разное время разными руками.
 */
public class AppsWordsTest {

    /** Отказ без причины: «неизвестная ошибка» и голые коды возврата. */
    private static final Pattern FACELESS = Pattern.compile(
            "неизвестн|непредвиденн|ошибка\\s*[№#]|код\\s*(ошибки|возврата)|errno|exit code",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Следующее действие названо глаголом, обращённым к человеку на «вы». */
    private static final Pattern NEXT_STEP =
            Pattern.compile("(Нажмите|Подключите|Подключитесь|Освободите|Установите|Повторите)");

    private static Map<Integer, String> byId;

    @BeforeClass
    public static void readResources() throws Exception {
        Map<String, String> byName = new HashMap<>();
        for (File file : stringFiles()) {
            String xml = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher found = Pattern.compile(
                    "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL)
                    .matcher(xml);
            while (found.find()) {
                byName.put(found.group(1), found.group(2).trim());
            }
        }
        byId = new HashMap<>();
        for (Field field : Class.forName("ru.codoceh.stol.R$string").getFields()) {
            String text = byName.get(field.getName());
            if (text != null) {
                byId.put(field.getInt(null), text);
            }
        }
        assertFalse("строковые ресурсы не прочитались", byId.isEmpty());
    }

    @Test
    public void everyOutcomeOfAnInstallIsNamedHumanly() {
        List<String> names = new ArrayList<>();
        for (AppResult.State state : AppResult.State.values()) {
            String name = human("итог " + state, AppsTexts.state(state));
            assertFalse("итог " + state + " назван теми же словами, что другой",
                    names.contains(name));
            names.add(name);
        }
        human("надпись на кнопке установки", AppsTexts.action());
    }

    @Test
    public void everyRefusalNamesItsCauseAndWhatToDoNext() {
        for (AppInstallException.Reason reason : AppInstallException.Reason.values()) {
            String cause = human("причина отказа " + reason, AppsTexts.failureCause(reason));
            String next = human("действие при отказе " + reason, AppsTexts.failureAction(reason));
            assertFalse("отказ " + reason + " не объясняет причину: " + cause,
                    FACELESS.matcher(cause).find());
            assertFalse("причина и действие при отказе " + reason + " — одно и то же",
                    cause.equals(next));
            assertTrue("действие при отказе " + reason + " не просит ничего сделать: " + next,
                    NEXT_STEP.matcher(next).find());
        }
    }

    /** Отложенная программа — не отказ, но и молчать о ней нельзя. */
    @Test
    public void programSetAsideUntilInternetIsExplainedTheSameWay() {
        String cause = human("причина откладывания", AppsTexts.skippedCause());
        String next = human("действие при откладывании", AppsTexts.skippedAction());
        assertFalse(cause.equals(next));
        assertFalse("откладывание названо отказом: " + cause, FACELESS.matcher(cause).find());
        assertTrue("не сказано, что сделать: " + next, NEXT_STEP.matcher(next).find());
    }

    /** Разные причины — разные слова: одинаковый текст скрывает разницу, а не объясняет её. */
    @Test
    public void causesDoNotRepeatEachOther() {
        List<String> causes = new ArrayList<>();
        for (AppInstallException.Reason reason : AppInstallException.Reason.values()) {
            String cause = byId.get(AppsTexts.failureCause(reason));
            assertFalse("причина " + reason + " повторяет чужую: " + cause,
                    causes.contains(cause));
            causes.add(cause);
        }
    }

    private static String human(String what, int resource) {
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
