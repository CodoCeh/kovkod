package ru.codoceh.stol.settings;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import ru.codoceh.stol.R;

public final class PhoneFiles {

    private static final String PREFS = "phone_files";
    private static final String KEY_ASKED = "asked";

    private PhoneFiles() {
    }

    public static boolean granted(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void ask(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } else {
            activity.requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        }
    }

    public static void offerOnce(Activity activity) {
        android.content.SharedPreferences prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        if (granted(activity) || prefs.getBoolean(KEY_ASKED, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_ASKED, true).apply();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_files_title)
                .setMessage(R.string.settings_files_access)
                .setPositiveButton(R.string.settings_notifications_allow, (d, w) -> ask(activity))
                .setNegativeButton(R.string.settings_updates_close, null)
                .show();
    }
}
