package ru.codoceh.stol.bridge;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Приложения Android в меню «Пуск» стола и их запуск рядом со столом. */
public final class AndroidApps {

    static final String ENTRIES = "/root/.local/share/applications";
    static final String ICONS = "/root/.local/share/kovkod/android-icons";
    static final String PREFIX = "android-";
    private static final int ICON_PX = 96;

    private AndroidApps() {
    }

    /** Пересобирает ярлыки: новые приложения появляются, удалённые пропадают. */
    public static void sync(Context context, File rootfs) {
        File entries = DesktopEntry.guest(rootfs, ENTRIES);
        File icons = DesktopEntry.guest(rootfs, ICONS);
        if (!entries.isDirectory() && !entries.mkdirs() || !icons.isDirectory() && !icons.mkdirs()) {
            return;
        }
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        Set<String> keep = new HashSet<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !isPackageName(pkg) || !keep.add(pkg)) {
                continue;
            }
            File icon = new File(icons, pkg + ".png");
            try {
                if (!icon.isFile()) {
                    writeIcon(app.loadIcon(pm), icon);
                }
                String label = app.loadLabel(pm).toString().replace('\n', ' ').trim();
                String entry = "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=" + label + "\n"
                        + "Exec=/opt/stol/android-open.sh " + pkg + "\n"
                        + "Icon=" + ICONS + "/" + pkg + ".png\n"
                        + "Categories=X-KovKod-Android;\n"
                        + "Terminal=false\n";
                File file = new File(entries, PREFIX + pkg + ".desktop");
                byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
                if (!file.isFile() || !java.util.Arrays.equals(Files.readAllBytes(file.toPath()), bytes)) {
                    Files.write(file.toPath(), bytes);
                }
            } catch (IOException | RuntimeException skipped) {
            }
        }
        File[] stale = entries.listFiles((d, n) -> n.startsWith(PREFIX) && n.endsWith(".desktop"));
        if (stale != null) {
            for (File file : stale) {
                String pkg = file.getName().substring(PREFIX.length(), file.getName().length() - ".desktop".length());
                if (!keep.contains(pkg)) {
                    file.delete();
                    new File(icons, pkg + ".png").delete();
                }
            }
        }
    }

    /** Открывает приложение; на большом экране — рядом со столом. */
    public static boolean open(Context context, String pkg) {
        if (!isPackageName(pkg)) {
            return false;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            return false;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
        context.startActivity(launch, ActivityOptions.makeBasic().toBundle());
        return true;
    }

    static boolean isPackageName(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    }

    private static void writeIcon(Drawable drawable, File target) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, ICON_PX, ICON_PX);
        drawable.draw(canvas);
        try (FileOutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }
}
