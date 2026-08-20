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

    public enum Mode {
        TOUCH,
        TRACKPAD
    }

    private final Viewport viewport;
    private final Mouse mouse;
    private final int slop;
    private final int wheelStep;

    private Mode mode = Mode.TOUCH;
    private float cursorX = -1;
    private float cursorY = -1;

    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private float wheelAnchor;
    private boolean dragging;
    private boolean wheeling;
    private boolean threeFingerMode;
    private boolean twoFingerTap;
    private boolean spent;

    /**
     * @param slop      сколько точек окна считается дрожанием пальца, а не ведением
     * @param wheelStep через сколько точек окна ведение двумя или тремя пальцами даёт щелчок колесом
     */
    public TouchTranslator(Viewport viewport, Mouse mouse, int slop, int wheelStep) {
        this.viewport = viewport;
        this.mouse = mouse;
        this.slop = slop;
        this.wheelStep = Math.max(wheelStep, 1);
    }

    public void setMode(Mode mode) {
        if (mode != null) {
            this.mode = mode;
        }
    }

    public Mode mode() {
        return mode;
    }

    public void setCursor(int x, int y) {
        this.cursorX = x;
        this.cursorY = y;
    }

    public int cursorX() {
        return Math.round(cursorX >= 0 ? cursorX : viewport.frameWidth() / 2f);
    }

    public int cursorY() {
        return Math.round(cursorY >= 0 ? cursorY : viewport.frameHeight() / 2f);
    }

    public void down(float x, float y) {
        downX = x;
        downY = y;
        lastX = x;
        lastY = y;
        dragging = false;
        wheeling = false;
        threeFingerMode = false;
        twoFingerTap = false;
        spent = false;
        if (cursorX < 0 || cursorY < 0) {
            cursorX = viewport.frameWidth() / 2f;
            cursorY = viewport.frameHeight() / 2f;
        }
    }

    /** Палец задержался: считаем правым щелчком и больше от этого касания ничего не ждём. */
    public void longPress() {
        if (dragging || wheeling || spent) {
            return;
        }
        spent = true;
        if (mode == Mode.TRACKPAD) {
            mouse.click(cursorX(), cursorY(), RfbClient.BUTTON_RIGHT);
        } else {
            mouse.click(viewport.frameX(downX), viewport.frameY(downY), RfbClient.BUTTON_RIGHT);
        }
    }

    /** Второй палец: перетаскивание отменяется, дальше идёт прокрутка или 2-finger tap. */
    public void secondFinger(float centreX, float centreY) {
        if (dragging) {
            int cx = mode == Mode.TRACKPAD ? cursorX() : viewport.frameX(lastX);
            int cy = mode == Mode.TRACKPAD ? cursorY() : viewport.frameY(lastY);
            mouse.move(cx, cy, 0);
            dragging = false;
        }
        wheeling = true;
        twoFingerTap = true;
        wheelAnchor = centreY;
        lastX = centreX;
        lastY = centreY;
    }

    /** Третий палец (как в AnyDesk): включается прокрутка колесом при свайпе 3 пальцами. */
    public void threeFinger(float centreX, float centreY) {
        if (dragging) {
            int cx = mode == Mode.TRACKPAD ? cursorX() : viewport.frameX(lastX);
            int cy = mode == Mode.TRACKPAD ? cursorY() : viewport.frameY(lastY);
            mouse.move(cx, cy, 0);
            dragging = false;
        }
        threeFingerMode = true;
        wheeling = true;
        twoFingerTap = false;
        wheelAnchor = centreY;
        lastX = centreX;
        lastY = centreY;
    }

    /** @param x,y середина между пальцами; для одного пальца — он сам */
    public void move(float x, float y) {
        if (mode == Mode.TRACKPAD) {
            moveTrackpad(x, y);
        } else {
            moveTouch(x, y);
        }
    }

    private void moveTouch(float x, float y) {
        lastX = x;
        lastY = y;
        if (wheeling) {
            spin(viewport.frameX(x), viewport.frameY(y), y);
        } else if (dragging) {
            mouse.move(viewport.frameX(x), viewport.frameY(y), RfbClient.BUTTON_LEFT);
        } else if (!spent && moved(x, y)) {
            dragging = true;
            // Сначала довести курсор, потом нажать: иначе окно дёрнется от старой точки.
            mouse.move(viewport.frameX(x), viewport.frameY(y), 0);
            mouse.move(viewport.frameX(x), viewport.frameY(y), RfbClient.BUTTON_LEFT);
        }
    }

    private void moveTrackpad(float x, float y) {
        float dx = x - lastX;
        float dy = y - lastY;
        lastX = x;
        lastY = y;
        if (wheeling) {
            spin(cursorX(), cursorY(), y);
        } else {
            float scale = viewport.scale();
            if (scale > 0) {
                cursorX = Math.max(0, Math.min(viewport.frameWidth() - 1, cursorX + (dx / scale)));
                cursorY = Math.max(0, Math.min(viewport.frameHeight() - 1, cursorY + (dy / scale)));
            }
            if (dragging) {
                mouse.move(cursorX(), cursorY(), RfbClient.BUTTON_LEFT);
            } else if (!spent) {
                mouse.move(cursorX(), cursorY(), 0);
            }
        }
    }

    public void up() {
        if (mode == Mode.TRACKPAD) {
            upTrackpad();
        } else {
            upTouch();
        }
        dragging = false;
        wheeling = false;
        threeFingerMode = false;
        twoFingerTap = false;
    }

    private void upTouch() {
        if (dragging) {
            mouse.move(viewport.frameX(lastX), viewport.frameY(lastY), 0);
        } else if (!wheeling && !spent) {
            mouse.click(viewport.frameX(lastX), viewport.frameY(lastY), RfbClient.BUTTON_LEFT);
        }
    }

    private void upTrackpad() {
        if (dragging) {
            mouse.move(cursorX(), cursorY(), 0);
        } else if (twoFingerTap && !spent) {
            mouse.click(cursorX(), cursorY(), RfbClient.BUTTON_RIGHT);
        } else if (!wheeling && !spent && !moved(lastX, lastY)) {
            mouse.click(cursorX(), cursorY(), RfbClient.BUTTON_LEFT);
        }
    }

    /** Касание отобрали: кнопку нельзя оставить зажатой. */
    public void cancel() {
        if (dragging) {
            int cx = mode == Mode.TRACKPAD ? cursorX() : viewport.frameX(lastX);
            int cy = mode == Mode.TRACKPAD ? cursorY() : viewport.frameY(lastY);
            mouse.move(cx, cy, 0);
        }
        dragging = false;
        wheeling = false;
        threeFingerMode = false;
        twoFingerTap = false;
        spent = true;
    }

    /** Ждать ли ещё долгого нажатия — таймер живёт снаружи, решение здесь. */
    public boolean isIdle() {
        return !dragging && !wheeling && !spent;
    }

    private void spin(int fx, int fy, float y) {
        while (Math.abs(y - wheelAnchor) >= wheelStep) {
            twoFingerTap = false;
            boolean up = y > wheelAnchor;
            wheelAnchor += up ? wheelStep : -wheelStep;
            // Палец вверх — содержимое уезжает вверх, колесо крутится вниз.
            mouse.wheel(fx, fy, up);
        }
    }

    private boolean moved(float x, float y) {
        return Math.abs(x - downX) > slop || Math.abs(y - downY) > slop;
    }
}
