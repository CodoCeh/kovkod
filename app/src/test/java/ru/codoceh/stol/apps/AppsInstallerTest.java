package ru.codoceh.stol.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Установщик программ (R04, R12i, R13i). Настоящего стола в проверке нет: команды идут в
 * запись через тот же шов, что у соседей, а сайты производителей заменяет сервер на петле.
 *
 * <p>Главное, что здесь проверяется, — что «источник ответил» не считается установкой.
 */
public class AppsInstallerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File rootfs;
    private FakeSite site;
    private Shell shell;
    private Trace trace;
    private boolean online;

    @Before
    public void setUp() throws IOException {
        rootfs = folder.newFolder("rootfs");
        site = new FakeSite();
        shell = new Shell();
        trace = new Trace();
        online = true;
    }

    @After
    public void tearDown() throws IOException {
        site.close();
    }

    // ---------- четыре вида установки ----------

    @Test
    public void packageFromTheVendorSiteIsDownloadedIntoTheDeskAndInstalled() {
        site.file("/code.deb", 700_000);
        AppSpec app = one(deb("vscode", site.address("/code.deb")));

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.INSTALLED, result.state());
        assertTrue("пакет не ставился: " + shell.commands,
                shell.ran("dpkg -i '/tmp/stol/vscode.deb'"));
        assertTrue("установка не проверялась", shell.ran("dpkg-query"));
        assertEquals("установка шла не по скачанному файлу",
                "vscode.deb=700000", shell.filesAt("dpkg -i"));
        assertTrue("качали, а ход загрузки не показывали", trace.downloaded >= 700_000);
        assertFalse("скачанный файл остался лежать в столе", downloaded("vscode.deb").exists());
    }

    @Test
    public void unpackedProgramGetsTheFlagsWithoutWhichWindowsDoNotAppear() {
        site.file("/zcode.AppImage", 4096);
        AppSpec app = one("{\"id\":\"zcode\",\"name\":\"ZCode\",\"kind\":\"appimage-extract\","
                + "\"url\":\"" + site.address("/zcode.AppImage") + "\",\"target\":\"/opt/zcode-app\"}");

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.INSTALLED, result.state());
        assertTrue("образ программы не распаковывался", shell.ran("--appimage-extract"));
        assertTrue("запускается образ целиком вместо распакованного",
                shell.line("Exec=").contains("\"/opt/zcode-app/AppRun\""));
        for (String flag : AppInstaller.REQUIRED_FLAGS) {
            assertTrue("в ярлыке нет обязательного ключа " + flag,
                    shell.line("Exec=").contains(flag));
            assertTrue("наличие ключа " + flag + " в ярлыке не проверяется",
                    shell.ran("grep -q -- '" + flag + "'"));
        }
    }

    /**
     * Пятый вид: под arm64 Antigravity раздаётся только архивом — ни пакета, ни образа, ни
     * репозитория у производителя под эту архитектуру нет.
     */
    @Test
    public void archiveFromTheVendorSiteIsUnpackedAndGetsALauncher() {
        site.file("/antigravity.tar.gz", 4096);
        AppSpec app = one("{\"id\":\"antigravity\",\"name\":\"Antigravity\",\"kind\":\"archive\","
                + "\"url\":\"" + site.address("/antigravity.tar.gz") + "\","
                + "\"target\":\"/opt/antigravity\",\"exec\":\"antigravity\"}");

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.INSTALLED, result.state());
        assertTrue("архив не распаковывался: " + shell.commands,
                shell.ran("tar -xf '/tmp/stol/antigravity.tar.gz'"));
        assertTrue("распакованное не переехало на своё место",
                shell.ran("mv \"$inner\" '/opt/antigravity'"));
        assertTrue("временный каталог остался в столе", shell.ran("rm -rf '/tmp/stol/antigravity.d'"));
        assertEquals("Exec=\"/opt/antigravity/antigravity\" "
                        + String.join(" ", AppInstaller.REQUIRED_FLAGS),
                shell.line("Exec="));
        assertTrue("запуск программы не проверяется",
                shell.ran("test -x '/opt/antigravity/antigravity'"));
    }

    @Test
    public void archiveWithoutANamedProgramLooksForItByTheProgramIdentifier() {
        site.file("/claude.tar.gz", 4096);
        AppSpec app = one("{\"id\":\"claude\",\"name\":\"Claude\",\"kind\":\"archive\","
                + "\"url\":\"" + site.address("/claude.tar.gz") + "\","
                + "\"target\":\"/opt/claude-app\"}");

        assertEquals(AppResult.State.INSTALLED, installer(app).install(app, trace).state());
        assertTrue(shell.ran("test -x '/opt/claude-app/claude'"));
    }

    /**
     * Разложенный образ вычисляет свой каталог сам — обходом дерева вверх по первому
     * аргументу. Аргументом идёт ключ запуска, каталог схлопывается в пустоту, и программа
     * не находится. На эталонном столе владельца каталог в ярлыке проставлен; у нас тоже.
     */
    @Test
    public void launcherOfAnUnpackedImageCarriesItsOwnDirectory() {
        site.file("/zcode.AppImage", 4096);
        AppSpec app = one("{\"id\":\"zcode\",\"name\":\"ZCode\",\"kind\":\"appimage-extract\","
                + "\"url\":\"" + site.address("/zcode.AppImage") + "\",\"target\":\"/opt/zcode-app\"}");

        installer(app).install(app, trace);

        assertEquals("Exec=env APPDIR=\"/opt/zcode-app\" \"/opt/zcode-app/AppRun\" "
                        + String.join(" ", AppInstaller.REQUIRED_FLAGS),
                shell.line("Exec="));
        assertTrue("наличие своего каталога в ярлыке не проверяется",
                shell.ran("grep -q -- 'APPDIR='"));
    }

    @Test
    public void flagsFromTheFileAreKeptAndNotDoubled() {
        site.file("/zcode.AppImage", 4096);
        AppSpec app = one("{\"id\":\"zcode\",\"name\":\"ZCode\",\"kind\":\"appimage-extract\","
                + "\"url\":\"" + site.address("/zcode.AppImage") + "\",\"target\":\"/opt/zcode-app\","
                + "\"exec_flags\":\"--no-sandbox --password-store=basic\"}");

        installer(app).install(app, trace);

        String exec = shell.line("Exec=");
        assertTrue("ключ из списка потерялся: " + exec, exec.contains("--password-store=basic"));
        assertEquals("обязательный ключ записан дважды: " + exec,
                exec.indexOf("--no-sandbox"), exec.lastIndexOf("--no-sandbox"));
    }

    @Test
    public void windowOverTheBrowserNeedsNoVendorSiteAtAll() {
        online = false;
        AppSpec app = one("{\"id\":\"gemini\",\"name\":\"Gemini\",\"kind\":\"webapp\","
                + "\"url\":\"https://gemini.google.com/app\","
                + "\"profile\":\"/root/.config/gemini-app\"}");

        AppResult result = installer(app).install(app, trace);

        assertEquals("окно поверх браузера не должно ждать интернета",
                AppResult.State.INSTALLED, result.state());
        assertTrue("окно открывается не на том адресе: " + shell.line("Exec="),
                shell.ran("--app=\"https://gemini.google.com/app\""));
        assertTrue("браузер в образе не проверяется", shell.ran("command -v chromium"));
        for (String flag : AppInstaller.REQUIRED_FLAGS) {
            assertTrue("окно останется без ключа запуска " + flag + ": " + shell.line("Exec="),
                    shell.line("Exec=").contains(flag));
        }
    }

    @Test
    public void packageFromTheSystemRepositoryIsInstalledAndChecked() {
        AppSpec app = one("{\"id\":\"mousepad\",\"name\":\"Блокнот\",\"kind\":\"apt\","
                + "\"package\":\"mousepad\"}");

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.INSTALLED, result.state());
        assertTrue(shell.ran("install 'mousepad'"));
        assertTrue("установка пакета не проверялась", shell.ran("dpkg-query -W"));
    }

    // ---------- «источник ответил» установкой не считается ----------

    @Test
    public void sourceAnsweredButProgramIsNotOnTheDeskCountsAsFailure() {
        site.file("/code.deb", 4096);
        shell.answer("dpkg-query", 1);
        AppSpec app = one(deb("vscode", site.address("/code.deb")));

        AppResult result = installer(app).install(app, trace);

        assertNotEquals("ответ сервера принят за установку",
                AppResult.State.INSTALLED, result.state());
        assertEquals(AppResult.State.FAILED, result.state());
        assertEquals(AppInstallException.Reason.NOT_INSTALLED, result.reason());
        assertFalse("итог без причины", result.detail().isEmpty());
    }

    @Test
    public void installCommandThatFailedIsNotCalledInstalled() {
        site.file("/code.deb", 4096);
        shell.answer("dpkg -i", 2);
        AppSpec app = one(deb("vscode", site.address("/code.deb")));

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.FAILED, result.state());
        assertEquals(AppInstallException.Reason.INSTALL_REFUSED, result.reason());
        assertFalse("после отказа установки проверка не нужна", shell.ran("dpkg-query"));
    }

    // ---------- сеть ----------

    @Test
    public void connectionTornInTheMiddleIsCalledTornAndNothingIsInstalled() {
        site.tornFile("/code.deb", 700_000, 30_000);
        AppSpec app = one(deb("vscode", site.address("/code.deb")));

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.FAILED, result.state());
        assertEquals(AppInstallException.Reason.BROKEN_LINK, result.reason());
        assertTrue("кусок файла пошёл в установку: " + shell.commands, shell.commands.isEmpty());
        assertFalse("недокачанный кусок остался в столе", downloaded("vscode.deb").exists());
    }

    @Test
    public void sourceThatRefusesOrIsSilentIsCalledSoAndDoesNotBreakTheDesk() {
        site.refusal("/gone.deb", 404);
        AppSpec refused = one(deb("vscode", site.address("/gone.deb")));
        AppResult first = installer(refused).install(refused, trace);
        assertEquals(AppResult.State.FAILED, first.state());
        assertEquals(AppInstallException.Reason.NO_SOURCE, first.reason());

        AppSpec silent = one(deb("vscode", "http://127.0.0.1:" + deadPort() + "/code.deb"));
        AppResult second = installer(silent).install(silent, trace);
        assertEquals(AppResult.State.FAILED, second.state());
        assertEquals(AppInstallException.Reason.NO_SOURCE, second.reason());
        assertTrue("к столу лезли, хотя качать было нечего", shell.commands.isEmpty());
    }

    /** Официальные адреса водят через зеркала: без перенаправлений не скачать ничего. */
    @Test
    public void officialAddressThatLeadsThroughAMirrorIsFollowed() {
        site.redirect("/latest", "/code.deb");
        site.file("/code.deb", 4096);
        AppSpec app = one(deb("vscode", site.address("/latest")));

        assertEquals(AppResult.State.INSTALLED, installer(app).install(app, trace).state());
    }

    @Test
    public void addressThatLeadsInCirclesIsCalledUnavailable() {
        site.redirect("/latest", "/latest");
        AppSpec app = one(deb("vscode", site.address("/latest")));

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.FAILED, result.state());
        assertEquals(AppInstallException.Reason.NO_SOURCE, result.reason());
    }

    @Test
    public void withoutInternetProgramsFromVendorSitesAreSetAsideNotFailed() {
        online = false;
        List<AppSpec> apps = AppsCatalog.parse("["
                + deb("vscode", site.address("/code.deb")) + ","
                + "{\"id\":\"zcode\",\"name\":\"ZCode\",\"kind\":\"appimage-extract\","
                + "\"url\":\"" + site.address("/zcode.AppImage") + "\",\"target\":\"/opt/zcode-app\"},"
                + "{\"id\":\"mousepad\",\"name\":\"Блокнот\",\"kind\":\"apt\"},"
                + "{\"id\":\"antigravity\",\"name\":\"Antigravity\",\"kind\":\"archive\","
                + "\"url\":\"" + site.address("/antigravity.tar.gz") + "\","
                + "\"target\":\"/opt/antigravity\"},"
                + "{\"id\":\"gemini\",\"name\":\"Gemini\",\"kind\":\"webapp\","
                + "\"url\":\"https://gemini.google.com/app\"}]");

        List<AppResult> results = installer(apps).installAll(trace);

        assertEquals(AppResult.State.SKIPPED_OFFLINE, results.get(0).state());
        assertEquals(AppResult.State.SKIPPED_OFFLINE, results.get(1).state());
        assertEquals(AppResult.State.SKIPPED_OFFLINE, results.get(2).state());
        assertEquals("архив без интернета взять неоткуда",
                AppResult.State.SKIPPED_OFFLINE, results.get(3).state());
        assertEquals("окно поверх браузера ставится и без интернета",
                AppResult.State.INSTALLED, results.get(4).state());
        assertFalse("отложенную программу пытались ставить", shell.ran("dpkg"));
    }

    // ---------- отказ одной программы ----------

    @Test
    public void oneRefusedProgramDoesNotCancelTheOthers() {
        site.file("/code.deb", 4096);
        site.refusal("/claude.deb", 500);
        site.file("/zcode.AppImage", 4096);
        List<AppSpec> apps = AppsCatalog.parse("["
                + deb("vscode", site.address("/code.deb")) + ","
                + deb("claude", site.address("/claude.deb")) + ","
                + "{\"id\":\"zcode\",\"name\":\"ZCode\",\"kind\":\"appimage-extract\","
                + "\"url\":\"" + site.address("/zcode.AppImage") + "\",\"target\":\"/opt/zcode-app\"}]");

        List<AppResult> results = installer(apps).installAll(trace);

        assertEquals(3, results.size());
        assertEquals(AppResult.State.INSTALLED, results.get(0).state());
        assertEquals(AppResult.State.FAILED, results.get(1).state());
        assertEquals("после отказа соседа программа не поставилась",
                AppResult.State.INSTALLED, results.get(2).state());
        assertEquals("[ставим vscode 1 из 3, vscode: INSTALLED, ставим claude 2 из 3, "
                        + "claude: FAILED, ставим zcode 3 из 3, zcode: INSTALLED]",
                trace.steps.toString());
    }

    /** R04.3 и R02.3: повтор установки одной программы проходит отдельно от остальных. */
    @Test
    public void refusedProgramIsInstalledAgainOnItsOwn() {
        site.refusal("/code.deb", 503);
        AppSpec app = one(deb("vscode", site.address("/code.deb")));
        AppInstaller installer = installer(app);

        assertEquals(AppResult.State.FAILED, installer.install(app, trace).state());

        site.file("/code.deb", 4096);
        AppResult second = installer.install(app, trace);

        assertEquals(AppResult.State.INSTALLED, second.state());
        assertEquals("повтор прошёл, а итог остался прежним", null, second.reason());
    }

    @Test
    public void brokenDeskDoesNotThrowOutOfTheInstaller() {
        shell = new Shell() {
            @Override
            public int run(String command) {
                throw new IllegalStateException("стол не отвечает");
            }
        };
        AppSpec app = one("{\"id\":\"mousepad\",\"name\":\"Блокнот\",\"kind\":\"apt\"}");

        AppResult result = installer(app).install(app, trace);

        assertEquals(AppResult.State.FAILED, result.state());
        assertEquals(AppInstallException.Reason.INSTALL_REFUSED, result.reason());
    }

    // ---------- обвязка ----------

    private AppInstaller installer(AppSpec app) {
        List<AppSpec> apps = new ArrayList<>();
        apps.add(app);
        return installer(apps);
    }

    private AppInstaller installer(List<AppSpec> apps) {
        return new AppInstaller(apps, rootfs, shell, () -> online);
    }

    private static AppSpec one(String entry) {
        return AppsCatalog.parse("[" + entry + "]").get(0);
    }

    private static String deb(String id, String url) {
        return "{\"id\":\"" + id + "\",\"name\":\"Программа\",\"kind\":\"deb\","
                + "\"url\":\"" + url + "\",\"desktop\":\"" + id + ".desktop\"}";
    }

    private File downloaded(String name) {
        return new File(rootfs, "tmp/stol/" + name);
    }

    private static int deadPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Запись команд вместо настоящего стола: тот же приём, что у соседних задач. */
    private class Shell implements AppInstaller.Commands {

        final List<String> commands = new ArrayList<>();
        private final Map<String, Integer> answers = new LinkedHashMap<>();
        private final Map<String, String> filesSeen = new LinkedHashMap<>();

        void answer(String fragment, int code) {
            answers.put(fragment, code);
        }

        @Override
        public int run(String command) {
            commands.add(command);
            for (Map.Entry<String, Integer> answer : answers.entrySet()) {
                if (command.contains(answer.getKey())) {
                    filesSeen.put(answer.getKey(), files());
                    return answer.getValue();
                }
            }
            filesSeen.put(command, files());
            return 0;
        }

        boolean ran(String fragment) {
            for (String command : commands) {
                if (command.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        /** Строка команды, где встретилось начало — чтобы смотреть на ярлык целиком. */
        String line(String starts) {
            for (String command : commands) {
                for (String line : command.split("\n")) {
                    if (line.startsWith(starts)) {
                        return line;
                    }
                }
            }
            return "";
        }

        /** Что лежало в каталоге загрузок в момент команды: скачали ли на самом деле. */
        String filesAt(String fragment) {
            for (Map.Entry<String, String> seen : filesSeen.entrySet()) {
                if (seen.getKey().contains(fragment)) {
                    return seen.getValue();
                }
            }
            return "";
        }

        private String files() {
            File[] files = new File(rootfs, "tmp/stol").listFiles();
            StringBuilder listing = new StringBuilder();
            if (files != null) {
                for (File file : files) {
                    listing.append(listing.length() == 0 ? "" : ", ")
                            .append(file.getName()).append('=').append(file.length());
                }
            }
            return listing.toString();
        }
    }

    /** Ход работы наружу — как у мастера сборки: молчать нельзя. */
    private static final class Trace implements AppsProgress {

        final List<String> steps = new ArrayList<>();
        long downloaded;

        @Override
        public void onApp(AppSpec app, int number, int total) {
            steps.add("ставим " + app.id() + " " + number + " из " + total);
        }

        @Override
        public void onDownload(long doneBytes, long totalBytes) {
            downloaded = Math.max(downloaded, doneBytes);
        }

        @Override
        public void onResult(AppResult result) {
            steps.add(result.app().id() + ": " + result.state());
        }
    }
}
