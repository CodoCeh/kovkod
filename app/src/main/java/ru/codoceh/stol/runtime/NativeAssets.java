package ru.codoceh.stol.runtime;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Раскладывает родные бинарники из assets в каталог данных приложения и ставит бит
 * исполнения: в самом APK файлы неисполняемы. Откуда взят каждый файл — в
 * {@code assets/native/SOURCES.md}.
 */
public final class NativeAssets {

    private static final String ASSET_ROOT = "native";
    private static final String[] TREES = {"bin", "lib", "libexec"};
    private static final String STAMP = ".installed";

    private NativeAssets() {
    }

    public static File nativeDir(Context context) {
        return new File(context.getFilesDir(), "native");
    }

    public static File rootfsDir(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    /** Идемпотентно: повторная установка происходит только после обновления APK. */
    public static File install(Context context) throws IOException {
        File dest = nativeDir(context);
        File stamp = new File(dest, STAMP);
        String version = String.valueOf(updatedAt(context));
        if (version.equals(readStamp(stamp))) {
            return dest;
        }
        for (String tree : TREES) {
            copyTree(context.getAssets(), ASSET_ROOT + "/" + tree, new File(dest, tree));
        }
        writeStamp(stamp, version);
        return dest;
    }

    private static void copyTree(AssetManager assets, String assetPath, File dest) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyFile(assets, assetPath, dest);
            return;
        }
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("не создать каталог " + dest);
        }
        for (String child : children) {
            copyTree(assets, assetPath + "/" + child, new File(dest, child));
        }
    }

    private static void copyFile(AssetManager assets, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("не создать каталог " + parent);
        }
        byte[] buffer = new byte[65536];
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        try {
            Os.chmod(dest.getAbsolutePath(), 0700);
        } catch (ErrnoException failed) {
            throw new IOException("не поставить бит исполнения на " + dest, failed);
        }
    }

    private static long updatedAt(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException impossible) {
            return 0L;
        }
    }

    private static String readStamp(File stamp) {
        if (!stamp.isFile()) {
            return null;
        }
        byte[] bytes = new byte[(int) stamp.length()];
        try (InputStream in = new java.io.FileInputStream(stamp)) {
            int read = in.read(bytes);
            return read <= 0 ? null : new String(bytes, 0, read, Charset.forName("UTF-8"));
        } catch (IOException failed) {
            return null;
        }
    }

    private static void writeStamp(File stamp, String version) throws IOException {
        try (OutputStream out = new FileOutputStream(stamp)) {
            out.write(version.getBytes(Charset.forName("UTF-8")));
        }
    }
}
