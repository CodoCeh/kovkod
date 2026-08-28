package ru.codoceh.stol.screen;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** Конвертация ARGB-пикселей в DIB-блок (BITMAPINFOHEADER + BGRA, снизу вверх) и обратно. */
public class DibCodecTest {

    @Test
    public void roundTripPreservesSizeAndColour() {
        int width = 4;
        int height = 3;
        int[] pixels = {
                0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff,
                0xff000000, 0xff123456, 0xffabcdef, 0xff808080,
                0xff01ff01, 0xff010203, 0xfffedcba, 0xff7f7f7f,
        };

        byte[] dib = DibCodec.argbToDib(pixels, width, height);

        int[] outWidthHeight = new int[2];
        int[] roundTripped = DibCodec.dibToArgb(dib, outWidthHeight);

        assertEquals(width, outWidthHeight[0]);
        assertEquals(height, outWidthHeight[1]);
        // Альфа не хранится отдельно в классическом DIB, поэтому сравниваем без альфа-канала.
        int[] expectedRgb = new int[pixels.length];
        int[] actualRgb = new int[roundTripped.length];
        for (int i = 0; i < pixels.length; i++) {
            expectedRgb[i] = pixels[i] & 0x00ffffff;
            actualRgb[i] = roundTripped[i] & 0x00ffffff;
        }
        assertArrayEquals(expectedRgb, actualRgb);
    }

    @Test
    public void dibHeaderMatchesBitmapinfoheaderLayout() {
        byte[] dib = DibCodec.argbToDib(new int[]{0xffaabbcc}, 1, 1);

        assertEquals(40 + 4, dib.length);
        assertEquals(40, readI32(dib, 0));  // biSize
        assertEquals(1, readI32(dib, 4));   // biWidth
        assertEquals(1, readI32(dib, 8));   // biHeight, положительная — bottom-up
        assertEquals(1, readU16(dib, 12));  // biPlanes
        assertEquals(32, readU16(dib, 14)); // biBitCount
        assertEquals(0, readI32(dib, 16));  // biCompression = BI_RGB

        // Пиксельные данные сразу после заголовка, в порядке BGRA.
        assertEquals((byte) 0xcc, dib[40]); // blue
        assertEquals((byte) 0xbb, dib[41]); // green
        assertEquals((byte) 0xaa, dib[42]); // red
    }

    @Test
    public void truncatedDibThrowsIllegalArgument() {
        byte[] tooShort = new byte[20]; // короче BITMAPINFOHEADER (40 байт)
        try {
            DibCodec.dibToArgb(tooShort, new int[2]);
            fail("ожидали IllegalArgumentException на усечённом заголовке");
        } catch (IllegalArgumentException expected) {
            // штатно
        }
    }

    @Test
    public void sizeMismatchBetweenHeaderAndPayloadThrowsIllegalArgument() {
        byte[] dib = DibCodec.argbToDib(new int[]{0xffaabbcc, 0xff112233}, 2, 1);
        byte[] truncatedPayload = new byte[dib.length - 4]; // заявлены 2 пикселя, а влезает только 1
        System.arraycopy(dib, 0, truncatedPayload, 0, truncatedPayload.length);

        try {
            DibCodec.dibToArgb(truncatedPayload, new int[2]);
            fail("ожидали IllegalArgumentException, когда длина данных не сходится с заголовком");
        } catch (IllegalArgumentException expected) {
            // штатно
        }
    }

    private static int readI32(byte[] data, int at) {
        return (data[at] & 0xff)
                | ((data[at + 1] & 0xff) << 8)
                | ((data[at + 2] & 0xff) << 16)
                | ((data[at + 3] & 0xff) << 24);
    }

    private static int readU16(byte[] data, int at) {
        return (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8);
    }
}
