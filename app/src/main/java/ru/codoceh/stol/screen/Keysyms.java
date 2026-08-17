package ru.codoceh.stol.screen;

import android.view.KeyEvent;

/**
 * Перевод клавиш Android в keysym X11 — то, что ждёт от нас {@code KeyEvent} протокола RFB.
 * Правило простое и взято из соглашения X: символы Latin-1 совпадают со своим кодом, всё
 * прочее из Unicode едет как {@code 0x01000000 + код}, а служебные клавиши — по таблице.
 *
 * <p>Раскладку переводим сами: сервер получает символ, а не позицию клавиши, поэтому в
 * VS Code печатается ровно то, что человек набрал, — включая кириллицу (R14.3).
 *
 * <p>Класс без состояния и без вызовов Android: ссылки на {@code KeyEvent} — только
 * числовые константы, поэтому проверяется он обычным тестом на JVM.
 */
public final class Keysyms {

    /** Клавиша, которой нечего послать. */
    public static final int NONE = 0;

    public static final int BACKSPACE = 0xff08;
    public static final int TAB = 0xff09;
    public static final int ENTER = 0xff0d;
    public static final int ESCAPE = 0xff1b;
    public static final int SHIFT_LEFT = 0xffe1;
    public static final int CONTROL_LEFT = 0xffe3;
    public static final int ALT_LEFT = 0xffe9;
    public static final int SUPER_LEFT = 0xffeb;

    private static final int UNICODE_BASE = 0x01000000;

    private Keysyms() {
    }

    /** Печатный символ — в keysym. */
    public static int forCharacter(int codePoint) {
        if (codePoint <= 0) {
            return NONE;
        }
        // Latin-1 занимает в таблице keysym те же номера, что и в Unicode.
        if (codePoint <= 0xff) {
            return codePoint;
        }
        return UNICODE_BASE + codePoint;
    }

    /**
     * Служебная клавиша Android — в keysym; {@link #NONE}, если клавиша не служебная и её
     * надо отправлять символом.
     */
    public static int forKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return ENTER;
            case KeyEvent.KEYCODE_DEL:
                return BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return 0xffff;
            case KeyEvent.KEYCODE_TAB:
                return TAB;
            case KeyEvent.KEYCODE_ESCAPE:
                return ESCAPE;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP:
                return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME:
                return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END:
                return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP:
                return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return 0xff56;
            case KeyEvent.KEYCODE_INSERT:
                return 0xff63;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return 0xffe5;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
                return SHIFT_LEFT;
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return 0xffe2;
            case KeyEvent.KEYCODE_CTRL_LEFT:
                return CONTROL_LEFT;
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return 0xffe4;
            case KeyEvent.KEYCODE_ALT_LEFT:
                return ALT_LEFT;
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return 0xffea;
            case KeyEvent.KEYCODE_META_LEFT:
                return SUPER_LEFT;
            case KeyEvent.KEYCODE_META_RIGHT:
                return 0xffec;
            default:
                if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
                    return 0xffbe + (keyCode - KeyEvent.KEYCODE_F1);
                }
                return NONE;
        }
    }
}
