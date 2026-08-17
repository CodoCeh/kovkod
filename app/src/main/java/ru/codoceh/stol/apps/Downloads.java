package ru.codoceh.stol.apps;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Загрузка файла программы средствами Android. Загрузчика в образе нет и не будет:
 * лишний бинарник в сборке — лишние права, лишний вес и ещё одна вещь, которая может
 * отсутствовать. Ответ разбирается сам, чтобы обрыв связи был отличим от отказа источника.
 */
final class Downloads {

    private static final int TIMEOUT_MS = 30_000;
    /** Официальные адреса водят через зеркала: без перенаправлений не скачать. */
    private static final int MAX_HOPS = 5;
    private static final long PROGRESS_STEP = 512L * 1024;

    /** Куда сообщать ход загрузки. */
    interface Watcher {
        void onBytes(long doneBytes, long totalBytes);
    }

    private Downloads() {
    }

    /** Кладёт файл по адресу в {@code target}. Недокачанный файл остаётся на совести вызвавшего. */
    static void fetch(String address, File target, Watcher watcher) throws AppInstallException {
        String current = address;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            HttpURLConnection connection = open(current);
            int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException silent) {
                connection.disconnect();
                throw new AppInstallException(AppInstallException.Reason.NO_SOURCE,
                        "источник не ответил: " + silent, silent);
            }
            if (code >= 300 && code < 400) {
                String next = connection.getHeaderField("Location");
                connection.disconnect();
                current = redirect(current, next);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new AppInstallException(AppInstallException.Reason.NO_SOURCE,
                        "источник ответил " + code);
            }
            try {
                copy(connection, target, watcher);
            } finally {
                connection.disconnect();
            }
            return;
        }
        throw new AppInstallException(AppInstallException.Reason.NO_SOURCE,
                "источник водит по кругу перенаправлений");
    }

    private static HttpURLConnection open(String address) throws AppInstallException {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            // Перенаправления разбираем сами: между http и https встроенный клиент не ходит.
            connection.setInstanceFollowRedirects(false);
            // Без сжатия на лету: иначе обещанная длина не сойдётся с полученной и обрыв
            // станет неотличим от нормальной загрузки.
            connection.setRequestProperty("Accept-Encoding", "identity");
            return connection;
        } catch (IOException | ClassCastException | IllegalArgumentException wrong) {
            throw new AppInstallException(AppInstallException.Reason.NO_SOURCE,
                    "к источнику не подключиться: " + wrong, wrong);
        }
    }

    private static String redirect(String from, String location) throws AppInstallException {
        if (location == null || location.trim().isEmpty()) {
            throw new AppInstallException(AppInstallException.Reason.NO_SOURCE,
                    "источник перенаправил в никуда");
        }
        try {
            return new URL(new URL(from), location.trim()).toString();
        } catch (IOException wrong) {
            throw new AppInstallException(AppInstallException.Reason.NO_SOURCE,
                    "источник перенаправил по неразборчивому адресу: " + location, wrong);
        }
    }

    private static void copy(HttpURLConnection connection, File target, Watcher watcher)
            throws AppInstallException {
        long promised = connection.getContentLengthLong();
        long done = 0;
        long reported = 0;
        try (InputStream from = connection.getInputStream();
             OutputStream to = new FileOutputStream(target)) {
            byte[] chunk = new byte[65536];
            int read;
            while ((read = from.read(chunk)) > 0) {
                to.write(chunk, 0, read);
                done += read;
                if (done - reported >= PROGRESS_STEP) {
                    reported = done;
                    watcher.onBytes(done, promised);
                }
            }
        } catch (IOException broken) {
            throw new AppInstallException(AppInstallException.Reason.BROKEN_LINK,
                    "связь оборвалась после " + done + " байт", broken);
        }
        watcher.onBytes(done, promised);

        // Обещанная длина и полученная — единственный способ отличить целый файл от куска.
        if (promised > 0 && done != promised) {
            throw new AppInstallException(AppInstallException.Reason.BROKEN_LINK,
                    "пришло " + done + " байт из " + promised);
        }
        if (done == 0) {
            throw new AppInstallException(AppInstallException.Reason.NO_SOURCE,
                    "источник отдал пустой файл");
        }
    }
}
