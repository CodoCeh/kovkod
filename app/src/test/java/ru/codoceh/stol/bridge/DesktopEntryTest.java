package ru.codoceh.stol.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class DesktopEntryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void russianNameWins() {
        DesktopEntry entry = DesktopEntry.parse("/root/Desktop/a.desktop", Arrays.asList(
                "[Desktop Entry]", "Type=Application", "Name=Terminal", "Name[ru]=Терминал", "Icon=term",
                "[Desktop Action new]", "Name=New"));
        assertEquals("Терминал", entry.name);
        assertEquals("term", entry.icon);
    }

    @Test
    public void hiddenAndNonApplicationsAreSkipped() {
        assertNull(DesktopEntry.parse("/root/Desktop/a.desktop",
                Arrays.asList("[Desktop Entry]", "Type=Application", "Name=A", "NoDisplay=true")));
        assertNull(DesktopEntry.parse("/root/Desktop/a.desktop",
                Arrays.asList("[Desktop Entry]", "Type=Link", "Name=A")));
    }

    @Test
    public void onlyDesktopAndMenuShortcutsAreLaunchable() {
        assertTrue(DesktopEntry.isLaunchable("/root/Desktop/zcode.desktop"));
        assertTrue(DesktopEntry.isLaunchable("/usr/share/applications/thunar.desktop"));
        assertFalse(DesktopEntry.isLaunchable("/root/Desktop/../.bashrc"));
        assertFalse(DesktopEntry.isLaunchable("/root/Desktop/../../etc/x.desktop"));
        assertFalse(DesktopEntry.isLaunchable("/tmp/evil.desktop"));
        assertFalse(DesktopEntry.isLaunchable("/root/Desktop/run.sh"));
        assertFalse(DesktopEntry.isLaunchable(null));
    }

    @Test
    public void listDedupesByNameAndFindsPngIcon() throws IOException {
        File rootfs = tmp.newFolder("rootfs");
        write(rootfs, "root/Desktop/zcode.desktop", "[Desktop Entry]\nType=Application\nName=ZCode\nIcon=zcode\n");
        write(rootfs, "usr/share/applications/zcode.desktop", "[Desktop Entry]\nType=Application\nName=ZCode\n");
        write(rootfs, "usr/share/applications/thunar.desktop", "[Desktop Entry]\nType=Application\nName=Файлы\n");
        write(rootfs, "usr/share/pixmaps/zcode.png", "png");

        List<DesktopEntry> apps = DesktopEntry.list(rootfs);

        assertEquals(2, apps.size());
        assertEquals("/root/Desktop/zcode.desktop", apps.get(0).guestPath);
        assertEquals(new File(rootfs, "usr/share/pixmaps/zcode.png"), apps.get(0).iconFile(rootfs));
        assertNull(apps.get(1).iconFile(rootfs));
    }

    private static void write(File rootfs, String path, String content) throws IOException {
        File file = new File(rootfs, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
