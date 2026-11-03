package ru.codoceh.stol.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import java.io.IOException;

import ru.codoceh.stol.MainActivity;
import ru.codoceh.stol.runtime.NativeAssets;

/** Нажатие значка программы на главном экране: запрос столу и показ стола. */
public class ShortcutActivity extends Activity {

    public static final String EXTRA_PATH = "ru.codoceh.stol.extra.DESKTOP_PATH";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (DesktopEntry.isLaunchable(path)) {
            try {
                DesktopBridge.launch(NativeAssets.rootfsDir(this), path);
            } catch (IOException ignored) {
            }
        }
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        finish();
    }
}
