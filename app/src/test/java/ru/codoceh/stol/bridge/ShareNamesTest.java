package ru.codoceh.stol.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class ShareNamesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void namesCannotLeaveTheInbox() {
        assertEquals("_.._etc_passwd", ShareActivity.safeName("/../etc/passwd"));
        assertEquals("bashrc", ShareActivity.safeName("..bashrc"));
        assertEquals("файл", ShareActivity.safeName("  "));
        assertEquals("Отчёт.pdf", ShareActivity.safeName("Отчёт.pdf"));
    }

    @Test
    public void existingFilesAreNotOverwritten() throws IOException {
        File dir = tmp.newFolder();
        new File(dir, "фото.jpg").createNewFile();
        new File(dir, "фото (2).jpg").createNewFile();
        assertEquals("фото (3).jpg", ShareActivity.unique(dir, "фото.jpg"));
        assertEquals("новое.txt", ShareActivity.unique(dir, "новое.txt"));
    }

    @Test
    public void onlyPlainWebLinksGoToTheBrowser() {
        assertTrue(DesktopBridge.isWebLink("https://codoceh.ru/blog"));
        assertFalse(DesktopBridge.isWebLink("file:///etc/passwd"));
        assertFalse(DesktopBridge.isWebLink("https://a.ru\n/root/x"));
        assertFalse(DesktopBridge.isWebLink("посмотри https://a.ru"));
    }
}
