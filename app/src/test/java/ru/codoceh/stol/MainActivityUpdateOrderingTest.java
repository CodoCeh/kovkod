package ru.codoceh.stol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** The desktop must wait for the rootfs update that refreshes its wallpaper. */
public class MainActivityUpdateOrderingTest {

    @Test
    public void existingDesktopIsNotRenderedReadyBeforeTheAutomaticUpdate() throws Exception {
        String source = new String(Files.readAllBytes(source().toPath()), StandardCharsets.UTF_8);
        int init = source.indexOf("private void initWizard()");
        int render = source.indexOf("private void renderWizardState", init);
        String method = source.substring(init, render);

        assertTrue("обновление стола должно запускаться автоматически",
                method.contains("executor.execute(() -> wizard.build())"));
        assertFalse("READY нельзя рисовать до update(): XFCE закеширует старые обои",
                method.contains("renderWizardState(wizard.state())"));
    }

    private static File source() {
        File dir = new File(System.getProperty("user.dir"));
        for (int depth = 0; depth < 4 && dir != null; depth++, dir = dir.getParentFile()) {
            File source = new File(dir, "app/src/main/java/ru/codoceh/stol/MainActivity.java");
            if (source.isFile()) {
                return source;
            }
        }
        throw new AssertionError("не найден MainActivity.java");
    }
}
