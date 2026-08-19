package ru.codoceh.stol.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Пересчёт касания в координату стола: стол вписан целиком, поэтому обрезки нет (R09.3). */
public class ScreenViewportTest {

    private static final float EXACT = 0.0001f;

    @Test
    public void desktopFitsWindowWholeAndStaysCentred() {
        Viewport viewport = new Viewport();
        viewport.setFrameSize(1600, 1000);
        viewport.setViewSize(800, 1000);

        assertEquals(0.5f, viewport.scale(), EXACT);
        assertEquals(0f, viewport.offsetX(), EXACT);
        assertEquals("остаток окна делится поровну", 250f, viewport.offsetY(), EXACT);
    }

    @Test
    public void touchInTheMiddleOfWindowLandsInTheMiddleOfDesktop() {
        Viewport viewport = new Viewport();
        viewport.setFrameSize(1600, 1000);
        viewport.setViewSize(800, 1000);

        assertEquals(800, viewport.frameX(400f));
        assertEquals(500, viewport.frameY(500f));
    }

    @Test
    public void touchOnTheBlackMarginIsPressedToTheEdgeNotSentOutOfBounds() {
        Viewport viewport = new Viewport();
        viewport.setFrameSize(1600, 1000);
        viewport.setViewSize(800, 1000);

        assertEquals(0, viewport.frameY(10f));
        assertEquals(999, viewport.frameY(990f));
        assertEquals(0, viewport.frameX(-50f));
        assertEquals(1599, viewport.frameX(5000f));
    }

    @Test
    public void turningTheWindowSidewaysRescalesInsteadOfCropping() {
        Viewport viewport = new Viewport();
        viewport.setFrameSize(1600, 1000);

        viewport.setViewSize(1000, 800);
        assertEquals(0.625f, viewport.scale(), EXACT);
        assertTrue("стол вылез за окно", 1600 * viewport.scale() <= 1000 + EXACT);

        viewport.setViewSize(800, 1000);
        assertEquals(0.5f, viewport.scale(), EXACT);
        assertTrue("стол вылез за окно", 1000 * viewport.scale() <= 1000 + EXACT);
    }

    @Test
    public void unknownSizesDoNotDivideByZero() {
        Viewport viewport = new Viewport();
        assertFalse(viewport.isReady());
        assertEquals(1f, viewport.scale(), EXACT);
        assertEquals(0, viewport.frameX(100f));
    }

    @Test
    public void zoomMagnifiesDesktopAndMapsTouchesCorrectly() {
        Viewport viewport = new Viewport();
        viewport.setFrameSize(1000, 1000);
        viewport.setViewSize(1000, 1000);

        assertEquals(1.0f, viewport.zoom(), EXACT);
        assertEquals(1.0f, viewport.scale(), EXACT);

        // Увеличиваем в 2 раза с фокусом по центру (500, 500)
        viewport.setZoom(2.0f, 500f, 500f);
        assertEquals(2.0f, viewport.zoom(), EXACT);
        assertEquals(2.0f, viewport.scale(), EXACT);

        // Центр окна по-прежнему попадает в центр стола
        assertEquals(500, viewport.frameX(500f));
        assertEquals(500, viewport.frameY(500f));

        // Точка (250, 500) на экране: при 2x это (500 - 250/2) = 375 на кадре стола
        assertEquals(375, viewport.frameX(250f));

        // Панорамирование
        viewport.pan(100f, 0f);
        assertEquals(325, viewport.frameX(250f));

        // Сброс масштаба возвращает 1.0x
        viewport.resetZoom();
        assertEquals(1.0f, viewport.zoom(), EXACT);
        assertEquals(1.0f, viewport.scale(), EXACT);
        assertEquals(250, viewport.frameX(250f));
    }
}
