package ru.codoceh.stol.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Тач как мышь (R14.1, R14.2). Стол 800×600 показан в окне 800×600 один к одному, чтобы в
 * ожиданиях были видны сами жесты, а не арифметика масштаба, — её проверяет
 * {@link ScreenViewportTest}.
 */
public class ScreenTouchTranslatorTest {

    private static final int SLOP = 10;
    private static final int WHEEL_STEP = 40;

    private Recorder mouse;
    private TouchTranslator touch;

    @Before
    public void setUp() {
        Viewport viewport = new Viewport();
        viewport.setFrameSize(800, 600);
        viewport.setViewSize(800, 600);
        mouse = new Recorder();
        touch = new TouchTranslator(viewport, mouse, SLOP, WHEEL_STEP);
    }

    @Test
    public void tapIsLeftClickWhereTheFingerWas() {
        touch.down(100, 200);
        touch.up();

        assertEquals(1, mouse.events.size());
        assertEquals("клик 100,200 кнопкой 1", mouse.events.get(0));
    }

    @Test
    public void shakyFingerIsStillATapNotADrag() {
        touch.down(100, 200);
        touch.move(104, 197);
        touch.up();

        assertEquals("дрожание пальца превратилось в перетаскивание", 1, mouse.events.size());
        assertTrue(mouse.events.get(0).startsWith("клик"));
    }

    @Test
    public void draggingHoldsLeftButtonUntilTheFingerLeaves() {
        touch.down(100, 200);
        touch.move(160, 200);
        touch.move(220, 240);
        touch.up();

        assertEquals(4, mouse.events.size());
        assertEquals("курсор ведут до нажатия", "курсор 160,200 кнопки 0", mouse.events.get(0));
        assertEquals("курсор 160,200 кнопки 1", mouse.events.get(1));
        assertEquals("курсор 220,240 кнопки 1", mouse.events.get(2));
        assertEquals("кнопку надо отпустить", "курсор 220,240 кнопки 0", mouse.events.get(3));
    }

    @Test
    public void longPressIsRightClickAtTheStartingPoint() {
        touch.down(300, 400);
        touch.longPress();
        touch.up();

        assertEquals("правый щелчок один, и тап поверх него не идёт", 1, mouse.events.size());
        assertEquals("клик 300,400 кнопкой 4", mouse.events.get(0));
    }

    @Test
    public void longPressDoesNotFireOnceTheFingerIsDragging() {
        touch.down(100, 100);
        touch.move(300, 100);
        touch.longPress();

        for (String event : mouse.events) {
            assertTrue("правая кнопка влезла в перетаскивание: " + event,
                    !event.contains("кнопкой 4"));
        }
    }

    @Test
    public void twoFingersScrollTheWheelInsteadOfDraggingAWindow() {
        touch.down(400, 300);
        touch.secondFinger(400, 300);
        touch.move(400, 220);

        // Щелчки идут в текущую точку: колесо крутится там, где сейчас палец.
        assertEquals("два шага по 40 точек — два щелчка", 2, mouse.events.size());
        assertEquals("палец вверх — колесо вниз", "колесо 400,220 вниз", mouse.events.get(0));
        assertEquals("колесо 400,220 вниз", mouse.events.get(1));
    }

    @Test
    public void pullingContentDownScrollsTheWheelUp() {
        touch.down(400, 200);
        touch.secondFinger(400, 200);
        touch.move(400, 245);

        assertEquals(1, mouse.events.size());
        assertEquals("колесо 400,245 вверх", mouse.events.get(0));
    }

    @Test
    public void secondFingerReleasesTheButtonHeldByTheFirst() {
        touch.down(100, 100);
        touch.move(200, 100);
        touch.secondFinger(200, 100);
        touch.move(200, 100);
        touch.up();

        assertEquals("после второго пальца кнопка осталась зажатой",
                "курсор 200,100 кнопки 0", mouse.events.get(mouse.events.size() - 1));
        int pressed = 0;
        for (String event : mouse.events) {
            if (event.endsWith("кнопки 1")) {
                pressed++;
            }
        }
        assertEquals("нажатие было ровно одно — до второго пальца", 1, pressed);
    }

    @Test
    public void wheelingNeverEndsWithATap() {
        touch.down(400, 300);
        touch.secondFinger(400, 300);
        touch.move(400, 100);
        int wheeled = mouse.events.size();
        touch.up();

        assertEquals("прокрутка закончилась щелчком", wheeled, mouse.events.size());
    }

    @Test
    public void cancelledTouchDoesNotLeaveTheButtonDown() {
        touch.down(100, 100);
        touch.move(300, 100);
        touch.cancel();

        assertEquals("курсор 300,100 кнопки 0", mouse.events.get(mouse.events.size() - 1));
    }

    @Test
    public void touchIsIdleOnlyWhileTheGestureIsUndecided() {
        assertTrue(touch.isIdle());
        touch.down(100, 100);
        assertTrue("сразу после касания жест ещё не выбран", touch.isIdle());
        touch.move(300, 100);
        assertTrue("перетаскивание началось, долгого нажатия ждать нечего", !touch.isIdle());
    }

    @Test
    public void trackpadModeMovesCursorRelativelyWithoutClicking() {
        touch.setMode(TouchTranslator.Mode.TRACKPAD);
        touch.setCursor(400, 300);
        touch.down(100, 100);
        touch.move(120, 110);
        touch.up();

        assertEquals(1, mouse.events.size());
        assertEquals("курсор 420,310 кнопки 0", mouse.events.get(0));
    }

    @Test
    public void trackpadTapPerformsLeftClickAtCursorPosition() {
        touch.setMode(TouchTranslator.Mode.TRACKPAD);
        touch.setCursor(400, 300);
        touch.down(100, 100);
        touch.up();

        assertEquals(1, mouse.events.size());
        assertEquals("клик 400,300 кнопкой 1", mouse.events.get(0));
    }

    @Test
    public void trackpadTwoFingerTapPerformsRightClick() {
        touch.setMode(TouchTranslator.Mode.TRACKPAD);
        touch.setCursor(400, 300);
        touch.down(100, 100);
        touch.secondFinger(100, 100);
        touch.up();

        assertEquals(1, mouse.events.size());
        assertEquals("клик 400,300 кнопкой 4", mouse.events.get(0));
    }

    @Test
    public void trackpadTwoFingerScrollWheelsMouse() {
        touch.setMode(TouchTranslator.Mode.TRACKPAD);
        touch.setCursor(400, 300);
        touch.down(100, 100);
        touch.secondFinger(100, 100);
        touch.move(100, 20);

        assertEquals(2, mouse.events.size());
        assertEquals("колесо 400,300 вниз", mouse.events.get(0));
    }

    @Test
    public void threeFingerSwipeUpScrollsListDown() {
        touch.down(400, 300);
        touch.secondFinger(400, 300);
        touch.threeFinger(400, 300);
        touch.move(400, 220);

        assertEquals(2, mouse.events.size());
        assertEquals("колесо 400,220 вниз", mouse.events.get(0));
        assertEquals("колесо 400,220 вниз", mouse.events.get(1));
    }

    @Test
    public void threeFingerSwipeDownScrollsListUp() {
        touch.down(400, 300);
        touch.secondFinger(400, 300);
        touch.threeFinger(400, 300);
        touch.move(400, 380);

        assertEquals(2, mouse.events.size());
        assertEquals("колесо 400,380 вверх", mouse.events.get(0));
        assertEquals("колесо 400,380 вверх", mouse.events.get(1));
    }

    private static final class Recorder implements TouchTranslator.Mouse {
        final List<String> events = new ArrayList<>();

        @Override
        public void move(int x, int y, int buttons) {
            events.add("курсор " + x + "," + y + " кнопки " + buttons);
        }

        @Override
        public void click(int x, int y, int button) {
            events.add("клик " + x + "," + y + " кнопкой " + button);
        }

        @Override
        public void wheel(int x, int y, boolean up) {
            events.add("колесо " + x + "," + y + (up ? " вверх" : " вниз"));
        }
    }
}
