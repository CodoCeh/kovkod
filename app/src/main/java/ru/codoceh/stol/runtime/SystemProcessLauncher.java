package ru.codoceh.stol.runtime;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Единственное место в приложении, где создаётся процесс. */
final class SystemProcessLauncher implements ProcessLauncher {

    private static final String TAG = "stol";

    @Override
    public int run(List<String> argv, Map<String, String> env, boolean background) throws IOException {
        Process process = start(argv, env);
        if (background) {
            return 0;
        }
        try {
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("запуск прерван", interrupted);
        }
    }

    @Override
    public Handle open(List<String> argv, Map<String, String> env) throws IOException {
        Process process = start(argv, env);
        return new Handle() {
            @Override
            public boolean isAlive() {
                return process.isAlive();
            }

            @Override
            public void close() {
                // Сессия заканчивается сама, изнутри образа: сигнал снаружи снимает только
                // её ведущий процесс, а всё, что он вёл, остаётся сиротами. Поэтому здесь
                // не убийство, а ожидание — и сигнал лишь на случай, если ждать нечего.
                try {
                    if (process.waitFor(10, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                process.destroy();
            }
        };
    }

    private static Process start(List<String> argv, Map<String, String> env) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        drain(process);
        return process;
    }

    private static void drain(Process process) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    Log.i(TAG, line);
                }
            } catch (IOException ignored) {
                // поток закрылся вместе с процессом
            }
        }, "stol-output");
        reader.setDaemon(true);
        reader.start();
    }
}
