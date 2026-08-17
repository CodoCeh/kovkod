package ru.codoceh.stol.apps;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ru.codoceh.stol.runtime.NativeAssets;
import ru.codoceh.stol.runtime.ProotSession;

/**
 * Ставит программы из списка (R04, R12i, R13i). Список — данные, движок — код: видов
 * установки четыре, и каждый кончается одним из трёх состояний {@link AppResult.State}.
 *
 * <p>Состояние «источник ответил» здесь невозможно устройством, а не уговором: после
 * команды установки идёт вторая команда — проверка на столе, и пока она не вернула ноль,
 * программа считается неустановленной. Ответ сервера доказательством не служит.
 *
 * <p>Отказ одной программы не отменяет остальные и не ломает стол: сборка идёт дальше,
 * неудачная помечается причиной, и её установку можно повторить отдельно (R04.3).
 */
public final class AppInstaller {

    /**
     * Ключи запуска, без которых окна не появляются вовсе. Проверены на эталонном столе
     * владельца и добавляются всегда — файл списка может их забыть, а стол без них
     * выглядит как сломанный.
     */
    static final List<String> REQUIRED_FLAGS = Collections.unmodifiableList(Arrays.asList(
            "--no-sandbox",
            "--disable-gpu",
            "--disable-software-rasterizer",
            "--disable-dev-shm-usage"));

    /** Куда падают загруженные файлы: внутрь стола, иначе установка их не увидит. */
    private static final String DOWNLOADS = "/tmp/stol";
    private static final String MENU = "/usr/share/applications";
    private static final String BROWSER = "chromium";

    /**
     * Запуск команды внутри стола. Шов ради проверки без устройства: в приложении за ним
     * стоит {@link ProotSession}, в тестах — запись вызова.
     */
    interface Commands {
        int run(String command);
    }

    /** Есть ли интернет. Отдельный шов, чтобы «нет сети» проверялось без самолётного режима. */
    interface Connectivity {
        boolean isOnline();
    }

    private final List<AppSpec> apps;
    private final File downloads;
    private final Commands commands;
    private final Connectivity connectivity;

    /** Рабочий установщик приложения: список из APK, команды — внутрь собранного стола. */
    public static AppInstaller forApp(Context context) throws IOException {
        File rootfs = NativeAssets.rootfsDir(context);
        ProotSession session = new ProotSession(NativeAssets.nativeDir(context), rootfs);
        List<AppSpec> apps;
        try (InputStream json = context.getAssets().open(AppsCatalog.ASSET)) {
            apps = AppsCatalog.read(json);
        }
        return new AppInstaller(apps, rootfs,
                command -> session.exec(command, false), deviceNetwork(context));
    }

    AppInstaller(List<AppSpec> apps, File rootfs, Commands commands, Connectivity connectivity) {
        this.apps = Collections.unmodifiableList(new ArrayList<>(apps));
        this.downloads = new File(rootfs, "tmp/stol");
        this.commands = commands;
        this.connectivity = connectivity;
    }

    /** Что вообще ставится — тем же порядком, что в файле списка. */
    public List<AppSpec> apps() {
        return apps;
    }

    /**
     * Ставит все программы подряд. Идёт минутами — вызывайте с фонового потока.
     *
     * @return по одному итогу на каждую программу, тем же порядком
     */
    public List<AppResult> installAll(AppsProgress progress) {
        List<AppResult> results = new ArrayList<>();
        int number = 0;
        for (AppSpec app : apps) {
            progress.onApp(app, ++number, apps.size());
            results.add(install(app, progress));
        }
        return Collections.unmodifiableList(results);
    }

    /**
     * Ставит одну программу — им же повторяется установка после отказа. Наружу не бросает
     * ничего: любой отказ возвращается итогом, иначе одна программа уронила бы сборку
     * стола. {@link AppsProgress#onApp} отсюда не идёт: кто ставит одну, тот знает какую.
     */
    public AppResult install(AppSpec app, AppsProgress progress) {
        if (app.kind().needsNetwork() && !connectivity.isOnline()) {
            return report(progress, AppResult.skipped(app));
        }
        File file = null;
        try {
            if (app.kind().needsDownload()) {
                file = download(app, progress);
            }
            run(installCommand(app), AppInstallException.Reason.INSTALL_REFUSED, "установка");
            run(checkCommand(app), AppInstallException.Reason.NOT_INSTALLED, "проверка");
            return report(progress, AppResult.installed(app));
        } catch (AppInstallException failed) {
            return report(progress, AppResult.failed(app, failed));
        } catch (RuntimeException broke) {
            // Что бы ни сломалось на одной программе, остальные ставятся дальше.
            return report(progress, AppResult.failed(app, new AppInstallException(
                    AppInstallException.Reason.INSTALL_REFUSED, String.valueOf(broke), broke)));
        } finally {
            if (file != null) {
                file.delete();
            }
        }
    }

    private AppResult report(AppsProgress progress, AppResult result) {
        progress.onResult(result);
        return result;
    }

    private File download(AppSpec app, AppsProgress progress) throws AppInstallException {
        if (!downloads.isDirectory() && !downloads.mkdirs()) {
            throw new AppInstallException(AppInstallException.Reason.INSTALL_REFUSED,
                    "не создать каталог загрузок " + downloads);
        }
        File file = new File(downloads, fileName(app));
        // Остаток прошлой попытки: докачивать чужой кусок нельзя, качаем заново.
        file.delete();
        try {
            Downloads.fetch(app.url(), file, progress::onDownload);
        } catch (AppInstallException | RuntimeException failed) {
            // Недокачанный кусок в столе не оставляем: место он занимает, а поставить
            // из него нечего.
            file.delete();
            throw failed;
        }
        return file;
    }

    private void run(String command, AppInstallException.Reason reason, String what)
            throws AppInstallException {
        int code = commands.run(command);
        if (code != 0) {
            throw new AppInstallException(reason, what + " вернула " + code);
        }
    }

    // ---------- что именно делается внутри стола ----------

    String installCommand(AppSpec app) {
        String file = DOWNLOADS + "/" + fileName(app);
        switch (app.kind()) {
            case DEB:
                return "set -e\n"
                        + "dpkg -i " + quote(file) + " || "
                        + "DEBIAN_FRONTEND=noninteractive apt-get -y --fix-broken install\n";
            case APPIMAGE:
                return "set -e\n"
                        + "chmod +x " + quote(file) + "\n"
                        + "rm -rf " + quote(unpackDir(app)) + "\n"
                        + "mkdir -p " + quote(unpackDir(app)) + "\n"
                        + "cd " + quote(unpackDir(app)) + "\n"
                        + quote(file) + " --appimage-extract >/dev/null\n"
                        + moveInto(app, quote(unpackDir(app) + "/squashfs-root"))
                        + desktopEntry(app, launcherLine(app), "Development;")
                        + "\n";
            case ARCHIVE:
                return "set -e\n"
                        + "rm -rf " + quote(unpackDir(app)) + "\n"
                        + "mkdir -p " + quote(unpackDir(app)) + "\n"
                        + "tar -xf " + quote(file) + " -C " + quote(unpackDir(app)) + "\n"
                        // Архив производителя обычно завёрнут в одну папку, но обещать это
                        // он нам не обязан: одна папка — берём её, иначе берём всё разом.
                        + "inner=" + quote(unpackDir(app)) + "\n"
                        + "if [ \"$(ls -1 " + quote(unpackDir(app)) + " | wc -l)\" -eq 1 ]; then\n"
                        + "  only=" + quote(unpackDir(app)) + "/\"$(ls -1 "
                        + quote(unpackDir(app)) + ")\"\n"
                        + "  if [ -d \"$only\" ]; then inner=\"$only\"; fi\n"
                        + "fi\n"
                        + moveInto(app, "\"$inner\"")
                        + desktopEntry(app, launcherLine(app), "Development;")
                        + "\n";
            case WEBAPP:
                return "set -e\n"
                        + "mkdir -p " + quote(app.profile()) + "\n"
                        + desktopEntry(app, BROWSER
                                + " --app=" + launched(app.url())
                                + " --user-data-dir=" + launched(app.profile())
                                + " " + flags(app),
                        "Network;")
                        + "\n";
            default:
                return "set -e\n"
                        // Списки пакетов могут быть пустыми на свежем столе; если обновить
                        // их не вышло, установка всё равно попробует и объяснится сама.
                        + "DEBIAN_FRONTEND=noninteractive apt-get -y update || true\n"
                        + "DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends"
                        + " install " + quote(app.packageName()) + "\n";
        }
    }

    /**
     * Проверка после установки: программа либо есть на столе, либо не установлена. Ровно
     * это отличает установку от ответа сервера.
     */
    String checkCommand(AppSpec app) {
        switch (app.kind()) {
            case DEB:
                return "set -e\n"
                        + "name=\"$(dpkg-deb -f " + quote(DOWNLOADS + "/" + fileName(app))
                        + " Package)\"\n"
                        + "test -n \"$name\"\n"
                        + installedPackage("\"$name\"")
                        + declaredLauncher(app);
            case APPIMAGE:
                return "set -e\n"
                        + "test -x " + quote(appRun(app)) + "\n"
                        + launcherWithFlags(app)
                        // Без своего каталога AppRun ищет программу по первому аргументу,
                        // а первым идёт ключ запуска — и программа не находится вовсе.
                        + "grep -q -- 'APPDIR=' " + quote(MENU + "/" + launcherName(app)) + "\n";
            case ARCHIVE:
                return "set -e\n"
                        + "test -x " + quote(program(app)) + "\n"
                        + launcherWithFlags(app);
            case WEBAPP:
                return "set -e\n"
                        + "command -v " + BROWSER + " >/dev/null\n"
                        + "test -d " + quote(app.profile()) + "\n"
                        + launcherWithFlags(app);
            default:
                return "set -e\n"
                        + installedPackage(quote(app.packageName()))
                        + declaredLauncher(app);
        }
    }

    /** Пакет считается стоящим только по слову хранителя пакетов, а не по коду возврата. */
    private static String installedPackage(String name) {
        return "dpkg-query -W -f='${Status}' " + name
                + " 2>/dev/null | grep -q '^install ok installed$'\n";
    }

    /** Ярлык проверяем только там, где он назван в списке: выдуманного имени быть не должно. */
    private static String declaredLauncher(AppSpec app) {
        return app.desktop().isEmpty() ? "" : "test -f " + quote(MENU + "/" + app.desktop()) + "\n";
    }

    /** Ярлык наш: заодно убеждаемся, что обязательные ключи запуска в нём остались. */
    private static String launcherWithFlags(AppSpec app) {
        String file = MENU + "/" + launcherName(app);
        StringBuilder check = new StringBuilder("test -f " + quote(file) + "\n");
        for (String flag : REQUIRED_FLAGS) {
            check.append("grep -q -- ").append(quote(flag)).append(' ')
                    .append(quote(file)).append('\n');
        }
        return check.toString();
    }

    private static String desktopEntry(AppSpec app, String exec, String categories) {
        String file = MENU + "/" + launcherName(app);
        return "mkdir -p " + quote(MENU) + "\n"
                + "cat > " + quote(file) + " <<'STOL-DESKTOP-ENTRY'\n"
                + "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Version=1.0\n"
                + "Name=" + app.name() + "\n"
                + "Exec=" + exec + "\n"
                + "Icon=" + app.icon() + "\n"
                + "Terminal=false\n"
                + "Categories=" + categories + "\n"
                + "STOL-DESKTOP-ENTRY\n"
                + "chmod 0644 " + quote(file);
    }

    /** Куда распаковывается скачанное до переезда на своё место. */
    private static String unpackDir(AppSpec app) {
        return DOWNLOADS + "/" + app.id() + ".d";
    }

    /** Переезд распакованного на своё место, поверх прошлой установки. */
    private static String moveInto(AppSpec app, String what) {
        return "rm -rf " + quote(app.target()) + "\n"
                + "mkdir -p \"$(dirname " + quote(app.target()) + ")\"\n"
                + "mv " + what + " " + quote(app.target()) + "\n"
                + "rm -rf " + quote(unpackDir(app)) + "\n";
    }

    private static String appRun(AppSpec app) {
        return app.target() + "/AppRun";
    }

    /** Что запускается у разложенной архивом программы: своё имя или имя программы. */
    private static String program(AppSpec app) {
        String named = app.exec();
        if (named.isEmpty()) {
            return app.target() + "/" + app.id();
        }
        return named.startsWith("/") ? named : app.target() + "/" + named;
    }

    /**
     * Строка запуска в ярлыке. У AppImage перед ней обязателен собственный каталог:
     * распакованный AppRun вычисляет его сам, обходя дерево вверх и проверяя путь по
     * первому аргументу, — а первым у нас идёт ключ запуска, и вычисление сбивается на
     * пустоту. На эталонном столе владельца этот каталог в ярлыке проставлен.
     */
    private static String launcherLine(AppSpec app) {
        if (app.kind() == AppSpec.Kind.APPIMAGE) {
            return "env APPDIR=" + launched(app.target()) + " " + launched(appRun(app))
                    + " " + flags(app);
        }
        return launched(program(app)) + " " + flags(app);
    }

    /** Имя файла ярлыка для тех видов, где ярлык пишем мы. */
    private static String launcherName(AppSpec app) {
        return app.desktop().isEmpty() ? app.id() + ".desktop" : app.desktop();
    }

    /** Обязательные ключи впереди, названные в списке — следом, без повторов. */
    static String flags(AppSpec app) {
        StringBuilder line = new StringBuilder();
        for (String flag : REQUIRED_FLAGS) {
            line.append(line.length() == 0 ? "" : " ").append(flag);
        }
        for (String flag : app.execFlags().trim().split("\\s+")) {
            if (!flag.isEmpty() && !REQUIRED_FLAGS.contains(flag)) {
                line.append(' ').append(flag);
            }
        }
        return line.toString();
    }

    private static String fileName(AppSpec app) {
        switch (app.kind()) {
            case DEB:
                return app.id() + ".deb";
            case ARCHIVE:
                return app.id() + ".tar.gz";
            default:
                return app.id() + ".AppImage";
        }
    }

    /** Одинарные кавычки: имена и адреса приходят из файла списка, а не из нашего кода. */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Кавычки для строки запуска в ярлыке. Там свой разбор, а не оболочка: двойные кавычки
     * описаны его правилами, одинарные — нет.
     */
    private static String launched(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static Connectivity deviceNetwork(Context context) {
        ConnectivityManager networks =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return () -> {
            try {
                Network active = networks.getActiveNetwork();
                NetworkCapabilities able =
                        active == null ? null : networks.getNetworkCapabilities(active);
                return able != null
                        && able.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } catch (RuntimeException unknown) {
                // Спросить не вышло — считаем, что сеть есть: пусть попытка сама покажет,
                // иначе программы окажутся отложены на ровном месте.
                return true;
            }
        };
    }
}
