package ru.codoceh.stol.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ярлык программы стола: путь внутри образа, имя и значок. */
public final class DesktopEntry {

    static final String DESKTOP_DIR = "/root/Desktop";
    static final String APPLICATIONS_DIR = "/usr/share/applications";

    private static final String[] ICON_SIZES = {"256x256", "192x192", "128x128", "96x96", "64x64", "48x48"};

    public final String guestPath;
    public final String name;
    final String icon;

    DesktopEntry(String guestPath, String name, String icon) {
        this.guestPath = guestPath;
        this.name = name;
        this.icon = icon;
    }

    /** Можно ли запускать этот путь с главного экрана телефона. */
    public static boolean isLaunchable(String guestPath) {
        return guestPath != null
                && guestPath.endsWith(".desktop")
                && !guestPath.contains("..")
                && (guestPath.startsWith(DESKTOP_DIR + "/") || guestPath.startsWith(APPLICATIONS_DIR + "/"));
    }

    /** Программы стола: сначала ярлыки рабочего стола, затем меню, без повторов по имени. */
    public static List<DesktopEntry> list(File rootfs) {
        Map<String, DesktopEntry> byName = new LinkedHashMap<>();
        for (String dir : new String[]{DESKTOP_DIR, APPLICATIONS_DIR}) {
            File[] files = guest(rootfs, dir).listFiles((d, n) -> n.endsWith(".desktop"));
            if (files == null) {
                continue;
            }
            List<DesktopEntry> found = new ArrayList<>();
            for (File file : files) {
                DesktopEntry entry = read(rootfs, dir + "/" + file.getName());
                if (entry != null) {
                    found.add(entry);
                }
            }
            found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            for (DesktopEntry entry : found) {
                byName.putIfAbsent(entry.name.toLowerCase(), entry);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(byName.values()));
    }

    /** Разбирает ярлык; {@code null}, если это не видимая в меню программа. */
    public static DesktopEntry read(File rootfs, String guestPath) {
        if (!isLaunchable(guestPath)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(guest(rootfs, guestPath).toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return parse(guestPath, lines);
    }

    static DesktopEntry parse(String guestPath, List<String> lines) {
        Map<String, String> keys = new LinkedHashMap<>();
        boolean inEntry = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("[")) {
                inEntry = line.equals("[Desktop Entry]");
                continue;
            }
            int eq = line.indexOf('=');
            if (inEntry && eq > 0) {
                keys.putIfAbsent(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        if (!"Application".equals(keys.get("Type"))
                || "true".equalsIgnoreCase(keys.get("NoDisplay"))
                || "true".equalsIgnoreCase(keys.get("Hidden"))) {
            return null;
        }
        String name = keys.containsKey("Name[ru]") ? keys.get("Name[ru]") : keys.get("Name");
        if (name == null || name.isEmpty()) {
            return null;
        }
        return new DesktopEntry(guestPath, name, keys.getOrDefault("Icon", ""));
    }

    /** Картинка значка, которую Android умеет прочесть (PNG), или {@code null}. */
    public File iconFile(File rootfs) {
        if (icon.isEmpty()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        if (icon.startsWith("/")) {
            candidates.add(icon);
        } else {
            for (String size : ICON_SIZES) {
                candidates.add("/usr/share/icons/hicolor/" + size + "/apps/" + icon + ".png");
            }
            candidates.add("/usr/share/pixmaps/" + icon + ".png");
        }
        for (String candidate : candidates) {
            File file = guest(rootfs, candidate);
            if (candidate.endsWith(".png") && file.isFile()) {
                return file;
            }
        }
        return null;
    }

    static File guest(File rootfs, String guestPath) {
        return new File(rootfs, guestPath.startsWith("/") ? guestPath.substring(1) : guestPath);
    }
}
