package ru.codoceh.stol.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Почтовые ящики между приложением и живым столом: {@code /run/stol/bridge/in} читает
 * {@code /opt/stol/stol-bridge.sh}, {@code /run/stol/bridge/out} заполняет стол.
 */
public final class DesktopBridge {

    static final String DIR = "/run/stol/bridge";

    private DesktopBridge() {
    }

    /** Открыть ярлык программы на столе. */
    public static void launch(File rootfs, String guestPath) throws IOException {
        if (!DesktopEntry.isLaunchable(guestPath)) {
            throw new IOException("ярлык вне рабочего стола и меню: " + guestPath);
        }
        send(rootfs, "launch", guestPath);
    }

    /** Открыть файл программой по умолчанию. */
    public static void open(File rootfs, String guestPath) throws IOException {
        if (!guestPath.startsWith("/root/") || guestPath.contains("..")) {
            throw new IOException("файл вне домашней папки: " + guestPath);
        }
        send(rootfs, "open", guestPath);
    }

    /** Ящик, куда стол кладёт просьбы к телефону. */
    public static File outbox(File rootfs) {
        return DesktopEntry.guest(rootfs, DIR + "/out");
    }

    private static void send(File rootfs, String verb, String guestPath) throws IOException {
        File inbox = DesktopEntry.guest(rootfs, DIR + "/in");
        if (!inbox.isDirectory() && !inbox.mkdirs()) {
            throw new IOException("нет ящика " + inbox);
        }
        String name = System.nanoTime() + "";
        File tmp = new File(inbox, name + ".tmp");
        Files.write(tmp.toPath(), (verb + "\n" + guestPath + "\n").getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), new File(inbox, name + ".req").toPath(), StandardCopyOption.ATOMIC_MOVE);
    }
}
