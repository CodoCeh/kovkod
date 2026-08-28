package ru.codoceh.stol.screen;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

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
    /** Pseudo-encoding RFB Extended Clipboard (0xC0A1E5CE) — см. spec.md R05.1. */
    static final int PSEUDO_ENCODING_EXTENDED_CLIPBOARD = -1063131698;

    /** Extended Clipboard, флаги (см. spec.md R06.1): бит формата Text — бит 0. */
    static final int EXT_CLIPBOARD_FORMAT_TEXT = 1;
    /** Extended Clipboard: бит формата DIB (картинка) — бит 3; RTF/HTML/Files не заявляем. */
    static final int EXT_CLIPBOARD_FORMAT_DIB = 1 << 3;
    /** Extended Clipboard: бит действия Caps — бит 24 (биты 25-28 — Request/Peek/Notify/Provide). */
    static final int EXT_CLIPBOARD_ACTION_CAPS = 1 << 24;
    /** Extended Clipboard: бит действия Provide — бит 28, данные форматов сжаты zlib. */
    static final int EXT_CLIPBOARD_ACTION_PROVIDE = 1 << 28;

    private static final String TAG = "RfbClient";

    /** Максимум текста, который принимаем без запроса, — см. spec.md R06.1. */
    static final int EXT_CLIPBOARD_MAX_TEXT_SIZE = 1 << 20;
    /** Максимум DIB-картинки без запроса — см. spec.md R06.1. */
    static final int EXT_CLIPBOARD_MAX_DIB_SIZE = 8 << 20;

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

    /**
     * Согласование Extended Clipboard — свойство ТЕКУЩЕГО соединения (spec.md R01.1):
     * пока Caps от собеседника не пришёл или DIB в них не заявлен, картиночный путь
     * остаётся отключённым, никакой ошибки это не вызывает.
     */
    private volatile boolean serverSupportsDib;
    private volatile int serverMaxDibSize;

    public RfbClient(int port, FramebufferSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("некуда класть кадр");
        }
        this.port = port;
        this.sink = sink;
    }

    /** Рукопожатие и первый запрос кадра. Возвращается уже подключённым. */
    public void connect() throws IOException {
        // Согласование картинок — свойство этого соединения (spec.md R01.1 «Границы»):
        // при переподключении сбрасываем, чтобы не унести результат прошлой сессии.
        serverSupportsDib = false;
        serverMaxDibSize = 0;

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
        sendExtendedClipboardCaps();
    }

    /**
     * Заявляет собеседнику наши возможности Extended Clipboard (spec.md R01.1): это
     * тот же {@code ClientCutText} с отрицательной длиной, что и на приёме, — флаги
     * Caps|Text|DIB, а следом по 4 байта максимального размера без предварительного
     * запроса для каждого заявленного формата, в порядке возрастания номера бита
     * (Text, затем DIB).
     */
    private void sendExtendedClipboardCaps() throws IOException {
        int flags = EXT_CLIPBOARD_ACTION_CAPS | EXT_CLIPBOARD_FORMAT_TEXT | EXT_CLIPBOARD_FORMAT_DIB;
        int payloadLength = 4 + 4 + 4; // флаги + maxSize(Text) + maxSize(DIB)
        byte[] message = new byte[8 + payloadLength];
        message[0] = 6; // ClientCutText
        putU32(message, 4, -payloadLength);
        putU32(message, 8, flags);
        putU32(message, 12, EXT_CLIPBOARD_MAX_TEXT_SIZE);
        putU32(message, 16, EXT_CLIPBOARD_MAX_DIB_SIZE);
        send(message);
    }

    /**
     * Согласован ли Extended Clipboard настолько, что {@code sendExtendedClipboardImage}
     * (таск 03) не будет впустую: собеседник прислал Caps и заявил в них формат DIB.
     * См. interfaces.md.
     */
    public boolean isExtendedClipboardImageSupported() {
        return serverSupportsDib;
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
        int[] encodings = {
                ENCODING_COPY_RECT, ENCODING_RAW, ENCODING_DESKTOP_SIZE,
                PSEUDO_ENCODING_EXTENDED_CLIPBOARD
        };
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
                int textLen = readU32();
                if (textLen < 0) {
                    readExtendedClipboardMessage(textLen);
                    break;
                }
                byte[] textBytes = new byte[textLen];
                readFully(textBytes, textLen);
                String cutText = new String(textBytes, UTF8);
                CutTextListener listener = cutTextListener;
                if (listener != null) {
                    listener.onCutText(cutText);
                }
                break;
            default:
                throw new IOException("сервер прислал сообщение " + type + ", которого нет в RFB 3.8");
        }
        return type;
    }

    /**
     * Разбирает Extended Clipboard кадр {@code ServerCutText} с отрицательной длиной
     * (RFB Extended Clipboard, см. spec.md R05.1): следующие {@code |length|} байт — это
     * 4-байтное поле флагов (big-endian), за которым может идти полезная нагрузка
     * (Caps/Provide). Разбор самих флагов и нагрузки — таски 02/03.
     */
    private void readExtendedClipboardMessage(int length) throws IOException {
        int payloadLength = -length;
        byte[] payload = new byte[payloadLength];
        readFully(payload, payloadLength);
        int flags = payloadLength >= 4
                ? (payload[0] << 24) | ((payload[1] & 0xff) << 16)
                        | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff)
                : 0;
        int remainingLength = Math.max(0, payloadLength - 4);
        byte[] remainingBytes = new byte[remainingLength];
        System.arraycopy(payload, payloadLength - remainingLength, remainingBytes, 0, remainingLength);
        handleExtendedClipboardMessage(flags, remainingBytes);
    }

    /**
     * Разбирает Caps/Provide — единственные действия Extended Clipboard, которые нам
     * нужны (Request/Peek/Notify — вне объёма задачи, молча игнорируются).
     */
    private void handleExtendedClipboardMessage(int flags, byte[] remainingBytes) {
        if ((flags & EXT_CLIPBOARD_ACTION_CAPS) != 0) {
            handleExtendedClipboardCaps(flags, remainingBytes);
            return;
        }
        if ((flags & EXT_CLIPBOARD_ACTION_PROVIDE) != 0) {
            handleExtendedClipboardProvide(flags, remainingBytes);
        }
    }

    /**
     * Разбирает Caps — ответ собеседника на {@link #sendExtendedClipboardCaps()}: тело —
     * по 4 байта максимального размера для каждого формата, чей бит установлен во
     * {@code flags}, в порядке возрастания номера бита (Text=0, RTF=1, HTML=2, DIB=3,
     * Files=4) — см. spec.md R06.1. Нас интересует только DIB, но чтобы дойти до его
     * записи, нужно честно пропустить записи форматов перед ним, даже не поддерживаемых
     * нами (RTF/HTML).
     */
    private void handleExtendedClipboardCaps(int flags, byte[] remainingBytes) {
        boolean dibSupported = false;
        int dibMaxSize = 0;
        int offset = 0;
        for (int bit = 0; bit < 5; bit++) {
            int formatBit = 1 << bit;
            if ((flags & formatBit) == 0) {
                continue;
            }
            if (offset + 4 > remainingBytes.length) {
                break; // тело короче, чем обещали флаги, — отказ, а не падение (R01.1)
            }
            int maxSize = ((remainingBytes[offset] & 0xff) << 24)
                    | ((remainingBytes[offset + 1] & 0xff) << 16)
                    | ((remainingBytes[offset + 2] & 0xff) << 8)
                    | (remainingBytes[offset + 3] & 0xff);
            if (formatBit == EXT_CLIPBOARD_FORMAT_DIB) {
                dibSupported = true;
                dibMaxSize = maxSize;
            }
            offset += 4;
        }
        serverSupportsDib = dibSupported;
        serverMaxDibSize = dibMaxSize;
    }

    /**
     * Разбирает Provide (spec.md R02.2/R06.1): {@code compressed} — блок, сжатый zlib,
     * внутри которого один за другим лежат блоки по форматам, чей бит установлен во
     * {@code flags} (в порядке возрастания номера бита) — 4 байта big-endian длины,
     * затем сами данные. Текст уходит через существующий {@link CutTextListener}, DIB —
     * через {@link ExtendedClipboardImageListener}. Любая нестыковка (битый zlib,
     * заявленная длина не сходится с данными, кривой заголовок DIB) — это одно плохое
     * сообщение, а не повод рвать соединение: логируем и молча игнорируем (R02.2
     * «Кривой ввод / отказ»).
     */
    private void handleExtendedClipboardProvide(int flags, byte[] compressed) {
        try {
            byte[] decompressed = inflateZlib(compressed);
            String text = null;
            byte[] dibBytes = null;
            int offset = 0;
            for (int bit = 0; bit < 5; bit++) {
                int formatBit = 1 << bit;
                if ((flags & formatBit) == 0) {
                    continue;
                }
                if (offset + 4 > decompressed.length) {
                    throw new IOException("Provide короче, чем обещали флаги форматов");
                }
                int dataLength = ((decompressed[offset] & 0xff) << 24)
                        | ((decompressed[offset + 1] & 0xff) << 16)
                        | ((decompressed[offset + 2] & 0xff) << 8)
                        | (decompressed[offset + 3] & 0xff);
                offset += 4;
                if (dataLength < 0 || offset + dataLength > decompressed.length) {
                    throw new IOException("длина формата Provide не сходится с данными");
                }
                byte[] data = new byte[dataLength];
                System.arraycopy(decompressed, offset, data, 0, dataLength);
                offset += dataLength;
                if (formatBit == EXT_CLIPBOARD_FORMAT_TEXT) {
                    text = new String(data, UTF8);
                } else if (formatBit == EXT_CLIPBOARD_FORMAT_DIB) {
                    dibBytes = data;
                }
            }
            if (dibBytes != null && !isPlausibleDibHeader(dibBytes)) {
                Log.w(TAG, "Extended Clipboard Provide: некорректные размеры в заголовке DIB, отброшено");
                dibBytes = null;
            }
            if (text != null) {
                CutTextListener listener = cutTextListener;
                if (listener != null) {
                    listener.onCutText(text);
                }
            }
            if (dibBytes != null) {
                ExtendedClipboardImageListener listener = extendedClipboardImageListener;
                if (listener != null) {
                    listener.onExtendedClipboardImage(dibBytes);
                }
            }
        } catch (IOException malformed) {
            Log.w(TAG, "Extended Clipboard Provide проигнорирован: " + malformed.getMessage());
        }
    }

    /**
     * Распаковывает zlib-блок (стандартный {@code java.util.zip} формат, не raw deflate и
     * не gzip). Бросает {@link IOException} на битых/усечённых данных вместо того, чтобы
     * пробрасывать {@link DataFormatException} наружу.
     */
    private static byte[] inflateZlib(byte[] compressed) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.max(64, compressed.length * 3));
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("zlib-блок Extended Clipboard оборван");
                    }
                    break;
                }
                result.write(buffer, 0, count);
            }
        } catch (DataFormatException malformed) {
            throw new IOException("zlib-блок Extended Clipboard повреждён", malformed);
        } finally {
            inflater.end();
        }
        return result.toByteArray();
    }

    /** Сжимает zlib — симметрично {@link #inflateZlib(byte[])}, для исходящего Provide. */
    private static byte[] deflateZlib(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream result = new ByteArrayOutputStream(Math.max(64, input.length));
        byte[] buffer = new byte[8192];
        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                result.write(buffer, 0, count);
            }
        } finally {
            deflater.end();
        }
        return result.toByteArray();
    }

    /**
     * Базовая проверка заголовка DIB (BITMAPINFOHEADER, little-endian, spec.md R02.2
     * «Кривой ввод»): достаточно байт под заголовок, разумный размер заголовка и
     * положительные/ненулевые размеры картинки. Не проверяет пиксельные данные —
     * это уже забота вызывающей стороны (interfaces.md, граница RfbClient).
     */
    private static boolean isPlausibleDibHeader(byte[] dib) {
        if (dib.length < 40) {
            return false;
        }
        long headerSize = readLeU32(dib, 0);
        int width = readLeI32(dib, 4);
        int height = readLeI32(dib, 8);
        return headerSize >= 40 && width > 0 && height != 0;
    }

    private static long readLeU32(byte[] data, int at) {
        return (data[at] & 0xffL) | ((data[at + 1] & 0xffL) << 8)
                | ((data[at + 2] & 0xffL) << 16) | ((data[at + 3] & 0xffL) << 24);
    }

    private static int readLeI32(byte[] data, int at) {
        return (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8)
                | ((data[at + 2] & 0xff) << 16) | ((data[at + 3] & 0xff) << 24);
    }

    public interface CutTextListener {
        void onCutText(String text);
    }

    private volatile CutTextListener cutTextListener;

    public void setCutTextListener(CutTextListener listener) {
        this.cutTextListener = listener;
    }

    /** Аналог {@link CutTextListener}, но для картинок (interfaces.md). */
    public interface ExtendedClipboardImageListener {
        void onExtendedClipboardImage(byte[] dibBytes);
    }

    private volatile ExtendedClipboardImageListener extendedClipboardImageListener;

    public void setExtendedClipboardImageListener(ExtendedClipboardImageListener listener) {
        this.extendedClipboardImageListener = listener;
    }

    /**
     * Наименьший промежуток между двумя запросами кадра, миллисекунды; {@code 0} — без
     * ограничения, следующий кадр просится сразу после предыдущего. На неподвижном столе
     * это ни на что не влияет — поток и так спит в {@code read} между кадрами; ограничение
     * заметно только там, где стол непрерывно что-то перерисовывает.
     */
    private volatile long minFrameIntervalMs;

    public void setMinFrameIntervalMs(long ms) {
        this.minFrameIntervalMs = Math.max(0, ms);
    }

    public void sendCutText(String text) throws IOException {
        if (text == null) {
            return;
        }
        byte[] textBytes = text.getBytes(UTF8);
        byte[] message = new byte[8 + textBytes.length];
        message[0] = 6;
        message[1] = 0;
        message[2] = 0;
        message[3] = 0;
        putU32(message, 4, textBytes.length);
        System.arraycopy(textBytes, 0, message, 8, textBytes.length);
        send(message);
    }

    /**
     * Отправляет DIB-картинку через Extended Clipboard Provide (interfaces.md, spec.md
     * R02.1). Тихо ничего не делает, если Extended Clipboard с DIB не согласован
     * ({@link #isExtendedClipboardImageSupported()} лжёт не бывает) или картинка больше
     * заявленного собеседником максимума без запроса — не режем и не масштабируем,
     * просто не отправляем (R02.1 «Рост»).
     */
    public void sendExtendedClipboardImage(byte[] dibBytes) throws IOException {
        if (!serverSupportsDib || dibBytes == null || dibBytes.length > serverMaxDibSize) {
            return;
        }
        byte[] body = new byte[4 + dibBytes.length];
        putU32(body, 0, dibBytes.length);
        System.arraycopy(dibBytes, 0, body, 4, dibBytes.length);
        byte[] compressed = deflateZlib(body);

        int flags = EXT_CLIPBOARD_ACTION_PROVIDE | EXT_CLIPBOARD_FORMAT_DIB;
        int payloadLength = 4 + compressed.length;
        byte[] message = new byte[8 + payloadLength];
        message[0] = 6; // ClientCutText
        putU32(message, 4, -payloadLength);
        putU32(message, 8, flags);
        System.arraycopy(compressed, 0, message, 12, compressed.length);
        send(message);
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
        if (minFrameIntervalMs > 0) {
            try {
                Thread.sleep(minFrameIntervalMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
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
