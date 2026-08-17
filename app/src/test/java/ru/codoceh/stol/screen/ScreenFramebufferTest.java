package ru.codoceh.stol.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Кадр стола до рукопожатия с сервером пустой, и показывать его нельзя.
 *
 * <p>Поверхность экрана готова раньше сети и сама просит перерисовку. Раньше эта просьба
 * доходила до показа кадра нулевого размера — Android отказывался делать такую картинку, а
 * поверхность оставалась ненастроенной, и стол не появлялся уже никогда.
 */
public class ScreenFramebufferTest {

    @Test
    public void emptyFrameIsNotShown() {
        Framebuffer framebuffer = new Framebuffer();
        List<Framebuffer> shown = new ArrayList<>();
        framebuffer.setFrameListener(shown::add);

        assertTrue("кадра ещё нет, а он не пустой", framebuffer.isEmpty());
        framebuffer.frameComplete();
        assertEquals("показали кадр нулевого размера", 0, shown.size());
    }

    @Test
    public void frameWithSizeIsShown() {
        Framebuffer framebuffer = new Framebuffer();
        List<Framebuffer> shown = new ArrayList<>();
        framebuffer.setFrameListener(shown::add);

        framebuffer.resize(1600, 1000);
        assertFalse(framebuffer.isEmpty());
        framebuffer.frameComplete();

        assertEquals(1, shown.size());
        assertEquals(1600, shown.get(0).width());
        assertEquals(1000, shown.get(0).height());
    }
}
