package ru.codoceh.stol.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Icon;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ru.codoceh.stol.R;

/** Значки программ стола на главном экране телефона. */
public final class HomeShortcuts {

    private static final String[] QUICK = {
            "/root/Desktop/codoceh-store.desktop",
            "/usr/share/applications/kovkod-parametry.desktop",
            "/usr/share/applications/xfce4-terminal.desktop",
            "/usr/share/applications/thunar.desktop",
    };

    private HomeShortcuts() {
    }

    /** Просит лаунчер закрепить значок; {@code false}, если лаунчер этого не умеет. */
    public static boolean pin(Context context, File rootfs, DesktopEntry entry, String label, Bitmap customIcon) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            return false;
        }
        String id = "app:" + entry.guestPath + (label.equals(entry.name) && customIcon == null
                ? "" : ":" + Integer.toHexString((label + (customIcon == null ? "" : customIcon.getGenerationId())).hashCode()));
        return manager.requestPinShortcut(build(context, rootfs, entry, id, label, customIcon), null);
    }

    /** Быстрые действия на долгом нажатии значка КовКода. */
    public static void publishQuick(Context context, File rootfs) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) {
            return;
        }
        List<ShortcutInfo> shortcuts = new ArrayList<>();
        int limit = Math.min(QUICK.length, manager.getMaxShortcutCountPerActivity());
        for (String path : QUICK) {
            DesktopEntry entry = DesktopEntry.read(rootfs, path);
            if (entry != null && shortcuts.size() < limit) {
                shortcuts.add(build(context, rootfs, entry, "app:" + entry.guestPath, entry.name, null));
            }
        }
        try {
            manager.setDynamicShortcuts(shortcuts);
        } catch (IllegalStateException | IllegalArgumentException rateLimited) {
        }
    }

    private static ShortcutInfo build(Context context, File rootfs, DesktopEntry entry, String id,
                                      String label, Bitmap customIcon) {
        Intent intent = new Intent(context, ShortcutActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(ShortcutActivity.EXTRA_PATH, entry.guestPath);
        return new ShortcutInfo.Builder(context, id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon(context, rootfs, entry, customIcon))
                .setIntent(intent)
                .build();
    }

    static Icon icon(Context context, File rootfs, DesktopEntry entry, Bitmap customIcon) {
        File file = entry.iconFile(rootfs);
        Bitmap bitmap = customIcon != null ? customIcon
                : file != null ? BitmapFactory.decodeFile(file.getAbsolutePath()) : null;
        if (bitmap == null) {
            return Icon.createWithResource(context, R.mipmap.ic_launcher);
        }
        int size = 432;
        Bitmap padded = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(padded);
        canvas.drawColor(context.getColor(R.color.win_card));
        int inner = size / 2;
        canvas.drawBitmap(Bitmap.createScaledBitmap(bitmap, inner, inner, true),
                (size - inner) / 2f, (size - inner) / 2f, null);
        return Icon.createWithAdaptiveBitmap(padded);
    }
}
