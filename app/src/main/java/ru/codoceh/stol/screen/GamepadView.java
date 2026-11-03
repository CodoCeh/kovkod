package ru.codoceh.stol.screen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;

/** Экранный геймпад поверх стола: крестовина — стрелки, кнопки — Enter, Esc, пробел, Shift. */
public class GamepadView extends FrameLayout {

    static final int LEFT = 0xff51;
    static final int UP = 0xff52;
    static final int RIGHT = 0xff53;
    static final int DOWN = 0xff54;
    static final int SPACE = 0x20;

    private final DesktopView desktop;

    public GamepadView(Context context, DesktopView desktop) {
        super(context);
        this.desktop = desktop;

        GridLayout pad = new GridLayout(context);
        pad.setColumnCount(3);
        pad.setRowCount(3);
        place(pad, "▲", UP, 0, 1);
        place(pad, "◀", LEFT, 1, 0);
        place(pad, "▶", RIGHT, 1, 2);
        place(pad, "▼", DOWN, 2, 1);
        LayoutParams padParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        padParams.setMargins(dp(24), 0, 0, dp(72));
        addView(pad, padParams);

        GridLayout buttons = new GridLayout(context);
        buttons.setColumnCount(3);
        buttons.setRowCount(3);
        place(buttons, "Y", Keysyms.SHIFT_LEFT, 0, 1);
        place(buttons, "X", SPACE, 1, 0);
        place(buttons, "B", Keysyms.ESCAPE, 1, 2);
        place(buttons, "A", Keysyms.ENTER, 2, 1);
        LayoutParams buttonsParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        buttonsParams.setMargins(0, 0, dp(24), dp(72));
        addView(buttons, buttonsParams);
    }

    private void place(GridLayout grid, String label, int keysym, int row, int column) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor("#66141916"));
        shape.setStroke(dp(2), Color.parseColor("#995fd6a6"));
        button.setBackground(shape);
        button.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setAlpha(0.6f);
                    desktop.sendKey(keysym, true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setAlpha(1f);
                    desktop.sendKey(keysym, false);
                    return true;
                default:
                    return true;
            }
        });
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(row), GridLayout.spec(column));
        params.width = dp(60);
        params.height = dp(60);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        grid.addView(button, params);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
