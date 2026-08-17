package ru.codoceh.stol.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Единственная точка запуска команд внутри образа (шов 2). Никакой другой код приложения
 * proot не вызывает и процессов не создаёт: сюда приходит строка команды, отсюда уходит
 * код возврата.
 *
 * <p>Чужие бинарники запускаются отдельными процессами и в наш код не линкуются.
 */
public final class ProotSession {

    /** Образа нет или распаковка не завершена: {@code files/rootfs/.stol-ready} отсутствует. */
    public static final int EXIT_NO_ROOTFS = 78;

    /** Процесс не удалось даже запустить. */
    public static final int EXIT_LAUNCH_FAILED = 79;

    /** Метку ставит установщик образа последней операцией — см. шов 1. */
    public static final String READY_MARKER = ".stol-ready";

    private static final String GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /** Имена в каталоге библиотек: установщик распаковывает только файлы вида {@code lib*.so}. */
    private static final String PROOT = "libproot.so";
    private static final String LOADER = "libproot-loader.so";

    /**
     * Псевдоним → файл. Библиотека объявляет версионный SONAME ({@code libtalloc.so.2}), и
     * линкер ищет ровно это имя, а в каталог библиотек попадают только имена
     * {@code lib*.so}. Разницу закрывает символическая ссылка: имя для линкера берётся из
     * каталога данных, а отображается и исполняется файл из каталога библиотек.
     */
    private static final String[][] SONAME_LINKS = {{"libtalloc.so.2", "libtalloc.so"}};

    private final File nativeDir;
    private final File rootfs;
    private final ProcessLauncher launcher;

    public ProotSession(File nativeDir, File rootfs) {
        this(nativeDir, rootfs, new SystemProcessLauncher());
    }

    ProotSession(File nativeDir, File rootfs, ProcessLauncher launcher) {
        this.nativeDir = nativeDir;
        this.rootfs = rootfs;
        this.launcher = launcher;
    }

    /** Готов ли образ к запуску команд. Разворачивать образ — не дело этого класса. */
    public boolean isRootfsReady() {
        return new File(rootfs, READY_MARKER).isFile();
    }

    /**
     * Выполняет команду внутри образа через {@code /bin/sh -lc}.
     *
     * @param background не ждать завершения — так поднимается стол
     * @return код возврата команды, {@link #EXIT_NO_ROOTFS} если образа нет,
     *         {@link #EXIT_LAUNCH_FAILED} если процесс не запустился
     */
    public int exec(String command, boolean background) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("пустая команда");
        }
        if (!isRootfsReady()) {
            return EXIT_NO_ROOTFS;
        }
        try {
            prepareRuntime();
            return launcher.run(argv(command), env(), background);
        } catch (IOException failed) {
            return EXIT_LAUNCH_FAILED;
        }
    }

    private List<String> argv(String command) {
        List<String> argv = new ArrayList<>();
        argv.add(new File(nativeDir, PROOT).getAbsolutePath());
        argv.add("--kill-on-exit");
        argv.add("--link2symlink");
        argv.add("--sysvipc");
        argv.add("-0");
        argv.add("-r");
        argv.add(rootfs.getAbsolutePath());
        argv.add("-w");
        argv.add("/root");
        for (String bind : binds()) {
            argv.add("-b");
            argv.add(bind);
        }
        // env -i: своё окружение внутрь образа не пускаем, там пути Android.
        argv.add("/usr/bin/env");
        argv.add("-i");
        argv.add("HOME=/root");
        argv.add("PATH=" + GUEST_PATH);
        argv.add("TERM=xterm-256color");
        argv.add("LANG=C.UTF-8");
        argv.add("/bin/sh");
        argv.add("-lc");
        argv.add(command);
        return Collections.unmodifiableList(argv);
    }

    private List<String> binds() {
        List<String> binds = new ArrayList<>();
        binds.add("/dev");
        binds.add("/proc");
        binds.add("/sys");
        binds.add("/dev/urandom:/dev/random");
        binds.add("/proc/self/fd:/dev/fd");
        binds.add("/proc/self/fd/0:/dev/stdin");
        binds.add("/proc/self/fd/1:/dev/stdout");
        binds.add("/proc/self/fd/2:/dev/stderr");
        binds.add(shmDir().getAbsolutePath() + ":/dev/shm");
        return binds;
    }

    private Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        // Бинарники Termux собраны с RUNPATH на префикс Termux, которого у нас нет.
        env.put("LD_LIBRARY_PATH", new File(nativeDir, "lib").getAbsolutePath());
        env.put("PROOT_LOADER", new File(nativeDir, "libexec/proot/loader").getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmpDir().getAbsolutePath());
        env.put("HOME", tmpDir().getAbsolutePath());
        env.put("TMPDIR", tmpDir().getAbsolutePath());
        return Collections.unmodifiableMap(env);
    }

    private void prepareRuntimeDirs() {
        tmpDir().mkdirs();
        shmDir().mkdirs();
    }

    private File tmpDir() {
        return new File(rootfs.getParentFile(), "tmp");
    }

    private File shmDir() {
        return new File(tmpDir(), "shm");
    }
}
