package ru.codoceh.stol.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.codoceh.stol.R;
import ru.codoceh.stol.about.AboutActivity;
import ru.codoceh.stol.bridge.DesktopEntry;
import ru.codoceh.stol.runtime.NativeAssets;
import ru.codoceh.stol.runtime.ProotSession;

/**
 * Настройки стола (нативный экран приложения, не внутри самого Linux-рабочего стола):
 * разрешение и масштаб интерфейса, экономия процессора, потолок памяти, и «О программе» —
 * раньше кнопка на неё была прямо на экране ожидания кузницы и мешала там, где должна
 * быть только анимация.
 *
 * <p>Все настройки читает {@code MainActivity} перед запуском стола — на уже открытый
 * стол они не действуют, только на следующий запуск (R-настройки живут в
 * {@link DesktopPrefs}).
 */
public class SettingsActivity extends Activity {

    private final ExecutorService background = Executors.newSingleThreadExecutor();

    /**
     * Код результата для {@link Activity#setResult(int)}: стол просят перезапустить прямо
     * сейчас, а не ждать следующего запуска приложения.
     */
    public static final int RESULT_RESTART_DESKTOP = RESULT_FIRST_USER;

    /** Ширина/высота — {@code 0} читается как «авто», см. {@link DesktopPrefs#width}. */
    private static final int[][] RESOLUTIONS = {
            {0, 0}, {1280, 800}, {1600, 900}, {1920, 1080},
    };

    private static final int[] RAM_LIMITS_MB = {0, 1024, 2048, 3072, 4096};

    private static final int REQUEST_BACKUP_SAVE = 10;
    private static final int REQUEST_BACKUP_OPEN = 11;
    private static final String BACKUP_TMP = "/tmp/kovkod-backup.tar.gz";

    private static final int[] PANEL_POSITIONS = {
            DesktopPrefs.PANEL_TOP_RIGHT, DesktopPrefs.PANEL_BOTTOM_RIGHT,
            DesktopPrefs.PANEL_TOP_LEFT, DesktopPrefs.PANEL_BOTTOM_LEFT,
    };

    private static final int[] PANEL_POSITION_LABELS = {
            R.string.settings_panel_top_right, R.string.settings_panel_bottom_right,
            R.string.settings_panel_top_left, R.string.settings_panel_bottom_left,
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.win_bg));
        getWindow().setNavigationBarColor(getColor(R.color.win_bg));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(24), dp(16), dp(32));
        page.setBackgroundColor(getColor(R.color.win_bg));

        TextView title = text(getString(R.string.settings_title), 28, true, R.color.forge_text);
        page.addView(title);
        TextView hint = text(getString(R.string.settings_hint), 13, false, R.color.forge_muted);
        page.addView(hint, margins(0, dp(6), 0, dp(8)));

        page.addView(groupTitle(R.string.settings_group_display));
        int currentWidth = DesktopPrefs.width(this);
        int currentHeight = DesktopPrefs.height(this);
        String[] resolutionLabels = new String[RESOLUTIONS.length];
        int resolutionIndex = 0;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            resolutionLabels[i] = RESOLUTIONS[i][0] == 0 && RESOLUTIONS[i][1] == 0
                    ? getString(R.string.settings_resolution_auto)
                    : RESOLUTIONS[i][0] + "×" + RESOLUTIONS[i][1];
            if (RESOLUTIONS[i][0] == currentWidth && RESOLUTIONS[i][1] == currentHeight) {
                resolutionIndex = i;
            }
        }
        page.addView(card(R.string.settings_resolution_title, 0, dropdown(resolutionLabels, resolutionIndex,
                i -> DesktopPrefs.setResolution(this, RESOLUTIONS[i][0], RESOLUTIONS[i][1]))));

        int[] scales = {0, 1, 2};
        String[] scaleLabels = {getString(R.string.settings_scale_auto),
                getString(R.string.settings_scale_1x), getString(R.string.settings_scale_2x)};
        int currentScale = DesktopPrefs.scale(this);
        int scaleIndex = 0;
        for (int i = 0; i < scales.length; i++) {
            if (scales[i] == currentScale) {
                scaleIndex = i;
            }
        }
        page.addView(card(R.string.settings_scale_title, 0, dropdown(scaleLabels, scaleIndex,
                i -> DesktopPrefs.setScale(this, scales[i]))));

        android.view.Display[] monitors = getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        java.util.List<String> modeKeys = new java.util.ArrayList<>();
        java.util.List<String> modeLabels = new java.util.ArrayList<>();
        modeKeys.add("");
        modeLabels.add(getString(R.string.settings_monitor_auto));
        if (monitors.length > 0) {
            android.view.Display.Mode[] modes = monitors[0].getSupportedModes();
            java.util.Arrays.sort(modes, (a, b) -> a.getPhysicalWidth() != b.getPhysicalWidth()
                    ? b.getPhysicalWidth() - a.getPhysicalWidth()
                    : Float.compare(b.getRefreshRate(), a.getRefreshRate()));
            for (android.view.Display.Mode mode : modes) {
                String key = DesktopPrefs.modeKey(mode);
                if (!modeKeys.contains(key)) {
                    modeKeys.add(key);
                    modeLabels.add(mode.getPhysicalWidth() + "×" + mode.getPhysicalHeight()
                            + " · " + Math.round(mode.getRefreshRate()) + " Гц");
                }
            }
        }
        int modeIndex = Math.max(0, modeKeys.indexOf(DesktopPrefs.monitorMode(this)));
        if (modeIndex == 0 && !DesktopPrefs.monitorMode(this).isEmpty()) {
            modeKeys.add(DesktopPrefs.monitorMode(this));
            modeLabels.add(DesktopPrefs.monitorMode(this).replace("x", "×").replace("@", " · ") + " Гц");
            modeIndex = modeKeys.size() - 1;
        }
        page.addView(card(R.string.settings_monitor_title,
                monitors.length > 0 ? R.string.settings_monitor_hint : R.string.settings_monitor_none,
                dropdown(modeLabels.toArray(new String[0]), modeIndex,
                        i -> DesktopPrefs.setMonitorMode(this, modeKeys.get(i)))));

        page.addView(switchCard(R.string.settings_screen_title, R.string.settings_screen_hint,
                DesktopPrefs.economizeScreen(this),
                checked -> DesktopPrefs.setEconomizeScreen(this, checked)));

        page.addView(groupTitle(R.string.settings_group_controls));
        String[] panelLabels = new String[PANEL_POSITIONS.length];
        int panelIndex = 0;
        int currentPanelPosition = DesktopPrefs.panelPosition(this);
        for (int i = 0; i < PANEL_POSITIONS.length; i++) {
            panelLabels[i] = getString(PANEL_POSITION_LABELS[i]);
            if (PANEL_POSITIONS[i] == currentPanelPosition) {
                panelIndex = i;
            }
        }
        page.addView(card(R.string.settings_panel_title, R.string.settings_panel_hint,
                dropdown(panelLabels, panelIndex,
                        i -> DesktopPrefs.setPanelPosition(this, PANEL_POSITIONS[i]))));

        page.addView(card(R.string.settings_pin_title, R.string.settings_pin_hint,
                button(getString(R.string.settings_pin_action), false, v -> choosePinnedApp())));

        String[] windowModes = {DesktopPrefs.WINDOW_FULLSCREEN, DesktopPrefs.WINDOW_MAXIMIZED, DesktopPrefs.WINDOW_NORMAL};
        String[] windowLabels = {getString(R.string.settings_window_full),
                getString(R.string.settings_window_max), getString(R.string.settings_window_normal)};
        int windowIndex = java.util.Arrays.asList(windowModes).indexOf(DesktopPrefs.shortcutWindow(this));
        page.addView(card(R.string.settings_window_title, R.string.settings_window_hint,
                dropdown(windowLabels, Math.max(windowIndex, 0),
                        i -> DesktopPrefs.setShortcutWindow(this, windowModes[i]))));

        page.addView(groupTitle(R.string.settings_group_performance));
        page.addView(switchCard(R.string.settings_cpu_title, R.string.settings_cpu_hint,
                DesktopPrefs.economizeCpu(this),
                checked -> DesktopPrefs.setEconomizeCpu(this, checked)));

        String[] ramLabels = new String[RAM_LIMITS_MB.length];
        int ramIndex = 0;
        int currentRamLimit = DesktopPrefs.ramLimitMb(this);
        for (int i = 0; i < RAM_LIMITS_MB.length; i++) {
            ramLabels[i] = RAM_LIMITS_MB[i] == 0
                    ? getString(R.string.settings_ram_unlimited) : RAM_LIMITS_MB[i] + " МБ";
            if (RAM_LIMITS_MB[i] == currentRamLimit) {
                ramIndex = i;
            }
        }
        page.addView(card(R.string.settings_ram_title, R.string.settings_ram_hint,
                dropdown(ramLabels, ramIndex, i -> DesktopPrefs.setRamLimitMb(this, RAM_LIMITS_MB[i]))));

        page.addView(groupTitle(R.string.settings_group_system));
        page.addView(card(R.string.settings_restart_action, R.string.settings_restart_confirm_message,
                button(getString(R.string.settings_restart_confirm_yes), true, v -> confirmRestart())));

        LinearLayout notificationsCard = switchCard(R.string.settings_notifications_title,
                R.string.settings_notifications_hint, DesktopPrefs.phoneNotifications(this), on -> {
                    DesktopPrefs.setPhoneNotifications(this, on);
                    if (on && !notificationAccessGranted()) {
                        new AlertDialog.Builder(this)
                                .setMessage(R.string.settings_notifications_access)
                                .setPositiveButton(R.string.settings_notifications_allow, (d, w) -> startActivity(
                                        new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                                .setNegativeButton(R.string.settings_updates_close, null)
                                .show();
                    }
                });
        page.addView(notificationsCard);

        LinearLayout remoteCard = switchCard(R.string.settings_remote_title, R.string.settings_remote_hint,
                DesktopPrefs.remotePassword(this) != null, null);
        Switch remoteSwitch = (Switch) remoteCard.getChildAt(1);
        remoteSwitch.setOnCheckedChangeListener((view, on) -> {
            if (on && DesktopPrefs.remotePassword(this) == null) {
                askRemotePassword(remoteSwitch);
            } else if (!on) {
                DesktopPrefs.setRemotePassword(this, null);
                sendRemote(null);
            }
        });
        page.addView(remoteCard);
        if (DesktopPrefs.remotePassword(this) != null) {
            TextView address = text(remoteAddress(), 13, false, R.color.win_accent);
            page.addView(address, margins(dp(16), 0, dp(16), dp(8)));
        }

        LinearLayout backupButtons = new LinearLayout(this);
        backupButtons.addView(button(getString(R.string.settings_backup_save), true, v -> startActivityForResult(
                new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/gzip")
                        .putExtra(Intent.EXTRA_TITLE, "КовКод-копия-"
                                + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                                        .format(new java.util.Date()) + ".tar.gz"),
                REQUEST_BACKUP_SAVE)));
        backupButtons.addView(button(getString(R.string.settings_backup_restore), false, v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQUEST_BACKUP_OPEN)), wrap(dp(8)));
        page.addView(card(R.string.settings_backup_title, R.string.settings_backup_hint, backupButtons));

        Button update = button(getString(R.string.settings_updates_action), false, null);
        update.setOnClickListener(v -> checkForUpdate(update));
        page.addView(card(R.string.settings_updates_title, R.string.settings_updates_hint, update));

        LinearLayout about = row(getString(R.string.about_title), getString(R.string.about_product_summary));
        about.setOnClickListener(v -> startActivity(new Intent(this, AboutActivity.class)));
        page.addView(about, cardMargins());

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getColor(R.color.win_bg));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    private void checkForUpdate(Button button) {
        button.setEnabled(false);
        button.setText(R.string.settings_updates_checking);
        background.execute(() -> {
            try {
                ReleaseInfo release = ReleaseChecker.latest(new ReleaseChecker.GithubSource());
                runOnUiThread(() -> showUpdateResult(button, release));
            } catch (IOException failure) {
                runOnUiThread(() -> {
                    restoreUpdateButton(button);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.settings_updates_error_title)
                            .setMessage(R.string.settings_updates_error_message)
                            .setPositiveButton(R.string.settings_updates_retry,
                                    (dialog, which) -> checkForUpdate(button))
                            .setNegativeButton(R.string.settings_updates_close, null)
                            .show();
                });
            }
        });
    }

    private void showUpdateResult(Button button, ReleaseInfo release) {
        restoreUpdateButton(button);
        String installedVersion = installedVersion();
        if (!ReleaseInfo.isNewer(release.version, installedVersion)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_updates_current_title)
                    .setMessage(getString(R.string.settings_updates_current_message,
                            installedVersion))
                    .setPositiveButton(R.string.settings_updates_close, null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_updates_available_title, release.version))
                .setMessage(R.string.settings_updates_available_message)
                .setPositiveButton(R.string.settings_updates_download,
                        (dialog, which) -> downloadUpdate(release))
                .setNegativeButton(R.string.settings_updates_close, null)
                .show();
    }

    private void downloadUpdate(ReleaseInfo release) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.downloadUrl));
        request.setTitle(getString(R.string.settings_updates_download_title, release.version));
        request.setDescription(getString(R.string.settings_updates_download_description));
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                "KovKod-" + release.version + "-arm64.apk");
        ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_updates_queued_title)
                .setMessage(R.string.settings_updates_queued_message)
                .setPositiveButton(R.string.settings_updates_close, null)
                .show();
    }

    private void restoreUpdateButton(Button button) {
        button.setEnabled(true);
        button.setText(R.string.settings_updates_action);
    }

    private String installedVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return "0";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_BACKUP_SAVE) {
            runBackup(data.getData(), true);
        } else if (requestCode == REQUEST_BACKUP_OPEN) {
            runBackup(data.getData(), false);
        }
    }

    private void runBackup(Uri uri, boolean save) {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(save ? R.string.settings_backup_saving : R.string.settings_backup_restoring)
                .setCancelable(false)
                .show();
        File rootfs = NativeAssets.rootfsDir(this);
        ProotSession session = new ProotSession(NativeAssets.nativeDir(this), rootfs);
        File archive = session.inside(BACKUP_TMP);
        background.execute(() -> {
            boolean ok;
            try {
                if (save) {
                    ok = session.exec("sh /opt/stol/backup.sh create " + BACKUP_TMP, false) == 0
                            && copy(new java.io.FileInputStream(archive), getContentResolver().openOutputStream(uri, "wt"));
                } else {
                    ok = copy(getContentResolver().openInputStream(uri), new java.io.FileOutputStream(archive))
                            && session.exec("sh /opt/stol/backup.sh restore " + BACKUP_TMP, false) == 0;
                }
            } catch (IOException | RuntimeException failed) {
                ok = false;
            } finally {
                archive.delete();
            }
            boolean done = ok;
            runOnUiThread(() -> {
                progress.dismiss();
                AlertDialog.Builder result = new AlertDialog.Builder(this)
                        .setMessage(!done ? R.string.settings_backup_failed
                                : save ? R.string.settings_backup_saved : R.string.settings_backup_restored);
                if (done && !save) {
                    result.setPositiveButton(R.string.settings_restart_confirm_yes, (d, w) -> {
                        setResult(RESULT_RESTART_DESKTOP);
                        finish();
                    }).setNegativeButton(R.string.settings_updates_close, null);
                } else {
                    result.setPositiveButton(R.string.settings_updates_close, null);
                }
                result.show();
            });
        });
    }

    private static boolean copy(java.io.InputStream in, java.io.OutputStream out) throws IOException {
        if (in == null || out == null) {
            return false;
        }
        try (java.io.InputStream source = in; java.io.OutputStream target = out) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = source.read(buffer)) > 0; ) {
                target.write(buffer, 0, n);
            }
        }
        return true;
    }

    private boolean notificationAccessGranted() {
        String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void askRemotePassword(Switch remoteSwitch) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setHint(R.string.settings_remote_password_hint);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, margins(0, 0, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_remote_password_title)
                .setView(box)
                .setPositiveButton(R.string.settings_remote_enable, null)
                .setNegativeButton(R.string.settings_restart_confirm_no, (d, w) -> remoteSwitch.setChecked(false))
                .setOnCancelListener(d -> remoteSwitch.setChecked(false))
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String password = input.getText().toString().trim();
            if (password.length() < 6) {
                input.setError(getString(R.string.settings_remote_password_short));
                return;
            }
            DesktopPrefs.setRemotePassword(this, password);
            sendRemote(password);
            dialog.dismiss();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_remote_title)
                    .setMessage(getString(R.string.settings_remote_ready, remoteAddress()))
                    .setPositiveButton(R.string.settings_updates_close, null)
                    .show();
        });
    }

    private void sendRemote(String password) {
        try {
            ru.codoceh.stol.bridge.DesktopBridge.remote(NativeAssets.rootfsDir(this), password);
        } catch (IOException ignored) {
        }
    }

    private String remoteAddress() {
        android.net.ConnectivityManager network = getSystemService(android.net.ConnectivityManager.class);
        for (android.net.Network candidate : network == null ? new android.net.Network[0] : network.getAllNetworks()) {
            android.net.NetworkCapabilities caps = network.getNetworkCapabilities(candidate);
            android.net.LinkProperties link = network.getLinkProperties(candidate);
            if (caps == null || link == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                    || !(caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) {
                continue;
            }
            for (android.net.LinkAddress address : link.getLinkAddresses()) {
                if (address.getAddress() instanceof java.net.Inet4Address && !address.getAddress().isLoopbackAddress()) {
                    return "http://" + address.getAddress().getHostAddress() + ":6080/vnc.html";
                }
            }
        }
        return getString(R.string.settings_remote_no_network);
    }

    private void choosePinnedApp() {
        File rootfs = NativeAssets.rootfsDir(this);
        background.execute(() -> {
            List<DesktopEntry> apps = DesktopEntry.list(rootfs);
            runOnUiThread(() -> {
                if (apps.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setMessage(R.string.settings_pin_empty)
                            .setPositiveButton(R.string.settings_updates_close, null)
                            .show();
                    return;
                }
                String[] names = new String[apps.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = apps.get(i).name;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.settings_pin_title)
                        .setItems(names, (dialog, which) -> startActivity(
                                new Intent(this, ru.codoceh.stol.bridge.PinActivity.class)
                                        .putExtra(ru.codoceh.stol.bridge.PinActivity.EXTRA_PATH, apps.get(which).guestPath)))
                        .setNegativeButton(R.string.settings_restart_confirm_no, null)
                        .show();
            });
        });
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_restart_confirm_title)
                .setMessage(R.string.settings_restart_confirm_message)
                .setPositiveButton(R.string.settings_restart_confirm_yes, (dialog, which) -> {
                    setResult(RESULT_RESTART_DESKTOP);
                    finish();
                })
                .setNegativeButton(R.string.settings_restart_confirm_no, null)
                .show();
    }

    private interface OnPick {
        void picked(int index);
    }

    private interface OnToggle {
        void toggled(boolean checked);
    }

    private TextView groupTitle(int resource) {
        TextView view = text(getString(resource), 14, true, R.color.forge_text);
        view.setLayoutParams(margins(dp(4), dp(24), 0, dp(8)));
        return view;
    }

    private LinearLayout cardShell() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(rounded(R.color.win_card, 0, 8));
        card.setLayoutParams(cardMargins());
        return card;
    }

    private LinearLayout card(int titleRes, int hintRes, View control) {
        LinearLayout card = cardShell();
        card.addView(text(getString(titleRes), 15, false, R.color.forge_text));
        if (hintRes != 0) {
            card.addView(text(getString(hintRes), 12, false, R.color.forge_muted), margins(0, dp(2), 0, 0));
        }
        card.addView(control, margins(0, dp(12), 0, 0));
        return card;
    }

    private LinearLayout switchCard(int titleRes, int hintRes, boolean checked, OnToggle onToggle) {
        LinearLayout card = cardShell();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(getString(titleRes), 15, false, R.color.forge_text));
        texts.addView(text(getString(hintRes), 12, false, R.color.forge_muted), margins(0, dp(2), 0, 0));
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states,
                new int[]{getColor(R.color.win_on_accent), getColor(R.color.forge_text)}));
        toggle.setTrackTintList(new ColorStateList(states,
                new int[]{getColor(R.color.win_accent), getColor(R.color.forge_muted)}));
        if (onToggle != null) {
            toggle.setOnCheckedChangeListener((view, isChecked) -> onToggle.toggled(isChecked));
        }
        card.addView(toggle, wrap(dp(12)));
        card.setOnClickListener(v -> toggle.toggle());
        return card;
    }

    private LinearLayout row(String title, String hint) {
        LinearLayout card = cardShell();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 15, false, R.color.forge_text));
        texts.addView(text(hint, 12, false, R.color.forge_muted), margins(0, dp(2), 0, 0));
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        card.addView(text("›", 22, false, R.color.forge_muted), wrap(dp(12)));
        card.setClickable(true);
        return card;
    }

    private Spinner dropdown(String[] labels, int selected, OnPick onPick) {
        Spinner spinner = new Spinner(this, Spinner.MODE_DROPDOWN);
        spinner.setBackground(rounded(R.color.win_control, R.color.win_stroke, 6));
        spinner.setPopupBackgroundDrawable(rounded(R.color.win_card, R.color.win_stroke, 8));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(getColor(R.color.forge_text));
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                view.setPadding(dp(12), dp(10), dp(12), dp(10));
                Drawable chevron = getDrawable(android.R.drawable.arrow_down_float).mutate();
                chevron.setTint(getColor(R.color.forge_muted));
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, chevron, null);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(getColor(position == spinner.getSelectedItemPosition()
                        ? R.color.win_accent : R.color.forge_text));
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                return view;
            }
        };
        spinner.setAdapter(adapter);
        spinner.setSelection(selected, false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onPick.picked(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinner.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return spinner;
    }

    private Button button(String label, boolean accent, View.OnClickListener onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setTextColor(getColor(accent ? R.color.win_on_accent : R.color.forge_text));
        button.setBackground(accent ? rounded(R.color.win_accent, 0, 6)
                : rounded(R.color.win_control, R.color.win_stroke, 6));
        button.setStateListAnimator(null);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(36));
        button.setMinimumHeight(dp(36));
        if (onClick != null) {
            button.setOnClickListener(onClick);
        }
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getColor(fill));
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            shape.setStroke(dp(1), getColor(stroke));
        }
        return shape;
    }

    private LinearLayout.LayoutParams wrap(int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = left;
        return params;
    }

    private LinearLayout.LayoutParams cardMargins() {
        return margins(0, 0, 0, dp(4));
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(getColor(color));
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
