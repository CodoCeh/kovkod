package ru.codoceh.stol.screen;

/**
 * Конвертация между сырыми ARGB-пикселями ({@code int[]}, формат
 * {@code Color.argb}-совместимый: {@code 0xAARRGGBB} на пиксель) и DIB-блоком —
 * {@code BITMAPINFOHEADER} (40 байт, little-endian) плюс пиксельные данные в порядке
 * BGRA по 4 байта на пиксель, снизу вверх (bottom-up), без 14-байтного файлового
 * заголовка BMP. Это ровно то, что лежит в Windows-буфере обмена под {@code CF_DIB}.
 *
 * <p>Класс не знает про Android SDK (без {@code android.graphics.Bitmap}) — только байты
 * и массивы {@code int}, чтобы конвертацию можно было прогнать юнит-тестом на JVM.
 *
 * <p>Альфа-канал и поворот по EXIF не обрабатываются: пиксели переносятся как есть,
 * альфа-байт при упаковке/распаковке игнорируется (классический 32bpp DIB не хранит
 * альфа отдельно) — см. границы конвертации в спеке Extended Clipboard.
 */
public final class DibCodec {

    /** Длина {@code BITMAPINFOHEADER} в байтах. */
    private static final int HEADER_LENGTH = 40;

    private static final int BI_RGB = 0;

    private DibCodec() {
    }

    /** Пиксели (ARGB, построчно сверху вниз) → DIB-блок (BGRA, снизу вверх). */
    public static byte[] argbToDib(int[] pixels, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("размеры должны быть положительными: " + width + "x" + height);
        }
        long pixelCount = (long) width * height;
        if (pixels == null || pixels.length != pixelCount) {
            throw new IllegalArgumentException(
                    "число пикселей не совпадает с width*height: ожидали " + pixelCount
                            + ", получили " + (pixels == null ? "null" : pixels.length));
        }

        byte[] dib = new byte[HEADER_LENGTH + (int) (pixelCount * 4)];
        writeHeader(dib, width, height);

        for (int row = 0; row < height; row++) {
            int sourceRow = height - 1 - row; // bottom-up: первой в файле идёт нижняя строка картинки
            int destOffset = HEADER_LENGTH + row * width * 4;
            int sourceOffset = sourceRow * width;
            for (int col = 0; col < width; col++) {
                int argb = pixels[sourceOffset + col];
                int at = destOffset + col * 4;
                dib[at] = (byte) argb;          // blue
                dib[at + 1] = (byte) (argb >>> 8);  // green
                dib[at + 2] = (byte) (argb >>> 16); // red
                dib[at + 3] = (byte) (argb >>> 24); // alpha, переносится как есть
            }
        }
        return dib;
    }

    /**
     * DIB-блок (BGRA, снизу вверх) → пиксели (ARGB, построчно сверху вниз).
     * Размеры картинки записываются в {@code outWidthHeight[0]} (ширина) и
     * {@code outWidthHeight[1]} (высота).
     *
     * @throws IllegalArgumentException если заголовок повреждён или его размеры не
     *                                   сходятся с длиной массива — вместо NPE/AIOOBE.
     */
    public static int[] dibToArgb(byte[] dib, int[] outWidthHeight) {
        if (outWidthHeight == null || outWidthHeight.length < 2) {
            throw new IllegalArgumentException("outWidthHeight должен быть массивом из двух int");
        }
        if (dib == null || dib.length < HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "DIB короче заголовка BITMAPINFOHEADER: " + (dib == null ? "null" : dib.length + " байт"));
        }

        int biSize = readI32(dib, 0);
        if (biSize != HEADER_LENGTH) {
            throw new IllegalArgumentException("неожиданный biSize: " + biSize + ", ожидали " + HEADER_LENGTH);
        }
        int width = readI32(dib, 4);
        int height = readI32(dib, 8);
        int biPlanes = readU16(dib, 12);
        int biBitCount = readU16(dib, 14);
        int biCompression = readI32(dib, 16);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "некорректные размеры в заголовке (нужна положительная высота, bottom-up): "
                            + width + "x" + height);
        }
        if (biPlanes != 1) {
            throw new IllegalArgumentException("неожиданный biPlanes: " + biPlanes);
        }
        if (biBitCount != 32) {
            throw new IllegalArgumentException("поддерживается только 32 бита на пиксель, получили: " + biBitCount);
        }
        if (biCompression != BI_RGB) {
            throw new IllegalArgumentException("поддерживается только BI_RGB, получили biCompression=" + biCompression);
        }

        long pixelCount = (long) width * height;
        long expectedLength = HEADER_LENGTH + pixelCount * 4;
        if (dib.length != expectedLength) {
            throw new IllegalArgumentException(
                    "длина DIB не сходится с размерами из заголовка: заявлено " + expectedLength
                            + " байт, получено " + dib.length);
        }

        int[] pixels = new int[(int) pixelCount];
        for (int row = 0; row < height; row++) {
            int sourceOffset = HEADER_LENGTH + row * width * 4;
            int destRow = height - 1 - row; // bottom-up: первая строка файла — нижняя строка картинки
            int destOffset = destRow * width;
            for (int col = 0; col < width; col++) {
                int at = sourceOffset + col * 4;
                int blue = dib[at] & 0xff;
                int green = dib[at + 1] & 0xff;
                int red = dib[at + 2] & 0xff;
                int alpha = dib[at + 3] & 0xff;
                pixels[destOffset + col] = (alpha << 24) | (red << 16) | (green << 8) | blue;
            }
        }

        outWidthHeight[0] = width;
        outWidthHeight[1] = height;
        return pixels;
    }

    private static void writeHeader(byte[] out, int width, int height) {
        writeI32(out, 0, HEADER_LENGTH);          // biSize
        writeI32(out, 4, width);                  // biWidth
        writeI32(out, 8, height);                 // biHeight, положительная — bottom-up
        writeU16(out, 12, 1);                     // biPlanes
        writeU16(out, 14, 32);                    // biBitCount
        writeI32(out, 16, BI_RGB);                // biCompression
        writeI32(out, 20, width * height * 4);    // biSizeImage
        writeI32(out, 24, 0);                     // biXPelsPerMeter
        writeI32(out, 28, 0);                     // biYPelsPerMeter
        writeI32(out, 32, 0);                     // biClrUsed
        writeI32(out, 36, 0);                     // biClrImportant
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

    private static void writeI32(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
        out[at + 2] = (byte) (value >>> 16);
        out[at + 3] = (byte) (value >>> 24);
    }

    private static void writeU16(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
    }
}
