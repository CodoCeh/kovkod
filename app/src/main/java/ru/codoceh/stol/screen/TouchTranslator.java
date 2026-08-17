package ru.codoceh.stol.screen;

/**
 * Перевод касаний в мышь: тап — щелчок левой, долгое нажатие — правой, ведение одним
 * пальцем — перетаскивание с зажатой левой, ведение двумя — колесо (R14.1, R14.2).
 *
 * <p>Отдельный класс без Android внутри — по тем же соображениям, что и
 * {@code ProcessLauncher} в шве 2: иначе половину задачи пришлось бы проверять руками на
 * устройстве. Сюда приходят координаты окна, отсюда уходят координаты стола.
 */
public final class TouchTranslator {

    /** Что делать с мышью на той стороне. */
    public interface Mouse {
        /** Курсор в точку стола с этим набором нажатых кнопок. */
        void move(int x, int y, int buttons);

        /** Нажать и отпустить кнопку в точке. */
        void click(int x, int y, int button);

        /** Щелчок колесом; в RFB это кнопки 4 и 5. */
        void wheel(int x, int y, boolean up);
    }

    private final Viewport viewport;
    private final Mouse mouse;
    private final int slop;
    private final int wheelStep;

    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private float wheelAnchor;
    private boolean dragging;
    private boolean wheeling;
    private boolean spent;

    /**
     * @param slop      сколько точек окна считается дрожанием пальца, а не ведением
     * @param wheelStep через сколько точек окна ведение двумя пальцами даёт щелчок колесом
     */
    public TouchTranslator(Viewport viewport, Mouse mouse, int slop, int wheelStep) {
        this.viewport = viewport;
        this.mouse = mouse;
        this.slop = slop;
        this.wheelStep = Math.max(wheelStep, 1);
    }

    public void down(float x, float y) {
        downX = x;
        downY = y;
        lastX = x;
        lastY = y;
        dragging = false;
        wheeling = false;
        spent = false;
    }

    /** Палец задержался: считаем правым щелчком и больше от этого касания ничего не ждём. */
    public void longPress() {
        if (dragging || wheeling || spent) {
            return;
        }
        spent = true;
        mouse.click(viewport.frameX(downX), viewport.frameY(downY), RfbClient.BUTTON_RIGHT);
    }

    /** Второй палец: перетаскивание отменяется, дальше идёт прокрутка. */
    public void secondFinger(float centreX, float centreY) {
        if (dragging) {
            mouse.move(viewport.frameX(lastX), viewport.frameY(lastY), 0);
            dragging = false;
        }
        wheeling = true;
        wheelAnchor = centreY;
        lastX = centreX;
        lastY = centreY;
    }

    /** @param x,y середина между пальцами; для одного пальца — он сам */
    public void move(float x, float y) {
        lastX = x;
        lastY = y;
        if (wheeling) {
            spin(x, y);
        } else if (dragging) {
            mouse.move(viewport.frameX(x), viewport.frameY(y), RfbClient.BUTTON_LEFT);
        } else if (!spent && moved(x, y)) {
            dragging = true;
            // Сначала довести курсор, потом нажать: иначе окно дёрнется от старой точки.
            mouse.move(viewport.frameX(x), viewport.frameY(y), 0);
            mouse.move(viewport.frameX(x), viewport.frameY(y), RfbClient.BUTTON_LEFT);
        }
    }

    public void up() {
        if (dragging) {
            mouse.move(viewport.frameX(lastX), viewport.frameY(lastY), 0);
        } else if (!wheeling && !spent) {
            mouse.click(viewport.frameX(lastX), viewport.frameY(lastY), RfbClient.BUTTON_LEFT);
        }
        dragging = false;
        wheeling = false;
    }

    /** Касание отобрали: кнопку нельзя оставить зажатой. */
    public void cancel() {
        if (dragging) {
            mouse.move(viewport.frameX(lastX), viewport.frameY(lastY), 0);
        }
        dragging = false;
        wheeling = false;
        spent = true;
    }

    /** Ждать ли ещё долгого нажатия — таймер живёт снаружи, решение здесь. */
    public boolean isIdle() {
        return !dragging && !wheeling && !spent;
    }

    private void spin(float x, float y) {
        while (Math.abs(y - wheelAnchor) >= wheelStep) {
            boolean up = y > wheelAnchor;
            wheelAnchor += up ? wheelStep : -wheelStep;
            // Палец вверх — содержимое уезжает вверх, колесо крутится вниз.
            mouse.wheel(viewport.frameX(x), viewport.frameY(y), up);
        }
    }

    private boolean moved(float x, float y) {
        return Math.abs(x - downX) > slop || Math.abs(y - downY) > slop;
    }
}
