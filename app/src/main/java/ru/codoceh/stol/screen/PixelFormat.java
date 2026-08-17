package ru.codoceh.stol.screen;

/**
 * Формат пикселя RFB — те самые 16 байт, что приходят в {@code ServerInit} и уходят в
 * {@code SetPixelFormat} (RFC 6143, «PIXEL_FORMAT»). Написан по спецификации протокола.
 *
 * <p>Сервер объявляет свой формат, но обязан перейти на тот, который попросит клиент;
 * мы просим {@link #PREFERRED} — 32 бита на пиксель, порядок байт от младшего, каналы по
 * 8 бит. Разобранный формат сервера всё равно нужен: по нему видно, с кем разговариваем,
 * и он же раскодирует кадр, если формат оставили серверный.
 */
public final class PixelFormat {

    /** Длина структуры в байтах. */
    public static final int LENGTH = 16;

    /** Формат, который мы просим у сервера: пиксель ложится в int как ARGB без пересчёта. */
    public static final PixelFormat PREFERRED =
            new PixelFormat(32, 24, false, true, 255, 255, 255, 16, 8, 0);

    private final int bitsPerPixel;
    private final int depth;
    private final boolean bigEndian;
    private final boolean trueColour;
    private final int redMax;
    private final int greenMax;
    private final int blueMax;
    private final int redShift;
    private final int greenShift;
    private final int blueShift;

    public PixelFormat(int bitsPerPixel, int depth, boolean bigEndian, boolean trueColour,
                       int redMax, int greenMax, int blueMax,
                       int redShift, int greenShift, int blueShift) {
        if (bitsPerPixel != 8 && bitsPerPixel != 16 && bitsPerPixel != 32) {
            throw new IllegalArgumentException("бит на пиксель: " + bitsPerPixel);
        }
        this.bitsPerPixel = bitsPerPixel;
        this.depth = depth;
        this.bigEndian = bigEndian;
        this.trueColour = trueColour;
        this.redMax = redMax;
        this.greenMax = greenMax;
        this.blueMax = blueMax;
        this.redShift = redShift;
        this.greenShift = greenShift;
        this.blueShift = blueShift;
    }

    public static PixelFormat parse(byte[] data, int offset) {
        return new PixelFormat(
                u8(data, offset),
                u8(data, offset + 1),
                u8(data, offset + 2) != 0,
                u8(data, offset + 3) != 0,
                u16(data, offset + 4),
                u16(data, offset + 6),
                u16(data, offset + 8),
                u8(data, offset + 10),
                u8(data, offset + 11),
                u8(data, offset + 12));
    }

    public void write(byte[] out, int offset) {
        out[offset] = (byte) bitsPerPixel;
        out[offset + 1] = (byte) depth;
        out[offset + 2] = (byte) (bigEndian ? 1 : 0);
        out[offset + 3] = (byte) (trueColour ? 1 : 0);
        putU16(out, offset + 4, redMax);
        putU16(out, offset + 6, greenMax);
        putU16(out, offset + 8, blueMax);
        out[offset + 10] = (byte) redShift;
        out[offset + 11] = (byte) greenShift;
        out[offset + 12] = (byte) blueShift;
        out[offset + 13] = 0;
        out[offset + 14] = 0;
        out[offset + 15] = 0;
    }

    public int bytesPerPixel() {
        return bitsPerPixel / 8;
    }

    public int bitsPerPixel() {
        return bitsPerPixel;
    }

    public int depth() {
        return depth;
    }

    public boolean isBigEndian() {
        return bigEndian;
    }

    public boolean isTrueColour() {
        return trueColour;
    }

    /** Пиксель из потока — в непрозрачный ARGB, каким его ждёт Android. */
    public int toArgb(byte[] data, int offset) {
        if (!trueColour) {
            throw new IllegalStateException("формат без прямого цвета: карта цветов не нужна на петле");
        }
        long raw = 0;
        int bytes = bytesPerPixel();
        if (bigEndian) {
            for (int i = 0; i < bytes; i++) {
                raw = (raw << 8) | (data[offset + i] & 0xffL);
            }
        } else {
            for (int i = bytes - 1; i >= 0; i--) {
                raw = (raw << 8) | (data[offset + i] & 0xffL);
            }
        }
        int red = channel(raw, redShift, redMax);
        int green = channel(raw, greenShift, greenMax);
        int blue = channel(raw, blueShift, blueMax);
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int channel(long raw, int shift, int max) {
        if (max <= 0) {
            return 0;
        }
        int value = (int) ((raw >>> shift) & max);
        return max == 255 ? value : (value * 255 + max / 2) / max;
    }

    private static int u8(byte[] data, int at) {
        return data[at] & 0xff;
    }

    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xff) << 8) | (data[at + 1] & 0xff);
    }

    private static void putU16(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 8);
        out[at + 1] = (byte) value;
    }

    @Override
    public String toString() {
        return bitsPerPixel + " бит, глубина " + depth
                + (bigEndian ? ", старший байт первым" : ", младший байт первым")
                + (trueColour ? ", прямой цвет" : ", карта цветов");
    }
}
