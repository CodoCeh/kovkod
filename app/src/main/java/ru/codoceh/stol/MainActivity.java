package ru.codoceh.stol;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.codoceh.stol.bridge.AndroidApps;
import ru.codoceh.stol.bridge.DesktopBridge;
import ru.codoceh.stol.bridge.DesktopEntry;
import ru.codoceh.stol.bridge.HomeShortcuts;
import ru.codoceh.stol.bridge.PinActivity;
import ru.codoceh.stol.screen.GamepadView;
import ru.codoceh.stol.screen.TouchpadView;
import ru.codoceh.stol.launch.DesktopFit;
import ru.codoceh.stol.launch.DesktopLauncher;
import ru.codoceh.stol.launch.DesktopService;
import ru.codoceh.stol.launch.DesktopSound;
import ru.codoceh.stol.launch.LaunchTexts;
import ru.codoceh.stol.help.HelpAction;
import ru.codoceh.stol.help.HelpTopics;
import ru.codoceh.stol.help.HelpView;
import ru.codoceh.stol.runtime.NativeAssets;
import ru.codoceh.stol.screen.Keysyms;
import ru.codoceh.stol.runtime.ProotSession;
import ru.codoceh.stol.screen.DesktopView;
import ru.codoceh.stol.screen.TouchTranslator;
import ru.codoceh.stol.settings.DesktopPrefs;
import ru.codoceh.stol.settings.SettingsActivity;
import ru.codoceh.stol.setup.ForgeView;
import ru.codoceh.stol.setup.InstallFailedException;
import ru.codoceh.stol.setup.SetupTexts;
import ru.codoceh.stol.setup.InstallProgress;
import ru.codoceh.stol.setup.RootfsInstaller;
import ru.codoceh.stol.setup.SetupWizard;

/**
 * Главный экран приложения: мастер первого запуска, экран ожидания стола с кузницей
 * и показ самого стола через RFB-клиент.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "stol_prefs";
    private static final String KEY_INPUT_MODE = "input_mode";
    private static final int REQUEST_SETTINGS = 1;
    private static final String KEY_TOUR_SEEN = "tour_seen";
    /** ~8 кадров в секунду вместо неограниченных — заметно экономит заряд на движении. */
    private static final long SCREEN_ECONOMIZE_FRAME_INTERVAL_MS = 120;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ProotSession session;
    private SetupWizard wizard;
    private DesktopLauncher launcher;
    private DesktopView desktopView;
    private Button modeButton;
    /** Держим ли службу на виду: см. {@link #holdWhileDesktopLives}. */
    private boolean holdingDesktop;
    /** Во что поднят стол: размер вида в точках экрана и масштаб интерфейса XFCE. */
    private DesktopFit desktopFit;

    private FrameLayout rootContainer;
    private View waitingView;
    private TextView titleView;
    private TextView hintView;
    private TextView stepCounterView;
    private ForgeView forgeView;
    private TextView percentView;
    private Button actionButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        session = new ProotSession(NativeAssets.nativeDir(this), NativeAssets.rootfsDir(this));
        displays = getSystemService(android.hardware.display.DisplayManager.class);
        displays.registerDisplayListener(monitorListener, mainHandler);

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(getColor(R.color.forge_bg));
        setContentView(rootContainer);

        setupInsets(rootContainer);

        waitingView = createWaitingView();
        rootContainer.addView(waitingView);

        if (session.isRootfsReady()) {
            executor.execute(() -> {
                try {
                    RootfsInstaller.forApp(this).update(new InstallProgress() {
                        @Override
                        public void onStage(Stage stage, long totalBytes) {
                        }

                        @Override
                        public void onProgress(long doneBytes) {
                        }
                    });
                } catch (InstallFailedException | RuntimeException skipped) {
                }
                mainHandler.post(() -> {
                    extractAssets();
                    initLauncher();
                });
            });
        } else {
            initWizard();
        }
    }

    private void extractAssets() {
        try {
            File pixmapsDir = new File(NativeAssets.rootfsDir(this), "usr/share/pixmaps");
            pixmapsDir.mkdirs();
            File iconsHicolorDir = new File(NativeAssets.rootfsDir(this), "usr/share/icons/hicolor/48x48/apps");
            iconsHicolorDir.mkdirs();

            String[] iconFiles = getAssets().list("icons");
            if (iconFiles != null) {
                for (String name : iconFiles) {
                    File dst1 = new File(pixmapsDir, name);
                    File dst2 = new File(iconsHicolorDir, name);
                    try (java.io.InputStream in = getAssets().open("icons/" + name)) {
                        byte[] data = new byte[in.available()];
                        int read = 0;
                        while (read < data.length) {
                            int r = in.read(data, read, data.length - read);
                            if (r <= 0) break;
                            read += r;
                        }
                        java.nio.file.Files.write(dst1.toPath(), data);
                        java.nio.file.Files.write(dst2.toPath(), data);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setupInsets(View view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            v.setPadding(left, top, right, bottom);
            return insets;
        });
    }

    private View createWaitingView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(dp(24), dp(32), dp(24), dp(32));

        titleView = new TextView(this);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        titleView.setTextColor(getColor(R.color.forge_text));
        titleView.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(titleView);

        hintView = new TextView(this);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hintView.setTextColor(getColor(R.color.forge_muted));
        hintView.setGravity(Gravity.CENTER_HORIZONTAL);
        hintView.setPadding(0, dp(12), 0, 0);
        layout.addView(hintView);

        stepCounterView = new TextView(this);
        stepCounterView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        stepCounterView.setTextColor(getColor(R.color.forge_muted));
        stepCounterView.setGravity(Gravity.CENTER_HORIZONTAL);
        stepCounterView.setPadding(0, dp(8), 0, 0);
        layout.addView(stepCounterView);

        // Человек смотрит на этот экран несколько минут, поэтому здесь кузница КодоЦеха —
        // та же, что на экране ожидания стола, — а не полоса (R04.1).
        // Появляется из темноты, а не всплывает сразу: человек открывает стол в первый раз
        // на чёрном фоне — кузница должна проступить, а не мигнуть готовой картинкой.
        forgeView = new ForgeView(this);
        forgeView.setAlpha(0f);
        forgeView.animate().alpha(1f).setStartDelay(200).setDuration(1600).start();
        LinearLayout.LayoutParams forgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        forgeParams.setMargins(0, dp(16), 0, 0);
        layout.addView(forgeView, forgeParams);

        percentView = new TextView(this);
        percentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        percentView.setTextColor(getColor(R.color.forge_brand));
        percentView.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.addView(percentView);

        actionButton = new Button(this);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, dp(24), 0, 0);
        actionButton.setLayoutParams(btnParams);
        layout.addView(actionButton);

        TextView forged = new TextView(this);
        forged.setText(R.string.forged_mark);
        forged.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        forged.setTextColor(getColor(R.color.forge_muted));
        forged.setGravity(Gravity.CENTER_HORIZONTAL);
        forged.setPadding(0, dp(32), 0, 0);
        layout.addView(forged);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        return scroll;
    }

    private void initWizard() {
        wizard = SetupWizard.forApp(this, state -> mainHandler.post(() -> renderWizardState(state)));
        // Не рисуем READY до update(): иначе DesktopLauncher успевает поднять XFCE и
        // прочитать старые обои до наложения нового слоя.
        executor.execute(() -> wizard.build());
    }

    private void renderWizardState(SetupWizard.State state) {
        titleView.setText(SetupTexts.title(state.phase()));
        stepCounterView.setVisibility(View.GONE);
        forgeView.show(state.phase());

        if (state.phase() == SetupWizard.Phase.FAILED) {
            String cause = getString(SetupTexts.failureCause(state.reason()));
            if (state.reason() == InstallFailedException.Reason.NOT_ENOUGH_SPACE) {
                cause = String.format(cause, state.requiredHuman(), state.availableHuman());
            }
            String action = getString(SetupTexts.failureAction(state.reason()));
            hintView.setText(cause + "\n\n" + action);
            percentView.setVisibility(View.GONE);
        } else {
            int hintRes = SetupTexts.hint(state.phase());
            hintView.setText(hintRes != 0 ? getString(hintRes) : "");
            if (state.phase() == SetupWizard.Phase.BUILDING) {
                stepCounterView.setVisibility(View.VISIBLE);
                stepCounterView.setText(String.format(getString(SetupTexts.stepCounter()),
                        state.stage().ordinal() + 1, SetupWizard.TOTAL_STEPS)
                        + ": " + getString(SetupTexts.step(state.stage())));
                percentView.setVisibility(View.VISIBLE);
                percentView.setText(String.format(
                        getString(SetupTexts.percent()), state.percent()));
            } else {
                percentView.setVisibility(View.GONE);
            }
        }

        if (state.action() != null) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText(SetupTexts.action(state.action()));
            actionButton.setOnClickListener(v -> executor.execute(() -> wizard.build()));
        } else {
            actionButton.setVisibility(View.GONE);
        }

        if (state.phase() == SetupWizard.Phase.READY) {
            initLauncher();
        }
    }

    private void initLauncher() {
        File rootfs = NativeAssets.rootfsDir(this);
        executor.execute(() -> HomeShortcuts.publishQuick(getApplicationContext(), rootfs));
        executor.execute(() -> AndroidApps.sync(getApplicationContext(), rootfs));
        launcher = new DesktopLauncher(session, state -> mainHandler.post(() -> renderLauncherState(state)));
        renderLauncherState(launcher.state());
        launchWhenMeasured();
    }

    /**
     * Стол поднимается размером ровно в вид, в котором его покажут: одна точка стола —
     * одна точка экрана. Пока вид не измерен, размера ещё нет — поэтому запуск ждёт
     * первой раскладки. Врезки системных полос уже вычтены: они лежат отступами
     * контейнера, а вид стола занимает его целиком.
     */
    private void launchWhenMeasured() {
        int width = rootContainer.getWidth()
                - rootContainer.getPaddingLeft() - rootContainer.getPaddingRight();
        int height = rootContainer.getHeight()
                - rootContainer.getPaddingTop() - rootContainer.getPaddingBottom();
        if (width > 0 && height > 0) {
            desktopFit = fitFor(width, height);
            launchWithSettings();
            return;
        }
        rootContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        int w = rootContainer.getWidth()
                                - rootContainer.getPaddingLeft() - rootContainer.getPaddingRight();
                        int h = rootContainer.getHeight()
                                - rootContainer.getPaddingTop() - rootContainer.getPaddingBottom();
                        if (w <= 0 || h <= 0) {
                            return;
                        }
                        rootContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        desktopFit = fitFor(w, h);
                        launchWithSettings();
                    }
                });
    }

    /**
     * Размер и масштаб — из настроек стола, если там выбрано не «авто», иначе как раньше:
     * ровно вид, в котором стол покажут (см. {@link DesktopFit}).
     */
    private DesktopFit fitFor(int viewWidth, int viewHeight) {
        int densityDpi = getResources().getDisplayMetrics().densityDpi;
        int prefWidth = DesktopPrefs.width(this);
        int prefHeight = DesktopPrefs.height(this);
        int prefScale = DesktopPrefs.scale(this);
        int width = prefWidth > 0 ? prefWidth : viewWidth;
        int height = prefHeight > 0 ? prefHeight : viewHeight;
        int scale = prefScale > 0 ? prefScale : DesktopFit.autoScale(densityDpi);
        return DesktopFit.of(width, height, scale);
    }

    private void launchWithSettings() {
        boolean economizeCpu = DesktopPrefs.economizeCpu(this);
        int ramLimitMb = DesktopPrefs.ramLimitMb(this);
        executor.execute(() -> launcher.launch(desktopFit, economizeCpu, ramLimitMb));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            applyPanelButtons();
        }
        if (requestCode == REQUEST_SETTINGS && resultCode == SettingsActivity.RESULT_SHOW_HELP) {
            showHelp();
        } else if (requestCode == REQUEST_SETTINGS && resultCode == SettingsActivity.RESULT_RESTART_DESKTOP) {
            executor.execute(() -> launcher.stop());
            launchWhenMeasured();
        } else if (requestCode == REQUEST_SETTINGS && monitor != null) {
            android.view.Display shown = monitor.getDisplay();
            moveToPhone();
            moveToMonitor(shown);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rootContainer.post(() -> {
            int width = rootContainer.getWidth()
                    - rootContainer.getPaddingLeft() - rootContainer.getPaddingRight();
            int height = rootContainer.getHeight()
                    - rootContainer.getPaddingTop() - rootContainer.getPaddingBottom();
            if (width > 0 && height > 0 && launcher != null) {
                executor.execute(() -> launcher.resize(width, height));
            }
        });
    }

    private void renderLauncherState(DesktopLauncher.State state) {
        stepCounterView.setVisibility(View.GONE);
        percentView.setVisibility(View.GONE);
        holdWhileDesktopLives(state);

        if (state.phase() == DesktopLauncher.Phase.RUNNING) {
            showDesktop();
            DesktopBridge.setDesktopLive(true);
            if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_TOUR_SEEN, false)) {
                showHelp();
            }
            watchPinRequests();
            sound.start();
            String remote = DesktopPrefs.remotePassword(this);
            if (remote != null) {
                try {
                    DesktopBridge.remote(NativeAssets.rootfsDir(this), remote);
                } catch (IOException ignored) {
                }
            }
            followMonitor();
            return;
        }

        showWaiting();

        titleView.setText(LaunchTexts.title(state));
        int hint = LaunchTexts.hint(state);
        if (hint != 0) {
            hintView.setText(hint);
        }

        if (state.phase() == DesktopLauncher.Phase.FAILED) {
            forgeView.settle();
            String cause = getString(LaunchTexts.failureCause(state.failure()));
            String action = getString(LaunchTexts.failureAction(state.failure()));
            hintView.setText(cause + "\n\n" + action);
        } else {
            forgeView.setState(ForgeView.State.FORGING);
        }

        // Обычная загрузка — без единой кнопки, как заставка загрузки: кузница на чёрном
        // фоне и ничего больше. Кнопка возвращается только тогда, когда есть настоящая
        // причина её показать — стол не поднялся, и нужно повторить попытку.
        if (state.action() == DesktopLauncher.Action.RETRY) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText(LaunchTexts.action(state.action()));
            actionButton.setOnClickListener(v -> launchWithSettings());
        } else {
            actionButton.setVisibility(View.GONE);
        }
    }

    /**
     * Стол живёт в процессе приложения, поэтому пока он поднят или поднимается, приложению
     * нужна причина оставаться в памяти. Остановился — причина уходит вместе с ним.
     */
    private void holdWhileDesktopLives(DesktopLauncher.State state) {
        boolean alive;
        switch (state.phase()) {
            case STARTING:
            case WAITING_BEACON:
            case RUNNING:
                alive = true;
                break;
            default:
                alive = false;
                break;
        }
        // Только на переходе. Состояние приходит на каждое движение стола, а
        // startForegroundService, за которым сразу идёт stopService, система засчитывает
        // как «службу подняли и не показали» и снимает приложение с
        // RemoteServiceException — вместе с приложением уходит весь стол. Снято живьём:
        // «Context.startForegroundService() did not then call Service.startForeground()».
        if (alive == holdingDesktop) {
            return;
        }
        holdingDesktop = alive;
        if (alive) {
            DesktopService.hold(getApplicationContext());
        } else {
            DesktopService.release(getApplicationContext());
        }
    }

    private android.hardware.display.DisplayManager displays;
    private android.app.Presentation monitor;
    private TouchpadView touchpad;

    private final android.hardware.display.DisplayManager.DisplayListener monitorListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    followMonitor();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    followMonitor();
                }

                @Override
                public void onDisplayChanged(int displayId) {
                }
            };

    private android.view.Display externalDisplay() {
        android.view.Display[] found = displays.getDisplays(
                android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        return found.length > 0 ? found[0] : null;
    }

    private void followMonitor() {
        if (desktopView == null) {
            return;
        }
        android.view.Display external = externalDisplay();
        if (external != null && monitor == null) {
            moveToMonitor(external);
        } else if (external == null && monitor != null) {
            moveToPhone();
        } else if (external != null && monitor.getDisplay().getDisplayId() != external.getDisplayId()) {
            moveToPhone();
            moveToMonitor(external);
        }
    }

    private void moveToMonitor(android.view.Display external) {
        rootContainer.removeView(desktopView);
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        screen.addView(desktopView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        monitor = new android.app.Presentation(this, external);
        monitor.setContentView(screen);
        String wanted = DesktopPrefs.monitorMode(this);
        for (android.view.Display.Mode mode : external.getSupportedModes()) {
            if (DesktopPrefs.modeKey(mode).equals(wanted)) {
                android.view.WindowManager.LayoutParams attrs = monitor.getWindow().getAttributes();
                attrs.preferredDisplayModeId = mode.getModeId();
                monitor.getWindow().setAttributes(attrs);
            }
        }
        monitor.setOnDismissListener(dialog -> {
            if (monitor == dialog) {
                moveToPhone();
            }
        });
        desktopView.setTouchMode(TouchTranslator.Mode.TRACKPAD);
        try {
            monitor.show();
        } catch (android.view.WindowManager.InvalidDisplayException gone) {
            moveToPhone();
            return;
        }
        if (controlsBar != null) {
            controlsBar.setVisibility(View.GONE);
        }
        touchpad = new TouchpadView(this, desktopView, () -> startActivityForResult(
                new Intent(MainActivity.this, SettingsActivity.class), REQUEST_SETTINGS));
        rootContainer.addView(touchpad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        touchpad.requestFocus();
    }

    private void moveToPhone() {
        android.app.Presentation shown = monitor;
        monitor = null;
        if (touchpad != null) {
            rootContainer.removeView(touchpad);
            touchpad = null;
        }
        if (desktopView != null) {
            ViewGroup parent = (ViewGroup) desktopView.getParent();
            if (parent != null) {
                parent.removeView(desktopView);
            }
            rootContainer.addView(desktopView, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_INPUT_MODE, "touch");
            desktopView.setTouchMode("trackpad".equalsIgnoreCase(saved)
                    ? TouchTranslator.Mode.TRACKPAD : TouchTranslator.Mode.TOUCH);
        }
        if (controlsBar != null) {
            controlsBar.setVisibility(View.VISIBLE);
        }
        if (shown != null && shown.isShowing()) {
            shown.dismiss();
        }
    }

    private final DesktopSound sound = new DesktopSound();

    private android.os.FileObserver pinWatcher;

    private void watchPinRequests() {
        if (pinWatcher != null) {
            return;
        }
        File rootfs = NativeAssets.rootfsDir(this);
        File outbox = DesktopBridge.outbox(rootfs);
        outbox.mkdirs();
        pinWatcher = new android.os.FileObserver(outbox.getAbsolutePath(),
                android.os.FileObserver.MOVED_TO | android.os.FileObserver.CLOSE_WRITE) {
            @Override
            public void onEvent(int event, String name) {
                if (name == null || !(name.endsWith(".pin") || name.endsWith(".app"))) {
                    return;
                }
                File request = new File(outbox, name);
                String guestPath;
                try {
                    guestPath = new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8).trim();
                } catch (IOException unreadable) {
                    return;
                } finally {
                    request.delete();
                }
                if (name.endsWith(".app")) {
                    String pkg = guestPath;
                    mainHandler.post(() -> AndroidApps.open(MainActivity.this, pkg));
                    return;
                }
                if (DesktopEntry.read(rootfs, guestPath) != null) {
                    mainHandler.post(() -> startActivity(new Intent(MainActivity.this, PinActivity.class)
                            .putExtra(PinActivity.EXTRA_PATH, guestPath)));
                }
            }
        };
        pinWatcher.startWatching();
    }

    private HelpView help;

    private void showHelp() {
        if (help != null) {
            return;
        }
        help = new HelpView(this, HelpTopics.all(this), "start", this::runHelpAction, this::closeHelp);
        rootContainer.addView(help, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void closeHelp() {
        if (help == null) {
            return;
        }
        rootContainer.removeView(help);
        help = null;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(KEY_TOUR_SEEN, true).apply();
    }

    @Override
    public void onBackPressed() {
        if (help != null) {
            closeHelp();
            return;
        }
        super.onBackPressed();
    }

    private void runHelpAction(HelpAction action) {
        if (action == HelpAction.OPEN_SETTINGS) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQUEST_SETTINGS);
            return;
        }
        closeHelp();
        switch (action) {
            case START_MENU:
                pressKeys(Keysyms.SUPER_LEFT);
                break;
            case SNAP_LAYOUTS:
                launchDesktopEntry("/usr/share/applications/xfce4-terminal.desktop");
                mainHandler.postDelayed(() -> pressKeys(Keysyms.SUPER_LEFT, 'z'), 3000);
                break;
            case PARAMETERS:
                pressKeys(Keysyms.SUPER_LEFT, 'i');
                break;
            case SHOW_DESKTOP:
                pressKeys(Keysyms.SUPER_LEFT, 'd');
                break;
            case OPEN_STORE:
                launchDesktopEntry("/root/Desktop/codoceh-store.desktop");
                break;
            case OPEN_WINDOWS_PROGRAMS:
                launchDesktopEntry("/root/Desktop/windows-programs.desktop");
                break;
            case SHOW_KEYBOARD:
                if (desktopView != null) {
                    keyboardVisible = true;
                    desktopView.showKeyboard();
                }
                break;
            case SHOW_GAMEPAD:
                DesktopPrefs.setPanelGamepad(this, true);
                applyPanelButtons();
                if (gamepad == null) {
                    toggleGamepad();
                }
                break;
            default:
                break;
        }
    }

    /** Зажимает клавиши по порядку и отпускает в обратном — как сочетание на клавиатуре. */
    private void pressKeys(int... keysyms) {
        if (desktopView == null) {
            return;
        }
        for (int keysym : keysyms) {
            desktopView.sendKey(keysym, true);
        }
        for (int i = keysyms.length - 1; i >= 0; i--) {
            desktopView.sendKey(keysyms[i], false);
        }
    }

    private void launchDesktopEntry(String guestPath) {
        File rootfs = NativeAssets.rootfsDir(this);
        executor.execute(() -> {
            try {
                DesktopBridge.launch(rootfs, guestPath, "normal");
            } catch (IOException ignored) {
            }
        });
    }

    private GamepadView gamepad;

    private void toggleGamepad() {
        if (gamepad != null) {
            rootContainer.removeView(gamepad);
            gamepad = null;
            return;
        }
        if (desktopView == null) {
            return;
        }
        gamepad = new GamepadView(this, desktopView);
        rootContainer.addView(gamepad, rootContainer.indexOfChild(controlsBar), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static final int TASKBAR_PX = 48;
    private LinearLayout controlsBar;
    private Button keyboardButton;
    private Button gamepadButton;
    private boolean keyboardVisible;

    /** Угол экрана для {@link #controlsBar} — из настроек, см. {@code DesktopPrefs.PANEL_*}. */
    private FrameLayout.LayoutParams panelLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = dp(8);
        int position = DesktopPrefs.panelPosition(this);
        boolean top = position == DesktopPrefs.PANEL_TOP_RIGHT || position == DesktopPrefs.PANEL_TOP_LEFT;
        boolean end = position == DesktopPrefs.PANEL_TOP_RIGHT || position == DesktopPrefs.PANEL_BOTTOM_RIGHT;
        params.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | (end ? Gravity.END : Gravity.START);
        int bottom = margin;
        if (!top && desktopView != null) {
            int scale = desktopFit != null ? desktopFit.uiScale() : 1;
            bottom += desktopView.bottomBandHeight(TASKBAR_PX * scale);
        }
        params.setMargins(end ? 0 : margin, top ? margin : 0, end ? margin : 0, top ? 0 : bottom);
        return params;
    }

    private void applyPanelButtons() {
        if (gamepadButton == null) {
            return;
        }
        boolean gamepadOn = DesktopPrefs.panelGamepad(this);
        if (!gamepadOn && gamepad != null) {
            toggleGamepad();
        }
        gamepadButton.setVisibility(gamepadOn ? View.VISIBLE : View.GONE);
        keyboardButton.setVisibility(DesktopPrefs.panelKeyboard(this) ? View.VISIBLE : View.GONE);
        modeButton.setVisibility(DesktopPrefs.panelMode(this) ? View.VISIBLE : View.GONE);
    }

    private void showWaiting() {
        DesktopBridge.setDesktopLive(false);
        if (monitor != null) {
            moveToPhone();
        }
        if (gamepad != null) {
            rootContainer.removeView(gamepad);
            gamepad = null;
        }
        if (controlsBar != null) {
            rootContainer.removeView(controlsBar);
            controlsBar = null;
            modeButton = null;
            keyboardButton = null;
            gamepadButton = null;
            keyboardVisible = false;
        }
        if (desktopView != null) {
            rootContainer.removeView(desktopView);
            desktopView = null;
        }
        waitingView.setVisibility(View.VISIBLE);
    }

    private void showDesktop() {
        waitingView.setVisibility(View.GONE);
        if (desktopView == null) {
            desktopView = new DesktopView(this);
            desktopView.setListener(new DesktopView.Listener() {
                @Override
                public void onConnected(String desktopName, int width, int height) {
                }

                @Override
                public void onDisconnected(IOException failure) {
                    mainHandler.post(() -> renderLauncherState(DesktopLauncher.State.stopped()));
                }
            });

            desktopView.setFrameSizeListener(() -> mainHandler.post(() -> {
                if (controlsBar != null) {
                    controlsBar.setLayoutParams(panelLayoutParams());
                }
            }));
            desktopView.setResizeListener((width, height) -> {
                mainHandler.post(() -> {
                    if (controlsBar != null) {
                        controlsBar.setLayoutParams(panelLayoutParams());
                    }
                });
                executor.execute(() -> {
                    if (launcher != null) {
                        launcher.resize(width, height);
                    }
                });
            });

            String savedMode = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(KEY_INPUT_MODE, "touch");
            TouchTranslator.Mode initialMode = "trackpad".equalsIgnoreCase(savedMode)
                    ? TouchTranslator.Mode.TRACKPAD : TouchTranslator.Mode.TOUCH;
            desktopView.setTouchMode(initialMode);

            rootContainer.addView(desktopView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            controlsBar = new LinearLayout(this);
            controlsBar.setOrientation(LinearLayout.HORIZONTAL);
            controlsBar.setGravity(Gravity.CENTER_VERTICAL);

            android.graphics.drawable.GradientDrawable keyboardBg = new android.graphics.drawable.GradientDrawable();
            keyboardBg.setColor(Color.parseColor("#bb141916"));
            keyboardBg.setCornerRadius(dp(12));
            keyboardBg.setStroke(dp(1), Color.parseColor("#33ffffff"));

            keyboardButton = new Button(this);
            keyboardButton.setText("⌨");
            keyboardButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            keyboardButton.setMinWidth(0);
            keyboardButton.setMinHeight(0);
            keyboardButton.setMinimumWidth(0);
            keyboardButton.setMinimumHeight(0);
            keyboardButton.setPadding(dp(8), dp(3), dp(8), dp(3));
            keyboardButton.setAlpha(0.75f);
            keyboardButton.setTextColor(getColor(R.color.forge_text));
            keyboardButton.setBackground(keyboardBg);
            keyboardButton.setOnClickListener(v -> {
                if (desktopView == null) return;
                keyboardVisible = !keyboardVisible;
                if (keyboardVisible) {
                    desktopView.showKeyboard();
                } else {
                    desktopView.hideKeyboard();
                }
            });

            android.graphics.drawable.GradientDrawable modeBg = new android.graphics.drawable.GradientDrawable();
            modeBg.setColor(Color.parseColor("#bb141916"));
            modeBg.setCornerRadius(dp(12));
            modeBg.setStroke(dp(1), Color.parseColor("#33ffffff"));

            modeButton = new Button(this);
            modeButton.setText(initialMode == TouchTranslator.Mode.TRACKPAD ? "🖱 Мышь" : "👆 Тач");
            modeButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            modeButton.setMinWidth(0);
            modeButton.setMinHeight(0);
            modeButton.setMinimumWidth(0);
            modeButton.setMinimumHeight(0);
            modeButton.setPadding(dp(8), dp(3), dp(8), dp(3));
            modeButton.setAlpha(0.75f);
            modeButton.setTextColor(getColor(R.color.forge_text));
            modeButton.setBackground(modeBg);
            modeButton.setOnClickListener(v -> {
                if (desktopView == null) return;
                TouchTranslator.Mode next = desktopView.getTouchMode() == TouchTranslator.Mode.TRACKPAD
                        ? TouchTranslator.Mode.TOUCH : TouchTranslator.Mode.TRACKPAD;
                desktopView.setTouchMode(next);
                modeButton.setText(next == TouchTranslator.Mode.TRACKPAD ? "🖱 Мышь" : "👆 Тач");
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(KEY_INPUT_MODE, next == TouchTranslator.Mode.TRACKPAD ? "trackpad" : "touch")
                        .apply();
            });

            android.graphics.drawable.GradientDrawable settingsBg = new android.graphics.drawable.GradientDrawable();
            settingsBg.setColor(Color.parseColor("#bb141916"));
            settingsBg.setCornerRadius(dp(12));
            settingsBg.setStroke(dp(1), Color.parseColor("#33ffffff"));

            Button settingsButton = new Button(this);
            settingsButton.setText("⚙");
            settingsButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            settingsButton.setMinWidth(0);
            settingsButton.setMinHeight(0);
            settingsButton.setMinimumWidth(0);
            settingsButton.setMinimumHeight(0);
            settingsButton.setPadding(dp(8), dp(3), dp(8), dp(3));
            settingsButton.setAlpha(0.75f);
            settingsButton.setTextColor(getColor(R.color.forge_text));
            settingsButton.setBackground(settingsBg);
            settingsButton.setOnClickListener(v -> startActivityForResult(
                    new Intent(MainActivity.this, SettingsActivity.class), REQUEST_SETTINGS));

            android.graphics.drawable.GradientDrawable gamepadBg = new android.graphics.drawable.GradientDrawable();
            gamepadBg.setColor(Color.parseColor("#bb141916"));
            gamepadBg.setCornerRadius(dp(12));
            gamepadBg.setStroke(dp(1), Color.parseColor("#33ffffff"));

            gamepadButton = new Button(this);
            gamepadButton.setText("🎮");
            gamepadButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            gamepadButton.setMinWidth(0);
            gamepadButton.setMinHeight(0);
            gamepadButton.setMinimumWidth(0);
            gamepadButton.setMinimumHeight(0);
            gamepadButton.setPadding(dp(8), dp(3), dp(8), dp(3));
            gamepadButton.setAlpha(0.75f);
            gamepadButton.setBackground(gamepadBg);
            gamepadButton.setOnClickListener(v -> toggleGamepad());
            LinearLayout.LayoutParams gamepadParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gamepadParams.setMargins(0, 0, dp(6), 0);
            Button helpButton = new Button(this);
            helpButton.setText("?");
            helpButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            helpButton.setMinWidth(0);
            helpButton.setMinHeight(0);
            helpButton.setMinimumWidth(0);
            helpButton.setMinimumHeight(0);
            helpButton.setPadding(dp(10), dp(3), dp(10), dp(3));
            helpButton.setAlpha(0.75f);
            helpButton.setTextColor(getColor(R.color.forge_text));
            helpButton.setContentDescription(getString(R.string.help_button));
            helpButton.setBackground(gamepadBg.getConstantState().newDrawable().mutate());
            helpButton.setOnClickListener(v -> showHelp());
            LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            helpParams.setMargins(0, 0, dp(6), 0);
            controlsBar.addView(helpButton, helpParams);
            controlsBar.addView(gamepadButton, gamepadParams);

            LinearLayout.LayoutParams keyboardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            keyboardParams.setMargins(0, 0, dp(6), 0);
            controlsBar.addView(keyboardButton, keyboardParams);
            LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            modeParams.setMargins(0, 0, dp(6), 0);
            controlsBar.addView(modeButton, modeParams);
            controlsBar.addView(settingsButton);

            rootContainer.addView(controlsBar, panelLayoutParams());
            applyPanelButtons();

            long frameInterval = DesktopPrefs.economizeScreen(this) ? SCREEN_ECONOMIZE_FRAME_INTERVAL_MS : 0;
            desktopView.start(DesktopLauncher.RFB_PORT, frameInterval);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sound.stop();
        if (pinWatcher != null) {
            pinWatcher.stopWatching();
        }
        displays.unregisterDisplayListener(monitorListener);
        if (monitor != null) {
            moveToPhone();
        }
        if (launcher != null) {
            executor.execute(() -> launcher.stop());
        }
        executor.shutdown();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
