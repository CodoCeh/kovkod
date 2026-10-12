package ru.codoceh.stol.settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reads only the latest release metadata; the APK itself is handed to Android Downloads. */
final class ReleaseChecker {

    static final String RELEASES = "https://api.github.com/repos/CodoCeh/kovkod/releases?per_page=20";

    interface Source {
        String read() throws IOException;
    }

    static ReleaseInfo latest(Source source) throws IOException {
        ReleaseInfo result = ReleaseInfo.newestStableFromGithub(source.read());
        if (result == null) {
            throw new IOException("В выпуске нет подходящего APK");
        }
        return result;
    }

    static final class GithubSource implements Source {
        @Override
        public String read() throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "KovKod-Android");
            try {
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Сервер обновлений не ответил");
                }
                try (InputStream input = connection.getInputStream();
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    for (int count; (count = input.read(buffer)) != -1;) {
                        output.write(buffer, 0, count);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            } finally {
                connection.disconnect();
            }
        }
    }
}
