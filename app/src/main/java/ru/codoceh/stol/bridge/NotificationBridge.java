package ru.codoceh.stol.bridge;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.IOException;

import ru.codoceh.stol.runtime.NativeAssets;
import ru.codoceh.stol.settings.DesktopPrefs;

/** Уведомления телефона всплывают на столе, пока он открыт. */
public class NotificationBridge extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification posted) {
        Notification notification = posted.getNotification();
        if (!DesktopBridge.isDesktopLive() || !DesktopPrefs.phoneNotifications(this) || posted.getPackageName().equals(getPackageName())
                || posted.isOngoing() || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }
        Bundle extras = notification.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) {
            return;
        }
        String app = posted.getPackageName();
        try {
            PackageManager pm = getPackageManager();
            app = pm.getApplicationLabel(pm.getApplicationInfo(posted.getPackageName(), 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        String heading = title == null ? app : app + ": " + title;
        try {
            DesktopBridge.notify(NativeAssets.rootfsDir(this), posted.getPackageName(),
                    heading, text == null ? "" : text.toString());
        } catch (IOException ignored) {
        }
    }
}
