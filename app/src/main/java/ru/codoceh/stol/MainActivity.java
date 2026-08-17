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
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.codoceh.stol.about.AboutActivity;
import ru.codoceh.stol.launch.DesktopLauncher;
import ru.codoceh.stol.launch.LaunchTexts;
import ru.codoceh.stol.runtime.NativeAssets;
import ru.codoceh.stol.runtime.ProotSession;
import ru.codoceh.stol.screen.DesktopView;
import ru.codoceh.stol.setup.InstallFailedException;
import ru.codoceh.stol.setup.RootfsInstaller;
import ru.codoceh.stol.setup.SetupTexts;
import ru.codoceh.stol.setup.SetupWizard;

/**
 * Главный экран приложения: мастер первого запуска, экран ожидания стола с кузницей
 * и показ самого стола через RFB-клиент.
 */
public class MainActivity extends Activity {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ProotSession session;
    private SetupWizard wizard;
    private DesktopLauncher launcher;
    private DesktopView desktopView;

    private FrameLayout rootContainer;
    private View waitingView;
    private TextView titleView;
    private TextView hintView;
    private TextView stepCounterView;
    private ProgressBar progressBar;
    private Button actionButton;
    private Button aboutButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        session = new ProotSession(NativeAssets.nativeDir(this), NativeAssets.rootfsDir(this));

        rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(getColor(R.color.forge_bg));
        setContentView(rootContainer);

        setupInsets(rootContainer);

        waitingView = createWaitingView();
        rootContainer.addView(waitingView);

        if (session.isRootfsReady()) {
            initLauncher();
        } else {
            initWizard();
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

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.setMargins(0, dp(24), 0, 0);
        layout.addView(progressBar, progressParams);

        actionButton = new Button(this);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.setMargins(0, dp(24), 0, 0);
        actionButton.setLayoutParams(btnParams);
        layout.addView(actionButton);

        aboutButton = new Button(this);
        aboutButton.setText(R.string.about_title);
        aboutButton.setOnClickListener(v -> startActivity(new Intent(MainActivity.this, AboutActivity.class)));
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aboutParams.setMargins(0, dp(12), 0, 0);
        aboutButton.setLayoutParams(aboutParams);
        layout.addView(aboutButton);

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
        renderWizardState(wizard.state());
        // Автоматический запуск сборки при открытии
        executor.execute(() -> wizard.build());
    }

    private void renderWizardState(SetupWizard.State state) {
        titleView.setText(SetupTexts.title(state.phase()));
        stepCounterView.setVisibility(View.GONE);

        if (state.phase() == SetupWizard.Phase.FAILED) {
            String cause = getString(SetupTexts.failureCause(state.reason()));
            if (state.reason() == InstallFailedException.Reason.NOT_ENOUGH_SPACE) {
                cause = String.format(cause, state.requiredHuman(), state.availableHuman());
            }
            String action = getString(SetupTexts.failureAction(state.reason()));
            hintView.setText(cause + "\n\n" + action);
            progressBar.setVisibility(View.GONE);
        } else {
            int hintRes = SetupTexts.hint(state.phase());
            hintView.setText(hintRes != 0 ? getString(hintRes) : "");
            progressBar.setVisibility(View.VISIBLE);
            if (state.phase() == SetupWizard.Phase.BUILDING) {
                stepCounterView.setVisibility(View.VISIBLE);
                stepCounterView.setText(String.format(getString(SetupTexts.stepCounter()),
                        state.stage().ordinal() + 1, SetupWizard.TOTAL_STEPS)
                        + ": " + getString(SetupTexts.step(state.stage())));
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
        launcher = new DesktopLauncher(session, state -> mainHandler.post(() -> renderLauncherState(state)));
        renderLauncherState(launcher.state());
        executor.execute(() -> launcher.launch());
    }

    private void renderLauncherState(DesktopLauncher.State state) {
        stepCounterView.setVisibility(View.GONE);

        if (state.phase() == DesktopLauncher.Phase.RUNNING) {
            showDesktop();
            return;
        }

        showWaiting();

        titleView.setText(LaunchTexts.title(state));
        int hint = LaunchTexts.hint(state);
        if (hint != 0) {
            hintView.setText(hint);
        }

        if (state.phase() == DesktopLauncher.Phase.FAILED) {
            progressBar.setVisibility(View.GONE);
            String cause = getString(LaunchTexts.failureCause(state.failure()));
            String action = getString(LaunchTexts.failureAction(state.failure()));
            hintView.setText(cause + "\n\n" + action);
        } else {
            progressBar.setVisibility(View.VISIBLE);
        }

        if (state.action() != null) {
            actionButton.setVisibility(View.VISIBLE);
            actionButton.setText(LaunchTexts.action(state.action()));
            if (state.action() == DesktopLauncher.Action.STOP) {
                actionButton.setOnClickListener(v -> executor.execute(() -> launcher.stop()));
            } else if (state.action() == DesktopLauncher.Action.RETRY) {
                actionButton.setOnClickListener(v -> executor.execute(() -> launcher.launch()));
            }
        } else {
            actionButton.setVisibility(View.GONE);
        }
    }

    private void showWaiting() {
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
            rootContainer.addView(desktopView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            desktopView.start(DesktopLauncher.RFB_PORT);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
