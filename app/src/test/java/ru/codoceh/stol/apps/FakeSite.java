package ru.codoceh.stol.apps;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Поддельный сайт производителя на петле: отдаёт файл, отказывает, перенаправляет или
 * обрывает связь на середине. Настоящих сайтов в проверке нет, а мок вокруг загрузчика не
 * поймал бы ни обещанную длину, ни обрыв — поэтому здесь настоящий сокет.
 *
 * <p>Готового сервера из настольной Java здесь не взять: тесты собираются против android,
 * и {@code com.sun.net.httpserver} в этой сборке отсутствует.
 */
final class FakeSite implements Closeable {

    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** Что сайт отвечает на путь. */
    private static final class Answer {
        int code = 200;
        long promised;
        int given;
        String location;
    }

    private final ServerSocket listener;
    private final Map<String, Answer> answers = new LinkedHashMap<>();
    private final Thread thread;
    private volatile boolean running = true;

    FakeSite() throws IOException {
        listener = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "поддельный-сайт");
        thread.setDaemon(true);
        thread.start();
    }

    /** Адрес файла на этом сайте. */
    String address(String path) {
        return "http://127.0.0.1:" + listener.getLocalPort() + path;
    }

    /** Файл отдаётся целиком. */
    FakeSite file(String path, int bytes) {
        Answer answer = new Answer();
        answer.promised = bytes;
        answer.given = bytes;
        answers.put(path, answer);
        return this;
    }

    /** Сайт обещает файл, отдаёт кусок и обрывает связь. */
    FakeSite tornFile(String path, long promised, int given) {
        Answer answer = new Answer();
        answer.promised = promised;
        answer.given = given;
        answers.put(path, answer);
        return this;
    }

    /** Сайт отвечает отказом. */
    FakeSite refusal(String path, int code) {
        Answer answer = new Answer();
        answer.code = code;
        answers.put(path, answer);
        return this;
    }

    /** Сайт перенаправляет на другой свой путь — так живут официальные адреса. */
    FakeSite redirect(String path, String toPath) {
        Answer answer = new Answer();
        answer.code = 302;
        answer.location = address(toPath);
        answers.put(path, answer);
        return this;
    }

    private void serve() {
        while (running) {
            try (Socket client = listener.accept()) {
                answer(client);
            } catch (IOException stopped) {
                // сокет закрыт вместе с сайтом либо связь оборвал клиент
            }
        }
    }

    private void answer(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        String path = requestedPath(in);
        Answer answer = answers.get(path);
        OutputStream out = client.getOutputStream();
        if (answer == null) {
            out.write(header(404, 0, null));
            out.flush();
            return;
        }
        out.write(header(answer.code, answer.promised, answer.location));
        out.write(new byte[answer.given]);
        out.flush();
        // Связь закрывается здесь: обещали больше, чем отдали, — вот и обрыв.
    }

    private static byte[] header(int code, long length, String location) {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(code).append(" ")
                .append(code == 200 ? "OK" : "NO").append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Connection: close\r\n");
        if (location != null) {
            head.append("Location: ").append(location).append("\r\n");
        }
        return head.append("\r\n").toString().getBytes(ASCII);
    }

    private static String requestedPath(InputStream in) throws IOException {
        StringBuilder request = new StringBuilder();
        int symbol;
        while ((symbol = in.read()) >= 0) {
            request.append((char) symbol);
            if (request.length() >= 4 && request.indexOf("\r\n\r\n", request.length() - 4) >= 0) {
                break;
            }
        }
        String[] words = request.toString().split("\\s+");
        return words.length > 1 ? words[1] : "";
    }

    @Override
    public void close() throws IOException {
        running = false;
        listener.close();
    }
}
