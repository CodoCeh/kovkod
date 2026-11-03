package ru.codoceh.stol.bridge;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

import ru.codoceh.stol.MainActivity;
import ru.codoceh.stol.R;
import ru.codoceh.stol.runtime.NativeAssets;

/** «Поделиться → КовКод»: файлы и ссылки из Android открываются на столе. */
public class ShareActivity extends Activity {

    static final String INBOX = "/root/Входящие";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        List<Uri> uris = streams(intent);
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        File rootfs = NativeAssets.rootfsDir(this);
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean sent;
            try {
                sent = deliver(rootfs, uris, text);
            } catch (IOException | RuntimeException failed) {
                sent = false;
            }
            boolean ok = sent;
            runOnUiThread(() -> {
                Toast.makeText(this, ok ? R.string.share_sent : R.string.share_failed, Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                finish();
            });
        });
    }

    private boolean deliver(File rootfs, List<Uri> uris, String text) throws IOException {
        File inbox = DesktopEntry.guest(rootfs, INBOX);
        if (!uris.isEmpty()) {
            if (!inbox.isDirectory() && !inbox.mkdirs()) {
                throw new IOException("нет папки " + inbox);
            }
            String last = null;
            for (Uri uri : uris) {
                last = copy(uri, inbox);
            }
            DesktopBridge.open(rootfs, uris.size() == 1 ? INBOX + "/" + last : INBOX);
            return true;
        }
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String trimmed = text.trim();
        if (DesktopBridge.isWebLink(trimmed)) {
            DesktopBridge.browse(rootfs, trimmed);
            return true;
        }
        if (!inbox.isDirectory() && !inbox.mkdirs()) {
            throw new IOException("нет папки " + inbox);
        }
        String name = "Текст " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(new Date()) + ".txt";
        Files.write(new File(inbox, name).toPath(), text.getBytes(StandardCharsets.UTF_8));
        DesktopBridge.open(rootfs, INBOX + "/" + name);
        return true;
    }

    private String copy(Uri uri, File inbox) throws IOException {
        String name = unique(inbox, safeName(displayName(uri)));
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(new File(inbox, name))) {
            if (in == null) {
                throw new IOException("не открыть " + uri);
            }
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
        }
        return name;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException unreadable) {
        }
        String last = uri.getLastPathSegment();
        return last != null ? last : "файл";
    }

    static String safeName(String name) {
        String cleaned = name.replace('/', '_').replace('\\', '_').replace('\0', '_').trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned.isEmpty() ? "файл" : cleaned;
    }

    static String unique(File dir, String name) {
        if (!new File(dir, name).exists()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> streams(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) {
                uris.addAll(many);
            }
        } else {
            Uri one = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (one != null) {
                uris.add(one);
            }
        }
        return uris;
    }
}
