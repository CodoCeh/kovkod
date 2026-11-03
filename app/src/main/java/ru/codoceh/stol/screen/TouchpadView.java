package ru.codoceh.stol.screen;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ru.codoceh.stol.R;

/** Экран телефона, пока стол на мониторе: тачпад и клавиатура для него. */
public class TouchpadView extends FrameLayout {

    private final DesktopView desktop;
    private boolean keyboardShown;

    public TouchpadView(Context context, DesktopView desktop, Runnable onSettings) {
        super(context);
        this.desktop = desktop;
        setBackgroundColor(context.getColor(R.color.win_bg));
        setFocusable(true);
        setFocusableInTouchMode(true);

        LinearLayout hints = new LinearLayout(context);
        hints.setOrientation(LinearLayout.VERTICAL);
        hints.setGravity(Gravity.CENTER);
        hints.addView(text(context.getString(R.string.touchpad_title), 22, R.color.forge_text));
        TextView how = text(context.getString(R.string.touchpad_hint), 14, R.color.forge_muted);
        how.setPadding(0, dp(12), 0, 0);
        hints.addView(how);
        addView(hints, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        Button keyboard = new Button(context);
        keyboard.setText(R.string.touchpad_keyboard);
        keyboard.setAllCaps(false);
        keyboard.setTextColor(context.getColor(R.color.win_on_accent));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(context.getColor(R.color.win_accent));
        shape.setCornerRadius(dp(8));
        keyboard.setBackground(shape);
        keyboard.setStateListAnimator(null);
        keyboard.setPadding(dp(20), 0, dp(20), 0);
        keyboard.setOnClickListener(v -> toggleKeyboard());
        Button settings = new Button(context);
        settings.setText(R.string.touchpad_settings);
        settings.setAllCaps(false);
        settings.setTextColor(context.getColor(R.color.forge_text));
        GradientDrawable plain = new GradientDrawable();
        plain.setColor(context.getColor(R.color.win_control));
        plain.setStroke(dp(1), context.getColor(R.color.win_stroke));
        plain.setCornerRadius(dp(8));
        settings.setBackground(plain);
        settings.setStateListAnimator(null);
        settings.setPadding(dp(20), 0, dp(20), 0);
        settings.setOnClickListener(v -> onSettings.run());

        LinearLayout row = new LinearLayout(context);
        row.addView(keyboard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        second.leftMargin = dp(12);
        row.addView(settings, second);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(24);
        addView(row, params);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            requestFocus();
        }
        return desktop.onTouchEvent(event);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        return desktop.onCreateInputConnection(info);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return desktop.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return desktop.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event);
    }

    private void toggleKeyboard() {
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager == null) {
            return;
        }
        keyboardShown = !keyboardShown;
        if (keyboardShown) {
            requestFocus();
            manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        } else {
            manager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(getContext().getColor(color));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(24), 0, dp(24), 0);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
