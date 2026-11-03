package ru.codoceh.stol.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import ru.codoceh.stol.R;
import ru.codoceh.stol.runtime.NativeAssets;

/** Закрепление программы стола на главном экране: своё название и значок или как есть. */
public class PinActivity extends Activity {

    public static final String EXTRA_PATH = "ru.codoceh.stol.extra.PIN_PATH";
    private static final int REQUEST_ICON = 1;

    private File rootfs;
    private DesktopEntry entry;
    private Bitmap customIcon;
    private ImageView preview;
    private EditText name;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        rootfs = NativeAssets.rootfsDir(this);
        entry = DesktopEntry.read(rootfs, getIntent().getStringExtra(EXTRA_PATH));
        if (entry == null) {
            finish();
            return;
        }
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        preview = new ImageView(this);
        preview.setImageIcon(HomeShortcuts.icon(this, rootfs, entry, null));
        box.addView(preview, new LinearLayout.LayoutParams(dp(72), dp(72)));

        name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(entry.name);
        name.setSelectAllOnFocus(true);
        name.setHint(R.string.pin_name_hint);
        box.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout iconButtons = new LinearLayout(this);
        Button pick = new Button(this);
        pick.setText(R.string.pin_icon_pick);
        pick.setAllCaps(false);
        pick.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQUEST_ICON));
        Button reset = new Button(this);
        reset.setText(R.string.pin_icon_reset);
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> {
            customIcon = null;
            preview.setImageIcon(HomeShortcuts.icon(this, rootfs, entry, null));
        });
        iconButtons.addView(pick);
        iconButtons.addView(reset);
        box.addView(iconButtons);

        new AlertDialog.Builder(this)
                .setTitle(R.string.pin_title)
                .setView(box)
                .setPositiveButton(R.string.pin_add, (d, w) -> pin())
                .setNegativeButton(R.string.settings_restart_confirm_no, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void pin() {
        String label = name.getText().toString().trim();
        if (!HomeShortcuts.pin(this, rootfs, entry, label.isEmpty() ? entry.name : label, customIcon)) {
            Toast.makeText(this, R.string.settings_pin_unsupported, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ICON || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            Bitmap picked = in == null ? null : BitmapFactory.decodeStream(in);
            if (picked != null) {
                customIcon = picked;
                preview.setImageIcon(HomeShortcuts.icon(this, rootfs, entry, customIcon));
            }
        } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
            Toast.makeText(this, R.string.pin_icon_bad, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
