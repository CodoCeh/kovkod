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

    /** Открыть ярлык программы на столе; {@code window} — normal, max или full. */
    public static void launch(File rootfs, String guestPath, String window) throws IOException {
        if (!DesktopEntry.isLaunchable(guestPath)) {
            throw new IOException("ярлык вне рабочего стола и меню: " + guestPath);
        }
        send(rootfs, "launch", guestPath + "\n" + window);
    }

    /** Открыть файл программой по умолчанию. */
    public static void open(File rootfs, String guestPath) throws IOException {
        if (!guestPath.startsWith("/root/") || guestPath.contains("..")) {
            throw new IOException("файл вне домашней папки: " + guestPath);
        }
        send(rootfs, "open", guestPath);
    }

    /** Открыть ссылку в браузере стола. */
    public static void browse(File rootfs, String url) throws IOException {
        if (!isWebLink(url)) {
            throw new IOException("не ссылка: " + url);
        }
        send(rootfs, "browse", url);
    }

    /** Включить стол в браузере с этим паролем или выключить ({@code null}). */
    public static void remote(File rootfs, String password) throws IOException {
        if (password != null && (password.length() < 6 || password.contains("\n"))) {
            throw new IOException("пароль короче шести знаков");
        }
        send(rootfs, "remote", password == null ? "off" : "on\n" + password);
    }

    static boolean isWebLink(String text) {
        return text.matches("(?i)https?://[^\\s]+");
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
