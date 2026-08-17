package ru.codoceh.stol.setup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Кузница на экране сборки (R04.1). Проверяется не «похоже ли», а те же числа: кадр
 * считается по канону {@code ForgeAnimation.swift} из CehoIsland, перенесённому в
 * {@code ru.codoceh.desktoplauncher.ForgeView}.
 *
 * <p>Отсюда и два рода проверок. Расчётные — гоняют настоящую {@link ForgeView} по фазе и
 * сверяют огибающие и позу молота с числами канона. Построчная — берёт файл переноса из
 * соседнего приложения и требует, чтобы здешний был им же, а не новой отрисовкой на глаз:
 * владелец такую версию уже забраковал один раз.
 */
public class ForgeViewTest {

    /** Длина цикла удара в секундах — canonical {@code cycleDuration} из Swift. */
    private static final double CYCLE = 0.82;

    /** Доля цикла, на которую приходится контакт молота с наковальней. */
    private static final double IMPACT = 0.565;

    // ---------- кадр считается по канону ----------

    @Test
    public void cycleLastsTheCanonTime() {
        ForgeView forge = forge();

        assertEquals("цикл начинается не с нуля", 0.0, cycleTimeAt(forge, 0), 1e-9);
        assertEquals("середина цикла не на середине", 0.5, cycleTimeAt(forge, CYCLE / 2), 1e-9);
        assertEquals("цикл не замкнулся ровно на 0,82 с",
                0.0, cycleTimeAt(forge, CYCLE), 1e-9);
        assertEquals("третий удар считается не так, как первый",
                0.5, cycleTimeAt(forge, CYCLE * 3 + CYCLE / 2), 1e-9);
    }

    @Test
    public void blowLandsAtTheCanonInstantAndIsQuietBetweenBlows() {
        ForgeView forge = forge();

        forge.phase = CYCLE * IMPACT;
        assertEquals("удар пришёлся мимо своей доли цикла", 1.0, forge.impactEnvelope(), 1e-3);

        forge.phase = CYCLE * 0.2;
        assertEquals("между ударами наковальня не в покое",
                0.0, forge.impactEnvelope(), 1e-6);

        forge.phase = CYCLE * 0.9;
        assertEquals("после отскока удар звучит второй раз",
                0.0, forge.impactEnvelope(), 1e-6);
    }

    @Test
    public void flashFadesOutWithinTheCanonWindow() {
        ForgeView forge = forge();

        forge.phase = CYCLE * IMPACT;
        float atContact = forge.flashEnvelope();
        forge.phase = CYCLE * (IMPACT + 0.09);
        float halfway = forge.flashEnvelope();
        forge.phase = CYCLE * (IMPACT + 0.18);
        float afterwards = forge.flashEnvelope();

        assertEquals("вспышка в момент контакта не в полную силу", 1.0, atContact, 1e-3);
        assertEquals("вспышка гаснет не по канону", 0.1768, halfway, 1e-3);
        assertEquals("вспышка живёт дольше положенного", 0.0, afterwards, 1e-9);
    }

    @Test
    public void hammerMeetsTheAnvilExactlyAtTheImpactPoint() {
        ForgeView forge = forge();

        forge.phase = CYCLE * IMPACT;
        ForgeView.Pose blow = forge.forgingHammerPose();
        float angleAtContact = blow.angle;
        float xAtContact = blow.centerX;
        float yAtContact = blow.centerY;

        forge.phase = CYCLE * 0.02;
        ForgeView.Pose raised = forge.forgingHammerPose();

        assertEquals("молот встал не над точкой удара", 0.505, xAtContact, 1e-3);
        assertEquals("молот не дошёл до наковальни", 0.405, yAtContact, 1e-3);
        assertEquals("молот развернулся не так, как в каноне", 0.055, angleAtContact, 1e-3);
        assertTrue("замах не выше удара: молот не поднимается",
                raised.centerY < yAtContact);
        assertTrue("молот замахивается не назад", raised.angle < angleAtContact);
    }

    @Test
    public void aSettledForgeStopsCountingBlows() {
        ForgeView forge = forge();
        forge.settle();

        forge.phase = CYCLE * IMPACT;

        assertEquals("остывающая кузница всё ещё бьёт", 0.0, forge.impactEnvelope(), 1e-9);
        assertEquals("остывающая кузница всё ещё вспыхивает",
                0.0, forge.flashEnvelope(), 1e-9);
    }

    // ---------- кузница идёт за ходом сборки ----------

    @Test
    public void forgeWorksWhileBuildingAndSettlesWhenTheDesktopIsReady() {
        ForgeView forge = forge();

        forge.show(SetupWizard.Phase.NOT_BUILT);
        assertEquals("до сборки кузница уже машет молотом",
                ForgeView.State.AWAITING, forge.state);

        forge.show(SetupWizard.Phase.BUILDING);
        assertEquals("во время сборки кузница не работает",
                ForgeView.State.FORGING, forge.state);
        assertTrue("работающая кузница замерла", forge.animating);

        forge.show(SetupWizard.Phase.READY);
        assertEquals("собранный стол, а кузница всё кует",
                ForgeView.State.IDLE, forge.state);
        assertFalse("кузница кует после конца работы", forge.animating);
    }

    @Test
    public void aFailedBuildStopsTheHammerToo() {
        ForgeView forge = forge();
        forge.show(SetupWizard.Phase.BUILDING);

        forge.show(SetupWizard.Phase.FAILED);

        assertEquals("сборка отказала, а молот бьёт как ни в чём не бывало",
                ForgeView.State.IDLE, forge.state);
        assertFalse("отказ не остановил кузницу", forge.animating);
    }

    // ---------- это тот же перенос, а не новая отрисовка ----------

    @Test
    public void transferKeepsTheCanonNumbers() throws IOException {
        String source = read(transferred());

        for (String number : new String[]{
                "CYCLE_DURATION = 0.82",
                "IMPACT_X = 0.505f",
                "IMPACT_Y = 0.455f",
                "0.565",
                "Color.rgb(46, 140, 102)",
                "Color.rgb(95, 214, 166)",
                "Color.rgb(224, 168, 74)"}) {
            assertTrue("в переносе кузницы нет числа канона: " + number,
                    source.contains(number));
        }
    }

    @Test
    public void transferIsTheSameFileThatAlreadyForgesOnTheWaitingScreen() throws IOException {
        File canon = canonTransfer();
        Assume.assumeTrue("рядом нет приложения запуска — сверять перенос не с чем",
                canon != null);

        List<String> expected = body(read(canon));
        List<String> actual = body(read(transferred()));

        int index = 0;
        for (String line : expected) {
            int found = actual.subList(index, actual.size()).indexOf(line);
            if (found < 0) {
                fail("кузница разошлась с переносом лаунчера, строки нет: " + line.trim());
            }
            index += found + 1;
        }
        assertTrue("в переносе кузницы дописано слишком много своего: "
                        + (actual.size() - expected.size()) + " строк",
                actual.size() - expected.size() <= 25);
    }

    // ---------- снаряжение ----------

    /** Кузница без экрана: канва здесь не нужна, считаются кадры, а не пиксели. */
    private static ForgeView forge() {
        return new ForgeView(null);
    }

    private static double cycleTimeAt(ForgeView forge, double phase) {
        forge.phase = phase;
        return forge.cycleTime();
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Тело класса без шапки: пакет, импорты и javadoc у переносов разные по определению,
     * а сравнивать нужно расчёт. Ослабленная до пакетной видимость тоже не расхождение —
     * она сделана ради этой самой проверки, поэтому {@code private} снимается с обеих
     * сторон.
     */
    private static List<String> body(String source) {
        List<String> lines = new ArrayList<>();
        boolean started = false;
        for (String line : source.split("\n")) {
            if (!started) {
                started = line.contains("public class ForgeView extends View {");
                continue;
            }
            String bare = line.replace("private ", "").trim();
            if (!bare.isEmpty()) {
                lines.add(bare);
            }
        }
        assertFalse("класс кузницы не нашёлся в исходнике", lines.isEmpty());
        return lines;
    }

    private static File transferred() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++) {
            for (String candidate : new String[]{
                    "src/main/java/ru/codoceh/stol/setup/ForgeView.java",
                    "app/src/main/java/ru/codoceh/stol/setup/ForgeView.java"}) {
                File found = new File(dir, candidate);
                if (found.isFile()) {
                    return found;
                }
            }
            dir = dir.getParentFile();
        }
        fail("не нашёл кузницу от " + System.getProperty("user.dir"));
        return null;
    }

    /** Тот самый перенос, что уже кует на экране ожидания лаунчера. */
    private static File canonTransfer() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 6 && dir != null; depth++) {
            File found = new File(dir, "CodocehDesktopLauncher/app/src/main/java/"
                    + "ru/codoceh/desktoplauncher/ForgeView.java");
            if (found.isFile()) {
                return found;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}
