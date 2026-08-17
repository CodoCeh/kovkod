package ru.codoceh.stol.screen;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Клиент RFB, написанный по RFC 6143 — рукопожатие 3.8, тип безопасности None, кодировки
 * Raw и CopyRect, события мыши и клавиатуры. Чужого кода внутри нет: ни AVNC, ни
 * LibVNCClient, ни производных от них (R16.4).
 *
 * <p>Ходим только на петлю одного устройства, поэтому нет ни сжатия, ни шифрования, ни
 * пароля, ни списка серверов: на 127.0.0.1 они стоят дороже, чем дают. Адрес не
 * настраивается — только порт, и это же держит R15.2 с нашей стороны.
 *
 * <p>Чтение блокирующее: следующий кадр запрашивается инкрементально, и на неподвижном
 * столе поток спит в {@code read}, а не крутит опрос.
 */
public final class RfbClient implements Closeable {

    /** Порт Xtigervnc для дисплея :1 — см. шов 3. */
    public static final int DEFAULT_PORT = 5901;

    public static final int BUTTON_LEFT = 1;
    public static final int BUTTON_RIGHT = 1 << 2;
    /** Колесо вверх — кнопка 4 протокола. */
    public static final int BUTTON_WHEEL_UP = 1 << 3;
    /** Колесо вниз — кнопка 5 протокола. */
    public static final int BUTTON_WHEEL_DOWN = 1 << 4;

    static final int ENCODING_RAW = 0;
    static final int ENCODING_COPY_RECT = 1;
    static final int ENCODING_DESKTOP_SIZE = -223;

    static final String VERSION = "RFB 003.008\n";
    static final int SECURITY_NONE = 1;

    private static final int SERVER_FRAMEBUFFER_UPDATE = 0;
    private static final int SERVER_SET_COLOUR_MAP = 1;
    private static final int SERVER_BELL = 2;
    private static final int SERVER_CUT_TEXT = 3;

    private static final int CLIENT_SET_PIXEL_FORMAT = 0;
    private static final int CLIENT_SET_ENCODINGS = 2;
    private static final int CLIENT_UPDATE_REQUEST = 3;
    private static final int CLIENT_KEY_EVENT = 4;
    private static final int CLIENT_POINTER_EVENT = 5;

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final int port;
    private final FramebufferSink sink;
    private final Object writeLock = new Object();

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private String desktopName = "";
    private int width;
    private int height;
    private PixelFormat serverFormat;
    private PixelFormat format = PixelFormat.PREFERRED;

    private byte[] rowBytes = new byte[0];
    private int[] rowPixels = new int[0];
    private volatile boolean closed;

    public RfbClient(int port, FramebufferSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("некуда класть кадр");
        }
        this.port = port;
        this.sink = sink;
    }

    /** Рукопожатие и первый запрос кадра. Возвращается уже подключённым. */
    public void connect() throws IOException {
        Socket connecting = new Socket();
        connecting.connect(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
        connecting.setTcpNoDelay(true);
        socket = connecting;
        in = new BufferedInputStream(connecting.getInputStream(), 1 << 16);
        out = new BufferedOutputStream(connecting.getOutputStream(), 1 << 13);

        handshake();
        sendPixelFormat();
        sendEncodings();
        requestUpdate(false);
    }

    /**
     * Читает сообщения сервера, пока соединение живо. Вызывается из отдельного потока;
     * {@link #close()} разрывает чтение.
     */
    public void pump() throws IOException {
        while (!closed) {
            readServerMessage();
        }
    }

    public String desktopName() {
        return desktopName;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Формат, который объявил сервер в {@code ServerInit}. */
    public PixelFormat serverFormat() {
        return serverFormat;
    }

    @Override
    public void close() {
        closed = true;
        Socket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // разрываем связь, а не чиним её
            }
        }
    }

    // --- клиент → сервер -------------------------------------------------------------

    /** Положение курсора и набор нажатых кнопок (RFC 6143, PointerEvent). */
    public void sendPointer(int x, int y, int buttonMask) throws IOException {
        byte[] message = new byte[6];
        message[0] = CLIENT_POINTER_EVENT;
        message[1] = (byte) buttonMask;
        putU16(message, 2, x);
        putU16(message, 4, y);
        send(message);
    }

    /** Нажатие или отпускание клавиши; {@code keysym} — по таблице X11, см. {@link Keysyms}. */
    public void sendKey(int keysym, boolean down) throws IOException {
        byte[] message = new byte[8];
        message[0] = CLIENT_KEY_EVENT;
        message[1] = (byte) (down ? 1 : 0);
        putU32(message, 4, keysym);
        send(message);
    }

    /** Щелчок колесом: в RFB это кнопки 4 и 5, нажатые и сразу отпущенные. */
    public void sendWheel(int x, int y, boolean up) throws IOException {
        int button = up ? BUTTON_WHEEL_UP : BUTTON_WHEEL_DOWN;
        sendPointer(x, y, button);
        sendPointer(x, y, 0);
    }

    /**
     * Просит кадр. Инкрементально — значит «пришли, что изменилось»: на неподвижном столе
     * сервер молчит, и мы не жжём батарею.
     */
    public void requestUpdate(boolean incremental) throws IOException {
        byte[] message = new byte[10];
        message[0] = CLIENT_UPDATE_REQUEST;
        message[1] = (byte) (incremental ? 1 : 0);
        putU16(message, 2, 0);
        putU16(message, 4, 0);
        putU16(message, 6, width);
        putU16(message, 8, height);
        send(message);
    }

    private void sendPixelFormat() throws IOException {
        byte[] message = new byte[4 + PixelFormat.LENGTH];
        message[0] = CLIENT_SET_PIXEL_FORMAT;
        PixelFormat.PREFERRED.write(message, 4);
        send(message);
        format = PixelFormat.PREFERRED;
    }

    private void sendEncodings() throws IOException {
        int[] encodings = {ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_DESKTOP_SIZE};
        byte[] message = new byte[4 + encodings.length * 4];
        message[0] = CLIENT_SET_ENCODINGS;
        putU16(message, 2, encodings.length);
        for (int i = 0; i < encodings.length; i++) {
            putU32(message, 4 + i * 4, encodings[i]);
        }
        send(message);
    }

    private void send(byte[] message) throws IOException {
        synchronized (writeLock) {
            out.write(message);
            out.flush();
        }
    }

    // --- рукопожатие -----------------------------------------------------------------

    private void handshake() throws IOException {
        byte[] version = new byte[12];
        readFully(version, 12);
        String banner = new String(version, ASCII);
        if (!VERSION.equals(banner)) {
            throw new IOException("на порту не RFB 3.8, а «" + banner.trim() + "»");
        }
        send(VERSION.getBytes(ASCII));

        int types = readU8();
        if (types == 0) {
            throw new IOException("сервер отказал в подключении: " + readReason());
        }
        byte[] offered = new byte[types];
        readFully(offered, types);
        if (!offers(offered, SECURITY_NONE)) {
            throw new IOException("сервер требует проверку личности, а на петле её не бывает");
        }
        send(new byte[]{(byte) SECURITY_NONE});

        int result = readU32();
        if (result != 0) {
            throw new IOException("сервер не пустил: " + readReason());
        }

        // ClientInit: стол общий — оставляем чужие подключения в живых.
        send(new byte[]{1});

        width = readU16();
        height = readU16();
        byte[] pixelFormat = new byte[PixelFormat.LENGTH];
        readFully(pixelFormat, PixelFormat.LENGTH);
        serverFormat = PixelFormat.parse(pixelFormat, 0);
        format = serverFormat;
        int nameLength = readU32();
        byte[] name = new byte[nameLength];
        readFully(name, nameLength);
        desktopName = new String(name, UTF8);
        sink.resize(width, height);
    }

    private static boolean offers(byte[] types, int wanted) {
        for (byte type : types) {
            if ((type & 0xff) == wanted) {
                return true;
            }
        }
        return false;
    }

    private String readReason() throws IOException {
        int length = readU32();
        byte[] reason = new byte[length];
        readFully(reason, length);
        return new String(reason, UTF8);
    }

    // --- сервер → клиент -------------------------------------------------------------

    /** Разбирает ровно одно сообщение сервера. Отдельный метод, чтобы тест шагал по кадрам. */
    int readServerMessage() throws IOException {
        int type = readU8();
        switch (type) {
            case SERVER_FRAMEBUFFER_UPDATE:
                readFramebufferUpdate();
                break;
            case SERVER_SET_COLOUR_MAP:
                skipColourMap();
                break;
            case SERVER_BELL:
                break;
            case SERVER_CUT_TEXT:
                skip(3);
                skip(readU32());
                break;
            default:
                throw new IOException("сервер прислал сообщение " + type + ", которого нет в RFB 3.8");
        }
        return type;
    }

    private void readFramebufferUpdate() throws IOException {
        skip(1);
        int rectangles = readU16();
        boolean resized = false;
        for (int i = 0; i < rectangles; i++) {
            int x = readU16();
            int y = readU16();
            int rectWidth = readU16();
            int rectHeight = readU16();
            int encoding = readU32();
            switch (encoding) {
                case ENCODING_RAW:
                    readRaw(x, y, rectWidth, rectHeight);
                    break;
                case ENCODING_COPY_RECT:
                    int sourceX = readU16();
                    int sourceY = readU16();
                    sink.copy(sourceX, sourceY, x, y, rectWidth, rectHeight);
                    break;
                case ENCODING_DESKTOP_SIZE:
                    width = rectWidth;
                    height = rectHeight;
                    sink.resize(rectWidth, rectHeight);
                    resized = true;
                    break;
                default:
                    throw new IOException("сервер прислал кодировку " + encoding
                            + ", хотя мы просили только Raw и CopyRect");
            }
        }
        sink.frameComplete();
        // После смены размера прежнего содержимого нет — просим кадр целиком.
        requestUpdate(!resized);
    }

    private void readRaw(int x, int y, int rectWidth, int rectHeight) throws IOException {
        int bytesPerPixel = format.bytesPerPixel();
        int lineBytes = rectWidth * bytesPerPixel;
        if (rowBytes.length < lineBytes) {
            rowBytes = new byte[lineBytes];
        }
        if (rowPixels.length < rectWidth) {
            rowPixels = new int[rectWidth];
        }
        // Строка за строкой: полный кадр 1600×1000 — это шесть мегабайт, держать их незачем.
        for (int row = 0; row < rectHeight; row++) {
            readFully(rowBytes, lineBytes);
            for (int column = 0; column < rectWidth; column++) {
                rowPixels[column] = format.toArgb(rowBytes, column * bytesPerPixel);
            }
            sink.pixels(x, y + row, rectWidth, 1, rowPixels);
        }
    }

    private void skipColourMap() throws IOException {
        skip(1);
        skip(2);
        int colours = readU16();
        skip(colours * 6);
    }

    // --- чтение примитивов -----------------------------------------------------------

    private int readU8() throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("сервер закрыл соединение");
        }
        return value;
    }

    private int readU16() throws IOException {
        return (readU8() << 8) | readU8();
    }

    private int readU32() throws IOException {
        return (readU8() << 24) | (readU8() << 16) | (readU8() << 8) | readU8();
    }

    private void readFully(byte[] buffer, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int step = in.read(buffer, read, length - read);
            if (step < 0) {
                throw new EOFException("сервер закрыл соединение на середине сообщения");
            }
            read += step;
        }
    }

    private void skip(int length) throws IOException {
        for (int left = length; left > 0; ) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                readU8();
                left--;
            } else {
                left -= (int) skipped;
            }
        }
    }

    private static void putU16(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 8);
        out[at + 1] = (byte) value;
    }

    private static void putU32(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }
}
