package ru.codoceh.stol.screen;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Поддельный сервер RFB на петле: говорит рукопожатие по RFC 6143, отдаёт заготовленные
 * кадры и запоминает всё, что прислал клиент. Настоящего Xtigervnc в тестах нет, а моки
 * не поймали бы порядок байтов — поэтому здесь настоящий сокет и настоящие байты.
 */
final class FakeRfbServer implements Closeable {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long TIMEOUT_MS = 5000;

    private final ServerSocket listener;
    private final Object lock = new Object();
    private final List<byte[]> received = new ArrayList<>();
    private final Deque<byte[]> frames = new ArrayDeque<>();

    private String banner = RfbClient.VERSION;
    private String refusal;
    private int width = 4;
    private int height = 3;
    private String name = "кодоцех-стол";

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread thread;

    private String clientVersion;
    private int chosenSecurity = -1;
    private int sharedFlag = -1;
    private int owedFrames;

    FakeRfbServer() throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    }

    int port() {
        return listener.getLocalPort();
    }

    FakeRfbServer desktop(int width, int height, String name) {
        this.width = width;
        this.height = height;
        this.name = name;
        return this;
    }

    FakeRfbServer banner(String banner) {
        this.banner = banner;
        return this;
    }

    FakeRfbServer refuseWith(String reason) {
        this.refusal = reason;
        return this;
    }

    /** Кадр уйдёт клиенту в ответ на запрос — уже пришедший или следующий. */
    void enqueue(byte[] frame) throws IOException {
        synchronized (lock) {
            if (owedFrames > 0 && out != null) {
                owedFrames--;
                write(frame);
            } else {
                frames.add(frame);
            }
        }
    }

    /** Сообщение, которого клиент не просил: звонок, буфер обмена. */
    void send(byte[] message) throws IOException {
        write(message);
    }

    void start() {
        thread = new Thread(this::serve, "поддельный-rfb");
        thread.setDaemon(true);
        thread.start();
    }

    String clientVersion() {
        return clientVersion;
    }

    int chosenSecurity() {
        return chosenSecurity;
    }

    int sharedFlag() {
        return sharedFlag;
    }

    /** Сколько сообщений от клиента уже принято — для проверок «ничего не ушло». */
    int receivedCount() {
        synchronized (lock) {
            return received.size();
        }
    }

    /** Ждёт, пока клиент пришлёт столько сообщений, и возвращает их по порядку. */
    List<byte[]> awaitMessages(int count) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        synchronized (lock) {
            while (received.size() < count) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    throw new AssertionError("клиент прислал " + received.size()
                            + " сообщений вместо " + count);
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("ожидание прервано");
                }
            }
            return new ArrayList<>(received);
        }
    }

    /** Первое сообщение клиента этого типа. */
    byte[] awaitMessage(int type, int count) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                int seen = 0;
                for (byte[] message : received) {
                    if ((message[0] & 0xff) == type && ++seen == count) {
                        return message;
                    }
                }
                try {
                    lock.wait(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("ожидание прервано");
                }
            }
        }
        throw new AssertionError("клиент не прислал сообщение типа " + type + " № " + count);
    }

    @Override
    public void close() {
        try {
            listener.close();
        } catch (IOException ignored) {
            // тест закончился
        }
        Socket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // тест закончился
            }
        }
    }

    // --- разговор --------------------------------------------------------------------

    private void serve() {
        try {
            socket = listener.accept();
            socket.setTcpNoDelay(true);
            in = socket.getInputStream();
            synchronized (lock) {
                out = socket.getOutputStream();
            }
            write(banner.getBytes(ASCII));
            clientVersion = new String(readBytes(12), ASCII);

            if (refusal != null) {
                byte[] reason = refusal.getBytes(UTF8);
                byte[] message = new byte[5 + reason.length];
                putU32(message, 1, reason.length);
                System.arraycopy(reason, 0, message, 5, reason.length);
                write(message);
                return;
            }

            write(new byte[]{1, (byte) RfbClient.SECURITY_NONE});
            chosenSecurity = readBytes(1)[0] & 0xff;
            byte[] securityResult = new byte[4];
            write(securityResult);
            sharedFlag = readBytes(1)[0] & 0xff;
            writeServerInit();

            while (true) {
                readClientMessage();
            }
        } catch (IOException stopped) {
            // соединение закрыл тест — это конец разговора, а не поломка
        }
    }

    private void writeServerInit() throws IOException {
        byte[] name = this.name.getBytes(UTF8);
        byte[] message = new byte[4 + PixelFormat.LENGTH + 4 + name.length];
        putU16(message, 0, width);
        putU16(message, 2, height);
        // Свой формат сервер объявляет старшим байтом вперёд — клиент обязан его разобрать
        // и всё равно переключить сервер на свой.
        new PixelFormat(32, 24, true, true, 255, 255, 255, 16, 8, 0)
                .write(message, 4);
        putU32(message, 4 + PixelFormat.LENGTH, name.length);
        System.arraycopy(name, 0, message, 8 + PixelFormat.LENGTH, name.length);
        write(message);
    }

    private void readClientMessage() throws IOException {
        int type = readBytes(1)[0] & 0xff;
        byte[] body;
        switch (type) {
            case 0:
                body = readBytes(3 + PixelFormat.LENGTH);
                break;
            case 2:
                byte[] head = readBytes(3);
                int encodings = ((head[1] & 0xff) << 8) | (head[2] & 0xff);
                body = join(head, readBytes(encodings * 4));
                break;
            case 3:
                body = readBytes(9);
                break;
            case 4:
                body = readBytes(7);
                break;
            case 5:
                body = readBytes(5);
                break;
            case 6:
                byte[] cutHead = readBytes(7);
                int length = ((cutHead[3] & 0xff) << 24) | ((cutHead[4] & 0xff) << 16)
                        | ((cutHead[5] & 0xff) << 8) | (cutHead[6] & 0xff);
                // Отрицательная длина — Extended Clipboard (Caps и т.п.): читаем |length|
                // байт, как и настоящий сервер, а не только классический положительный CutText.
                int payloadLength = length < 0 ? -length : length;
                body = join(cutHead, readBytes(payloadLength));
                break;
            default:
                throw new IOException("клиент прислал неизвестное сообщение " + type);
        }
        byte[] message = new byte[1 + body.length];
        message[0] = (byte) type;
        System.arraycopy(body, 0, message, 1, body.length);

        synchronized (lock) {
            received.add(message);
            if (type == 3) {
                if (frames.isEmpty()) {
                    owedFrames++;
                } else {
                    write(frames.poll());
                }
            }
            lock.notifyAll();
        }
    }

    private byte[] readBytes(int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int step = in.read(buffer, read, length - read);
            if (step < 0) {
                throw new EOFException("клиент закрыл соединение");
            }
            read += step;
        }
        return buffer;
    }

    private void write(byte[] bytes) throws IOException {
        synchronized (lock) {
            out.write(bytes);
            out.flush();
        }
    }

    // --- сборка кадров ---------------------------------------------------------------

    static byte[] frame(byte[]... rectangles) {
        int length = 4;
        for (byte[] rectangle : rectangles) {
            length += rectangle.length;
        }
        byte[] message = new byte[length];
        message[0] = 0;
        putU16(message, 2, rectangles.length);
        int at = 4;
        for (byte[] rectangle : rectangles) {
            System.arraycopy(rectangle, 0, message, at, rectangle.length);
            at += rectangle.length;
        }
        return message;
    }

    /** Прямоугольник Raw в формате, который просит клиент: младший байт первым, 32 бита. */
    static byte[] rawRectangle(int x, int y, int width, int height, int[] colours) {
        byte[] rectangle = new byte[12 + width * height * 4];
        header(rectangle, x, y, width, height, RfbClient.ENCODING_RAW);
        for (int i = 0; i < width * height; i++) {
            int at = 12 + i * 4;
            rectangle[at] = (byte) colours[i];
            rectangle[at + 1] = (byte) (colours[i] >>> 8);
            rectangle[at + 2] = (byte) (colours[i] >>> 16);
            rectangle[at + 3] = 0;
        }
        return rectangle;
    }

    static byte[] copyRectangle(int sourceX, int sourceY, int x, int y, int width, int height) {
        byte[] rectangle = new byte[16];
        header(rectangle, x, y, width, height, RfbClient.ENCODING_COPY_RECT);
        putU16(rectangle, 12, sourceX);
        putU16(rectangle, 14, sourceY);
        return rectangle;
    }

    static byte[] resizeRectangle(int width, int height) {
        byte[] rectangle = new byte[12];
        header(rectangle, 0, 0, width, height, RfbClient.ENCODING_DESKTOP_SIZE);
        return rectangle;
    }

    static byte[] unknownEncodingRectangle(int encoding) {
        byte[] rectangle = new byte[12];
        header(rectangle, 0, 0, 1, 1, encoding);
        return rectangle;
    }

    static byte[] bell() {
        return new byte[]{2};
    }

    static byte[] serverCutText(String text) {
        byte[] payload = text.getBytes(UTF8);
        byte[] message = new byte[8 + payload.length];
        message[0] = 3;
        putU32(message, 4, payload.length);
        System.arraycopy(payload, 0, message, 8, payload.length);
        return message;
    }

    /**
     * Extended Clipboard кадр: длина в заголовке — отрицательная, сразу за ней 4-байтные
     * флаги (big-endian), затем {@code extra} байт нагрузки — как шлёт TigerVNC для
     * Caps/Provide.
     */
    static byte[] serverCutTextExtended(int flags, byte[] extra) {
        int payloadLength = 4 + extra.length;
        byte[] message = new byte[8 + payloadLength];
        message[0] = 3;
        putU32(message, 4, -payloadLength);
        putU32(message, 8, flags);
        System.arraycopy(extra, 0, message, 12, extra.length);
        return message;
    }

    /**
     * Extended Clipboard Provide кадр: тело — {@code flags} (Provide|форматы), затем
     * уже готовый zlib-сжатый блок с данными форматов — ровно то, что должен разобрать
     * клиент. Сжатие тестового содержимого делает вызывающий тест (симметрично тому, что
     * должен делать сам клиент на отправке).
     */
    static byte[] serverCutTextExtendedProvide(int flags, byte[] compressedBlock) {
        return serverCutTextExtended(flags, compressedBlock);
    }

    private static void header(byte[] rectangle, int x, int y, int width, int height, int encoding) {
        putU16(rectangle, 0, x);
        putU16(rectangle, 2, y);
        putU16(rectangle, 4, width);
        putU16(rectangle, 6, height);
        putU32(rectangle, 8, encoding);
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] both = new byte[first.length + second.length];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
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
