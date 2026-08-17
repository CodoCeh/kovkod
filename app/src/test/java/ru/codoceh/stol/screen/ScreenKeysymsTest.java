package ru.codoceh.stol.screen;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import org.junit.Test;

/**
 * Перевод клавиш в keysym X11. Значения сверены со спецификацией X (keysymdef): именно их
 * ждёт сервер, и именно от них зависит, появится ли буква в Electron-программе (R14.3).
 */
public class ScreenKeysymsTest {

    @Test
    public void latinCharactersKeepTheirCode() {
        assertEquals(0x41, Keysyms.forCharacter('A'));
        assertEquals(0x61, Keysyms.forCharacter('a'));
        assertEquals(0x20, Keysyms.forCharacter(' '));
        assertEquals(0x2f, Keysyms.forCharacter('/'));
    }

    @Test
    public void cyrillicGoesAsUnicodeKeysym() {
        assertEquals(0x01000436, Keysyms.forCharacter('ж'));
        assertEquals(0x01000410, Keysyms.forCharacter('А'));
        assertEquals("знак рубля тоже буква для сервера",
                0x010020bd, Keysyms.forCharacter('₽'));
    }

    @Test
    public void nothingTypedIsNothingSent() {
        assertEquals(Keysyms.NONE, Keysyms.forCharacter(0));
        assertEquals(Keysyms.NONE, Keysyms.forCharacter(-1));
    }

    @Test
    public void keysThatCarryNoCharacterHaveTheirOwnTable() {
        assertEquals(0xff0d, Keysyms.forKeyCode(KeyEvent.KEYCODE_ENTER));
        assertEquals(0xff08, Keysyms.forKeyCode(KeyEvent.KEYCODE_DEL));
        assertEquals(0xff09, Keysyms.forKeyCode(KeyEvent.KEYCODE_TAB));
        assertEquals(0xff1b, Keysyms.forKeyCode(KeyEvent.KEYCODE_ESCAPE));
        assertEquals(0xff51, Keysyms.forKeyCode(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(0xff54, Keysyms.forKeyCode(KeyEvent.KEYCODE_DPAD_DOWN));
        assertEquals(0xffe3, Keysyms.forKeyCode(KeyEvent.KEYCODE_CTRL_LEFT));
        assertEquals(0xffe1, Keysyms.forKeyCode(KeyEvent.KEYCODE_SHIFT_LEFT));
    }

    @Test
    public void functionKeysRunInOrder() {
        assertEquals(0xffbe, Keysyms.forKeyCode(KeyEvent.KEYCODE_F1));
        assertEquals(0xffc9, Keysyms.forKeyCode(KeyEvent.KEYCODE_F12));
    }

    @Test
    public void ordinaryLetterKeyIsLeftToTheCharacterPath() {
        assertEquals("букву переводит раскладка, а не таблица",
                Keysyms.NONE, Keysyms.forKeyCode(KeyEvent.KEYCODE_A));
    }
}
