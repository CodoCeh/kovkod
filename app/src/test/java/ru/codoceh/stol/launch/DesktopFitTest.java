package ru.codoceh.stol.launch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Числа, которыми стол ложится на экран. Здесь проверяется ровно причина жалобы: размер
 * X-сервера обязан совпадать с размером вида, иначе картинку вписывают с интерполяцией.
 */
public class DesktopFitTest {

    /** Экран владельца: Galaxy Fold, 1248×1972 за вычетом системных полос. */
    @Test
    public void desktopIsRaisedInTheSizeOfTheView() {
        DesktopFit fit = DesktopFit.forView(1248, 1846, 360);

        assertEquals(1248, fit.width());
        assertEquals(1846, fit.height());
        assertEquals("1248x1846", fit.geometry());
    }

    /**
     * Масштаб интерфейса — множитель самого Android: точка разметки там 1/160 дюйма.
     * Плотность 360 даёт 2,25, плотность 420 — 2,625; оба упираются в потолок 2.
     */
    @Test
    public void interfaceScaleFollowsScreenDensity() {
        assertEquals(2, DesktopFit.forView(1248, 1846, 360).uiScale());
        assertEquals(2, DesktopFit.forView(1080, 2274, 420).uiScale());
        assertEquals(1, DesktopFit.forView(1280, 736, 160).uiScale());
        assertEquals(1, DesktopFit.forView(1280, 736, 200).uiScale());
        assertEquals(2, DesktopFit.forView(1280, 736, 240).uiScale());
    }

    /** Потолок и пол: за них не выходим ни при какой плотности. */
    @Test
    public void scaleStaysBetweenOneAndTwo() {
        assertEquals(DesktopFit.MAX_SCALE, DesktopFit.forView(800, 600, 640).uiScale());
        assertEquals(DesktopFit.MIN_SCALE, DesktopFit.forView(800, 600, 0).uiScale());
    }

    /** Размер тоже с ограничителями: в вырожденный вид X-сервер не поднять. */
    @Test
    public void sizeStaysWithinLimits() {
        assertEquals(DesktopFit.MIN_SIDE, DesktopFit.forView(0, 0, 320).width());
        assertEquals(DesktopFit.MIN_SIDE, DesktopFit.forView(0, 0, 320).height());
        assertEquals(DesktopFit.MAX_SIDE, DesktopFit.forView(9000, 9000, 320).width());
    }
}
