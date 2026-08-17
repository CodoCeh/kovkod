package ru.codoceh.stol.launch;

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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Словарь экрана ожидания и запуска стола (R18.4, R18.5).
 */
public class LaunchWordsTest {

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

        byId = new HashMap<>();
        for (Field field : Class.forName("ru.codoceh.stol.R$string").getFields()) {
            String text = byName.get(field.getName());
            if (text != null) {
                byId.put(field.getInt(null), text);
            }
        }
    }

    @Test
    public void everyLaunchStateHasHumanTitle() {
        for (DesktopLauncher.Phase phase : DesktopLauncher.Phase.values()) {
            DesktopLauncher.State state = phase == DesktopLauncher.Phase.FAILED
                    ? DesktopLauncher.State.failed(DesktopLauncher.Failure.TIMEOUT)
                    : DesktopLauncher.State.starting();
            int titleRes = LaunchTexts.title(state);
            assertHuman("заголовок " + phase, titleRes);
        }
    }

    @Test
    public void everyFailureNamesCauseAndAction() {
        for (DesktopLauncher.Failure failure : DesktopLauncher.Failure.values()) {
            int causeRes = LaunchTexts.failureCause(failure);
            int actionRes = LaunchTexts.failureAction(failure);
            String cause = assertHuman("причина " + failure, causeRes);
            String action = assertHuman("действие " + failure, actionRes);

            assertFalse("причина пустая", cause.isEmpty());
            assertFalse("действие пустое", action.isEmpty());
            assertFalse("причина и действие одинаковые", cause.equals(action));
            assertTrue("действие не зовёт ничего сделать",
                    Pattern.compile("(Нажмите|Освободите|Перезапустите)").matcher(action).find());
        }
    }

    @Test
    public void everyActionHasHumanLabel() {
        for (DesktopLauncher.Action action : DesktopLauncher.Action.values()) {
            int actionRes = LaunchTexts.action(action);
            assertHuman("кнопка " + action, actionRes);
        }
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
                    return found;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл ресурсы от " + System.getProperty("user.dir"));
        return null;
    }
}
