package ru.codoceh.stol.runtime;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;

/** Единственное место в приложении, где создаётся процесс. */
final class SystemProcessLauncher implements ProcessLauncher {

    private static final String TAG = "stol";

    @Override
    public int run(List<String> argv, Map<String, String> env, boolean background) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        drain(process);
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
