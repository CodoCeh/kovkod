package ru.codoceh.stol.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    /**
     * Подменный {@code /proc/version}. Android монтирует {@code /proc} со скрытием чужих
     * процессов, и часть файлов ядра оттуда не читается вовсе: {@code /proc/version}
     * отвечает «отказано в доступе». LibreOffice считает это отсутствием {@code /proc},
     * печатает «ERROR: /proc not mounted» и выходит с кодом 1 — на столе это выглядит
     * мёртвым ярлыком. Файл кладётся рядом с образом и подставляется привязкой, как это
     * делает proot-distro. Содержимое — заглушка: настоящую строку ядра из-под приложения
     * всё равно не прочитать, а читающим программам важно, что файл есть и читается.
     */
    private static final String PROC_VERSION =
            "Linux version 6.6.0-stol (stol@codoceh) (gcc (Debian 14.2.0) 14.2.0,"
                    + " GNU ld (GNU Binutils for Debian) 2.44) #1 SMP PREEMPT_DYNAMIC\n";

    private final File nativeDir;
    private final File rootfs;
    private final ProcessLauncher launcher;

    public ProotSession(File nativeDir, File rootfs) {
        this(nativeDir, rootfs, new SystemProcessLauncher());
    }

    public ProotSession(File nativeDir, File rootfs, ProcessLauncher launcher) {
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
     * @param background не ждать завершения; долгоживущему столу этого мало — см.
     *                   {@link #open(String)}
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

    /**
     * Открывает живую сессию: команда исполняется внутри образа, а сессия остаётся во
     * владении приложения, пока её не закроют.
     *
     * <p>proot запущен с {@code --kill-on-exit} и уносит с собой всё, что породил, как
     * только выходит корневой процесс сессии. Поэтому долгоживущему столу нужна не
     * «команда в фоне», а сессия, которую держит само приложение: закрыли — на устройстве
     * не осталось ни одного процесса образа.
     *
     * @return живая сессия, либо {@code null} если образа нет или процесс не запустился
     */
    public ProcessLauncher.Handle open(String command) {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("пустая команда");
        }
        if (!isRootfsReady()) {
            return null;
        }
        try {
            prepareRuntime();
            return launcher.open(argv(command), env());
        } catch (IOException failed) {
            return null;
        }
    }

    /**
     * Путь внутри образа, каким его видит Android. Нужен, чтобы приложение могло прочесть
     * то, что программы образа положили на диск; запускать отсюда ничего нельзя.
     */
    public File inside(String guestPath) {
        String relative = guestPath.startsWith("/") ? guestPath.substring(1) : guestPath;
        return new File(rootfs, relative);
    }

    private List<String> argv(String command) {
        List<String> argv = new ArrayList<>();
        File linker = new File("/system/bin/linker64");
        if (linker.isFile() && linker.canExecute()) {
            argv.add(linker.getAbsolutePath());
        }
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
        binds.add(procVersion().getAbsolutePath() + ":/proc/version");

        File download = new File("/storage/emulated/0/Download");
        if (!download.isDirectory()) {
            download = new File("/sdcard/Download");
        }
        if (download.isDirectory()) {
            binds.add(download.getAbsolutePath() + ":/root/Загрузки");
        }
        File documents = new File("/storage/emulated/0/Documents");
        if (!documents.isDirectory()) {
            documents = new File("/sdcard/Documents");
        }
        if (documents.isDirectory()) {
            binds.add(documents.getAbsolutePath() + ":/root/Документы");
        }
        return binds;
    }

    private Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        // Бинарники Termux собраны с RUNPATH на префикс Termux, которого у нас нет: сами
        // библиотеки лежат рядом с ними, версионные имена — в каталоге ссылок.
        env.put("LD_LIBRARY_PATH", sonameDir().getAbsolutePath()
                + File.pathSeparator + nativeDir.getAbsolutePath());
        // Загрузчик ищется рядом с proot, но полагаться на догадку нельзя: имя файла в
        // каталоге библиотек не то, которое зашито в сборку Termux.
        env.put("PROOT_LOADER", new File(nativeDir, LOADER).getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmpDir().getAbsolutePath());
        env.put("HOME", "/root");
        env.put("TMPDIR", tmpDir().getAbsolutePath());
        return Collections.unmodifiableMap(env);
    }

    /** Перед каждым запуском: после обновления APK каталог библиотек лежит по новому пути. */
    private void prepareRuntime() throws IOException {
        tmpDir().mkdirs();
        shmDir().mkdirs();
        File version = procVersion();
        File procDir = version.getParentFile();
        if (!procDir.isDirectory() && !procDir.mkdirs()) {
            throw new IOException("не создать каталог " + procDir);
        }
        Files.write(version.toPath(), PROC_VERSION.getBytes("UTF-8"));
        File links = sonameDir();
        if (!links.isDirectory() && !links.mkdirs()) {
            throw new IOException("не создать каталог " + links);
        }
        for (String[] alias : SONAME_LINKS) {
            File target = new File(nativeDir, alias[1]);
            if (!target.isFile()) {
                throw new IOException("нет библиотеки " + target);
            }
            Path link = new File(links, alias[0]).toPath();
            Files.deleteIfExists(link);
            Files.createSymbolicLink(link, target.toPath());
        }
        prepareDns();
        prepareLaunchers();
    }

    private void prepareLaunchers() {
        try {
            File binDir = new File(rootfs, "usr/local/bin");
            binDir.mkdirs();
            writeExecutable(new File(binDir, "anydesk"),
                    "#!/bin/sh\n"
                    + "if [ -f /usr/bin/anydesk ]; then\n"
                    + "    id kovkod >/dev/null 2>&1 || useradd -m -s /bin/bash kovkod\n"
                    + "    chmod 1777 /tmp\n"
                    + "    pgrep -f anydesk.--local-service >/dev/null || rm -f /dev/shm/ad_* /tmp/ad_*\n"
                    + "    pulse=$(ls /tmp/pulse-*/native 2>/dev/null | head -n 1)\n"
                    + "    exec su kovkod -c \"DISPLAY=${DISPLAY:-:1} PULSE_SERVER=unix:$pulse exec /usr/bin/anydesk\"\n"
                    + "else\n"
                    + "    exec python3 /opt/stol/store/codoceh-store.py --install anydesk --then \"$0\"\n"
                    + "fi\n");
            writeExecutable(new File(binDir, "claude-desktop"),
                    "#!/bin/sh\n"
                    + "if [ -f \"$HOME/.config/kovkod/claude-update-pending\" ]; then\n"
                    + "    /opt/stol/update-claude.sh apply\n"
                    + "fi\n"
                    + "flock /tmp/kovkod-claude-update.lock true\n"
                    + "if [ -x /usr/bin/claude-desktop ] && [ ! -L /usr/bin/claude-desktop ] && [ \"$(realpath /usr/bin/claude-desktop 2>/dev/null)\" != \"$(realpath \"$0\" 2>/dev/null)\" ]; then\n"
                    + "    exec /usr/bin/claude-desktop --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Claude --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "elif [ -x /usr/lib/claude-desktop/claude-desktop ]; then\n"
                    + "    exec /usr/lib/claude-desktop/claude-desktop --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Claude --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "elif [ -x /opt/claude-desktop/claude-desktop ]; then\n"
                    + "    exec /opt/claude-desktop/claude-desktop --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Claude --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "else\n"
                    + "    exec python3 /opt/stol/store/codoceh-store.py --install claude --then \"$0\"\n"
                    + "fi\n");
            writeExecutable(new File(binDir, "code"),
                    "#!/bin/sh\n"
                    + "if [ -x /usr/share/code/code ]; then\n"
                    + "    exec /usr/share/code/code --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Code --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "elif [ -x /usr/bin/code ] && [ ! -L /usr/bin/code ] && [ \"$(realpath /usr/bin/code 2>/dev/null)\" != \"$(realpath \"$0\" 2>/dev/null)\" ]; then\n"
                    + "    exec /usr/bin/code --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Code --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "else\n"
                    + "    exec python3 /opt/stol/store/codoceh-store.py --install code --then \"$0\"\n"
                    + "fi\n");
            writeExecutable(new File(binDir, "antigravity"),
                    "#!/bin/sh\n"
                    + "if [ -x /opt/Antigravity-arm64/antigravity ]; then\n"
                    + "    exec /opt/Antigravity-arm64/antigravity --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Antigravity --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "elif [ -x \"/opt/Antigravity IDE/antigravity-ide\" ]; then\n"
                    + "    exec \"/opt/Antigravity IDE/antigravity-ide\" --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Antigravity --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "elif [ -x /opt/Antigravity-arm64/AppRun ]; then\n"
                    + "    exec /opt/Antigravity-arm64/AppRun --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Antigravity --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "else\n"
                    + "    exec python3 /opt/stol/store/codoceh-store.py --install antigravity2 --then \"$0\"\n"
                    + "fi\n");
            writeExecutable(new File(binDir, "antigravity-ide"),
                    "#!/bin/sh\nexec /usr/local/bin/antigravity \"$@\"\n");
            writeExecutable(new File(binDir, "obsidian"),
                    "#!/bin/sh\n"
                    + "if [ -x /opt/obsidian-app/AppRun ]; then\n"
                    + "    exec /opt/obsidian-app/AppRun --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Obsidian --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage --lang=ru \"$@\"\n"
                    + "elif [ -x /opt/obsidian-app/obsidian ]; then\n"
                    + "    exec /opt/obsidian-app/obsidian --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/Obsidian --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage --lang=ru \"$@\"\n"
                    + "else\n"
                    + "    exec python3 /opt/stol/store/codoceh-store.py --install obsidian --then \"$0\"\n"
                    + "fi\n");
            writeExecutable(new File(binDir, "zcode"),
                    "#!/bin/sh\n"
                    + "if [ -x /opt/zcode-app/AppRun ]; then\n"
                    + "    export APPDIR=/opt/zcode-app\n"
                    + "    exec /opt/zcode-app/AppRun --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/ZCode --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "elif [ -x /opt/zcode-app/zcode ]; then\n"
                    + "    export APPDIR=/opt/zcode-app\n"
                    + "    exec /opt/zcode-app/zcode --no-sandbox --xdg-portal-required-version=99 --user-data-dir=/root/.config/ZCode --disable-gpu --disable-software-rasterizer --disable-dev-shm-usage \"$@\"\n"
                    + "else\n"
                    + "    exec python3 /opt/stol/store/codoceh-store.py --install zcode --then \"$0\"\n"
                    + "fi\n");
            boolean firefox = new File(rootfs, "usr/bin/firefox-esr").exists();
            String browser = firefox ? "/usr/bin/firefox-esr" : "/usr/local/bin/chromium";
            String browserEntry = firefox ? "firefox-esr.desktop" : "chromium.desktop";
            if (!firefox) {
                writeExecutable(new File(binDir, "chromium"),
                        "#!/bin/sh\nexec /opt/stol/remove-chromium.sh\n");
                File chromiumEntry = new File(rootfs, "root/.local/share/applications/chromium.desktop");
                File systemEntry = new File(rootfs, "usr/share/applications/chromium.desktop");
                if (systemEntry.exists()) {
                    chromiumEntry.getParentFile().mkdirs();
                    String entry = new String(Files.readAllBytes(systemEntry.toPath()), StandardCharsets.UTF_8)
                            .replaceAll("(?m)^Exec=\\S*chromium", "Exec=/usr/local/bin/chromium");
                    Files.write(chromiumEntry.toPath(), entry.getBytes(StandardCharsets.UTF_8));
                }
            }
            for (String name : new String[] {"x-www-browser", "gnome-www-browser", "sensible-browser"}) {
                writeExecutable(new File(binDir, name), "#!/bin/sh\nexec " + browser + " \"$@\"\n");
            }

            File configDir = new File(rootfs, "root/.config");
            configDir.mkdirs();
            File mimeFile = new File(configDir, "mimeapps.list");
            String mimeContent = "[Default Applications]\n"
                    + "text/html=" + browserEntry + "\n"
                    + "x-scheme-handler/http=" + browserEntry + "\n"
                    + "x-scheme-handler/https=" + browserEntry + "\n"
                    + "x-scheme-handler/about=" + browserEntry + "\n"
                    + "x-scheme-handler/unknown=" + browserEntry + "\n\n"
                    + "[Added Associations]\n"
                    + "text/html=" + browserEntry + ";\n"
                    + "x-scheme-handler/http=" + browserEntry + ";\n"
                    + "x-scheme-handler/https=" + browserEntry + ";\n";
            Files.write(mimeFile.toPath(), mimeContent.getBytes(StandardCharsets.UTF_8));

            File desktopDir = new File(rootfs, "root/Desktop");
            if (desktopDir.isDirectory()) {
                updateDesktopFile(new File(desktopDir, "claude.desktop"), "claude-desktop %U", "claude");
                updateDesktopFile(new File(desktopDir, "code.desktop"), "code %F", "code");
                updateDesktopFile(new File(desktopDir, "antigravity.desktop"), "antigravity-ide %U", "antigravity-ide");
                updateDesktopFile(new File(desktopDir, "antigravity2.desktop"), "antigravity %U", "antigravity");
                updateDesktopFile(new File(desktopDir, "obsidian.desktop"), "obsidian %U", "obsidian");
                updateDesktopFile(new File(desktopDir, "zcode.desktop"), "zcode %U", "zcode");
                updateDesktopFile(new File(desktopDir, "codoceh-store.desktop"), "python3 /opt/stol/store/codoceh-store.py", "codoceh-store");
            }
        } catch (Exception ignored) {
        }
    }

    private static void writeExecutable(File file, String content) throws IOException {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        file.setExecutable(true, false);
        file.setReadable(true, false);
    }

    private static void updateDesktopFile(File file, String execCmd, String iconName) {
        if (!file.isFile()) return;
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!content.contains("[Desktop Entry]")) {
                String name = file.getName().replace(".desktop", "");
                content = "[Desktop Entry]\nType=Application\nName="
                        + name.substring(0, 1).toUpperCase() + name.substring(1)
                        + "\nExec=" + execCmd + "\nTerminal=false\n";
            }
            String updated = content.replaceAll("(?m)^Exec=.*$", "Exec=" + execCmd);
            if (iconName != null) {
                if (updated.contains("Icon=")) {
                    updated = updated.replaceAll("(?m)^Icon=.*$", "Icon=" + iconName);
                } else {
                    updated = updated + "\nIcon=" + iconName + "\n";
                }
            }
            Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            file.setExecutable(true, false);
            file.setReadable(true, false);
        } catch (Exception ignored) {
        }
    }

    private void prepareDns() {
        try {
            File resolv = new File(rootfs, "etc/resolv.conf");
            if (!resolv.getParentFile().isDirectory()) {
                return;
            }
            List<String> servers = detectDnsServers();
            StringBuilder sb = new StringBuilder();
            for (String s : servers) {
                sb.append("nameserver ").append(s).append("\n");
            }
            Files.write(resolv.toPath(), sb.toString().getBytes("UTF-8"));
            resolv.setReadable(true, false);
        } catch (Exception ignored) {
        }
    }

    private static List<String> detectDnsServers() {
        List<String> servers = new ArrayList<>();
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class);
            for (int i = 1; i <= 4; i++) {
                String val = (String) get.invoke(null, "net.dns" + i);
                if (val != null && !val.trim().isEmpty() && !servers.contains(val.trim())) {
                    servers.add(val.trim());
                }
            }
        } catch (Exception ignored) {
        }
        if (servers.isEmpty()) {
            servers.add("1.1.1.1");
            servers.add("8.8.8.8");
        }
        return servers;
    }

    private File tmpDir() {
        return new File(rootfs.getParentFile(), "tmp");
    }

    private File shmDir() {
        return new File(tmpDir(), "shm");
    }

    private File sonameDir() {
        return new File(rootfs.getParentFile(), "soname");
    }

    /** Подменные файлы {@code /proc} лежат рядом с образом, а не внутри него. */
    private File procVersion() {
        return new File(new File(rootfs.getParentFile(), "procfake"), "version");
    }
}
