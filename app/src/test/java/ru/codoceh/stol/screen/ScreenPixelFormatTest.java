package ru.codoceh.stol.screen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Разбор шестнадцати байт формата пикселя и раскодирование цвета (RFC 6143, PIXEL_FORMAT). */
public class ScreenPixelFormatTest {

    @Test
    public void writtenFormatReadsBackTheSame() {
        byte[] bytes = new byte[PixelFormat.LENGTH];
        PixelFormat.PREFERRED.write(bytes, 0);
        PixelFormat parsed = PixelFormat.parse(bytes, 0);

        assertEquals(32, parsed.bitsPerPixel());
        assertEquals(24, parsed.depth());
        assertFalse(parsed.isBigEndian());
        assertTrue(parsed.isTrueColour());
        assertEquals(4, parsed.bytesPerPixel());
    }

    @Test
    public void ourFormatPutsPixelStraightIntoArgb() {
        byte[] pixel = {0x56, 0x34, 0x12, 0x00}; // младший байт первым: синий, зелёный, красный
        assertEquals(0xff123456, PixelFormat.PREFERRED.toArgb(pixel, 0));
    }

    @Test
    public void bigEndianServerIsDecodedByItsOwnRules() {
        PixelFormat big = new PixelFormat(32, 24, true, true, 255, 255, 255, 16, 8, 0);
        byte[] pixel = {0x00, 0x12, 0x34, 0x56};
        assertEquals(0xff123456, big.toArgb(pixel, 0));
    }

    @Test
    public void narrowChannelsAreStretchedToEightBits() {
        // 16 бит на пиксель, 5-6-5: белый обязан остаться белым, а не почти белым.
        PixelFormat rgb565 = new PixelFormat(16, 16, false, true, 31, 63, 31, 11, 5, 0);
        byte[] white = {(byte) 0xff, (byte) 0xff};
        assertEquals(0xffffffff, rgb565.toArgb(white, 0));

        byte[] pureRed = {0x00, (byte) 0xf8};
        assertEquals(0xffff0000, rgb565.toArgb(pureRed, 0));
    }

    @Test
    public void offsetIsRespectedSoRowsCanBeDecodedInPlace() {
        byte[] row = {0, 0, 0, 0, (byte) 0xff, 0x00, 0x00, 0x00};
        assertEquals(0xff0000ff, PixelFormat.PREFERRED.toArgb(row, 4));
    }
}
