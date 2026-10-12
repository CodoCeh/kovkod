package ru.codoceh.stol.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.codoceh.stol.R;
import ru.codoceh.stol.about.AboutActivity;

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

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        page.setPadding(pad, pad, pad, pad);
        page.setBackgroundColor(getColor(R.color.forge_bg));

        page.addView(text(R.string.settings_title, 22, true, 0));
        page.addView(text(R.string.settings_hint, 14, false, dp(6), R.color.forge_muted));

        page.addView(sectionTitle(R.string.settings_resolution_title));
        RadioGroup resolutionGroup = new RadioGroup(this);
        resolutionGroup.setOrientation(RadioGroup.VERTICAL);
        int currentWidth = DesktopPrefs.width(this);
        int currentHeight = DesktopPrefs.height(this);
        for (int[] resolution : RESOLUTIONS) {
            RadioButton button = radioButton(resolution[0] == 0 && resolution[1] == 0
                    ? getString(R.string.settings_resolution_auto)
                    : resolution[0] + "×" + resolution[1]);
            button.setOnClickListener(v -> DesktopPrefs.setResolution(this, resolution[0], resolution[1]));
            resolutionGroup.addView(button);
            button.setChecked(resolution[0] == currentWidth && resolution[1] == currentHeight);
        }
        page.addView(resolutionGroup);

        page.addView(sectionTitle(R.string.settings_scale_title));
        RadioGroup scaleGroup = new RadioGroup(this);
        scaleGroup.setOrientation(RadioGroup.VERTICAL);
        int currentScale = DesktopPrefs.scale(this);
        addScaleOption(scaleGroup, getString(R.string.settings_scale_auto), 0, currentScale);
        addScaleOption(scaleGroup, getString(R.string.settings_scale_1x), 1, currentScale);
        addScaleOption(scaleGroup, getString(R.string.settings_scale_2x), 2, currentScale);
        page.addView(scaleGroup);

        page.addView(sectionTitle(R.string.settings_panel_title));
        page.addView(text(R.string.settings_panel_hint, 14, false, dp(4), R.color.forge_muted));
        RadioGroup panelGroup = new RadioGroup(this);
        panelGroup.setOrientation(RadioGroup.VERTICAL);
        int currentPanelPosition = DesktopPrefs.panelPosition(this);
        for (int i = 0; i < PANEL_POSITIONS.length; i++) {
            int position = PANEL_POSITIONS[i];
            RadioButton button = radioButton(getString(PANEL_POSITION_LABELS[i]));
            button.setOnClickListener(v -> DesktopPrefs.setPanelPosition(this, position));
            panelGroup.addView(button);
            button.setChecked(position == currentPanelPosition);
        }
        page.addView(panelGroup);

        page.addView(sectionTitle(R.string.settings_cpu_title));
        page.addView(text(R.string.settings_cpu_hint, 14, false, dp(4), R.color.forge_muted));
        CheckBox economize = new CheckBox(this);
        economize.setText(R.string.settings_cpu_title);
        economize.setTextColor(getColor(R.color.forge_text));
        economize.setChecked(DesktopPrefs.economizeCpu(this));
        economize.setOnCheckedChangeListener(
                (buttonView, checked) -> DesktopPrefs.setEconomizeCpu(this, checked));
        LinearLayout.LayoutParams economizeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        economizeParams.topMargin = dp(8);
        page.addView(economize, economizeParams);

        page.addView(sectionTitle(R.string.settings_screen_title));
        page.addView(text(R.string.settings_screen_hint, 14, false, dp(4), R.color.forge_muted));
        CheckBox economizeScreen = new CheckBox(this);
        economizeScreen.setText(R.string.settings_screen_title);
        economizeScreen.setTextColor(getColor(R.color.forge_text));
        economizeScreen.setChecked(DesktopPrefs.economizeScreen(this));
        economizeScreen.setOnCheckedChangeListener(
                (buttonView, checked) -> DesktopPrefs.setEconomizeScreen(this, checked));
        LinearLayout.LayoutParams economizeScreenParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        economizeScreenParams.topMargin = dp(8);
        page.addView(economizeScreen, economizeScreenParams);

        page.addView(sectionTitle(R.string.settings_ram_title));
        page.addView(text(R.string.settings_ram_hint, 14, false, dp(4), R.color.forge_muted));
        RadioGroup ramGroup = new RadioGroup(this);
        ramGroup.setOrientation(RadioGroup.VERTICAL);
        int currentRamLimit = DesktopPrefs.ramLimitMb(this);
        for (int limitMb : RAM_LIMITS_MB) {
            RadioButton button = radioButton(
                    limitMb == 0 ? getString(R.string.settings_ram_unlimited) : limitMb + " МБ");
            button.setOnClickListener(v -> DesktopPrefs.setRamLimitMb(this, limitMb));
            ramGroup.addView(button);
            button.setChecked(limitMb == currentRamLimit);
        }
        page.addView(ramGroup);

        Button restart = new Button(this);
        restart.setText(R.string.settings_restart_action);
        LinearLayout.LayoutParams restartParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        restartParams.topMargin = dp(32);
        restart.setLayoutParams(restartParams);
        restart.setOnClickListener(v -> confirmRestart());
        page.addView(restart);

        page.addView(sectionTitle(R.string.settings_updates_title));
        page.addView(text(R.string.settings_updates_hint, 14, false, dp(4), R.color.forge_muted));
        Button update = new Button(this);
        update.setText(R.string.settings_updates_action);
        update.setOnClickListener(v -> checkForUpdate(update));
        page.addView(update);

        Button about = new Button(this);
        about.setText(R.string.about_title);
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        aboutParams.topMargin = dp(32);
        about.setLayoutParams(aboutParams);
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                startActivity(new Intent(SettingsActivity.this, AboutActivity.class));
            }
        });
        page.addView(about);

        ScrollView scroll = new ScrollView(this);
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

    private void addScaleOption(RadioGroup group, String label, int value, int currentScale) {
        RadioButton button = radioButton(label);
        button.setOnClickListener(v -> DesktopPrefs.setScale(this, value));
        group.addView(button);
        button.setChecked(value == currentScale);
    }

    private RadioButton radioButton(String label) {
        RadioButton button = new RadioButton(this);
        button.setText(label);
        button.setTextColor(getColor(R.color.forge_text));
        return button;
    }

    private TextView sectionTitle(int resource) {
        return text(resource, 17, true, dp(28), R.color.forge_text);
    }

    private TextView text(int resource, int size, boolean bold, int marginTop) {
        return text(resource, size, bold, marginTop, R.color.forge_text);
    }

    private TextView text(int resource, int size, boolean bold, int marginTop, int color) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(getColor(color));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = marginTop;
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
