package ru.codoceh.stol.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Шов 4: список программ — данные. Добавление программы должно быть строкой в файле, а не
 * правкой приложения (R04.4i), поэтому разбор проверяется отдельно от установки.
 */
public class AppsCatalogTest {

    private static final String SEVEN = "["
            + "{\"id\":\"vscode\",\"name\":\"Visual Studio Code\",\"kind\":\"deb\","
            + "\"url\":\"https://update.code.visualstudio.com/latest/linux-deb-arm64/stable\","
            + "\"icon\":\"code\",\"desktop\":\"code.desktop\"},"
            + "{\"id\":\"zcode\",\"name\":\"ZCode\",\"kind\":\"appimage-extract\","
            + "\"url\":\"https://cdn-zcode.z.ai/zcode/electron/releases/3.7.7/linux-arm64/"
            + "ZCode-3.7.7-linux-arm64.AppImage\",\"target\":\"/opt/zcode-app\","
            + "\"exec_flags\":\"--no-sandbox --disable-gpu\"},"
            + "{\"id\":\"gemini\",\"name\":\"Gemini\",\"kind\":\"webapp\","
            + "\"url\":\"https://gemini.google.com/app\",\"profile\":\"/root/.config/gemini-app\"},"
            + "{\"id\":\"mousepad\",\"name\":\"Блокнот\",\"kind\":\"apt\",\"package\":\"mousepad\"}"
            + "]";

    @Test
    public void readsEveryKindOfInstallFromTheFile() {
        List<AppSpec> apps = AppsCatalog.parse(SEVEN);
        assertEquals(4, apps.size());
        assertEquals(AppSpec.Kind.DEB, apps.get(0).kind());
        assertEquals(AppSpec.Kind.APPIMAGE, apps.get(1).kind());
        assertEquals(AppSpec.Kind.WEBAPP, apps.get(2).kind());
        assertEquals(AppSpec.Kind.REPO, apps.get(3).kind());
        assertEquals("Visual Studio Code", apps.get(0).name());
        assertEquals("code.desktop", apps.get(0).desktop());
        assertEquals("/opt/zcode-app", apps.get(1).target());
        assertEquals("/root/.config/gemini-app", apps.get(2).profile());
        assertEquals("mousepad", apps.get(3).packageName());
    }

    @Test
    public void keepsTheOrderOfTheFile() {
        StringBuilder ids = new StringBuilder();
        for (AppSpec app : AppsCatalog.parse(SEVEN)) {
            ids.append(app.id()).append(' ');
        }
        assertEquals("vscode zcode gemini mousepad ", ids.toString());
    }

    @Test
    public void readsTheSameFromAStream() throws IOException {
        List<AppSpec> apps = AppsCatalog.read(
                new ByteArrayInputStream(SEVEN.getBytes(StandardCharsets.UTF_8)));
        assertEquals(4, apps.size());
        assertEquals("Блокнот", apps.get(3).name());
    }

    /** Ярлык не выдумывается: у пакета он свой, и проверять надо настоящий. */
    @Test
    public void launcherNameIsNotInventedForPackages() {
        AppSpec repo = AppsCatalog.parse(SEVEN).get(3);
        assertTrue("имя ярлыка придумано за пакет", repo.desktop().isEmpty());
    }

    @Test
    public void packageNameFallsBackToTheIdentifier() {
        AppSpec app = AppsCatalog.parse(
                "[{\"id\":\"gimp\",\"name\":\"Рисование\",\"kind\":\"apt\"}]").get(0);
        assertEquals("gimp", app.packageName());
    }

    @Test
    public void unknownKindOfInstallIsRefusedByName() {
        broken("[{\"id\":\"x\",\"name\":\"Икс\",\"kind\":\"snap\",\"url\":\"https://x\"}]",
                "незнакомый способ");
    }

    @Test
    public void everyProgramNeedsAnAddressExceptTheOneFromTheRepository() {
        broken("[{\"id\":\"x\",\"name\":\"Икс\",\"kind\":\"deb\"}]", "нет адреса");
        assertEquals(1, AppsCatalog.parse(
                "[{\"id\":\"x\",\"name\":\"Икс\",\"kind\":\"apt\"}]").size());
    }

    @Test
    public void unpackedProgramNeedsToKnowWhereItGoes() {
        broken("[{\"id\":\"x\",\"name\":\"Икс\",\"kind\":\"appimage-extract\","
                + "\"url\":\"https://x\"}]", "куда её раскладывать");
    }

    @Test
    public void missingNameIsRefused() {
        broken("[{\"id\":\"x\",\"kind\":\"apt\"}]", "нет поля name");
    }

    @Test
    public void repeatedProgramIsRefused() {
        broken("[{\"id\":\"x\",\"name\":\"Икс\",\"kind\":\"apt\"},"
                + "{\"id\":\"x\",\"name\":\"Икс\",\"kind\":\"apt\"}]", "названа дважды");
    }

    @Test
    public void emptyOrTornFileIsRefused() {
        broken("[]", "нет ни одной программы");
        broken("[{\"id\":\"x\",\"name\":\"Икс\",\"kind\":\"apt\"", "оборван");
    }

    private static void broken(String json, String saidInMessage) {
        try {
            AppsCatalog.parse(json);
            fail("испорченный список принят: " + json);
        } catch (IllegalArgumentException refused) {
            assertTrue("отказ не называет, что не так: " + refused.getMessage(),
                    refused.getMessage().contains(saidInMessage));
        }
    }
}
