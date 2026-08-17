package ru.codoceh.stol.launch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import ru.codoceh.stol.R;

/**
 * Держит приложение живым, пока живёт стол.
 *
 * <p>Сессию образа держит процесс приложения, а Android волен снять процесс, у которого нет
 * ничего заметного на экране. Снятый процесс уносит и сессию — то есть весь стол. Служба на
 * виду этого не даёт: пока стол работает, у приложения есть причина оставаться в памяти.
 *
 * <p>Ничего не считает и ничем не управляет: вся работа со столом — в
 * {@link DesktopLauncher}.
 */
public final class DesktopService extends Service {

    private static final String CHANNEL = "stol-desktop";
    private static final int NOTIFICATION = 1;

    /** Просит систему не снимать приложение, пока стол работает. */
    public static void hold(Context context) {
        context.startForegroundService(new Intent(context, DesktopService.class));
    }

    /** Отпускает: стол остановлен, держаться больше не за что. */
    public static void release(Context context) {
        context.stopService(new Intent(context, DesktopService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION, notification());
        // Стол не переживёт снятия приложения, поэтому поднимать службу заново незачем:
        // поднимать будет уже нечего.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);

        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent tap = open == null ? null : PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.service_title))
                .setContentText(getString(R.string.service_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(tap)
                .setOngoing(true)
                .build();
    }
}
