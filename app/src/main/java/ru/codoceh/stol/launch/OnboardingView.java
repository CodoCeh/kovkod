package ru.codoceh.stol.launch;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import ru.codoceh.stol.R;

/** Знакомство с КовКодом: карточки «зачем это вам» и «как этим пользоваться». */
public class OnboardingView extends FrameLayout {

    private static final int[][] SLIDES = {
            {R.string.tour_1_title, R.string.tour_1_why, R.string.tour_1_how},
            {R.string.tour_2_title, R.string.tour_2_why, R.string.tour_2_how},
            {R.string.tour_3_title, R.string.tour_3_why, R.string.tour_3_how},
            {R.string.tour_4_title, R.string.tour_4_why, R.string.tour_4_how},
            {R.string.tour_5_title, R.string.tour_5_why, R.string.tour_5_how},
            {R.string.tour_6_title, R.string.tour_6_why, R.string.tour_6_how},
    };
    private static final String[] ICONS = {"🖥", "👆", "🛒", "📱", "📺", "🛟"};

    private final Runnable onDone;
    private final TextView icon;
    private final TextView title;
    private final TextView why;
    private final TextView how;
    private final TextView progress;
    private final Button next;
    private final Button back;
    private int index;
    private float swipeStartX;

    public OnboardingView(Context context, Runnable onDone) {
        super(context);
        this.onDone = onDone;
        setBackgroundColor(context.getColor(R.color.win_bg));
        setClickable(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(28), dp(40), dp(28), dp(24));

        TextView skip = text("", 14, R.color.forge_muted, false);
        skip.setText(R.string.tour_skip);
        skip.setGravity(Gravity.END);
        skip.setPadding(0, dp(8), 0, dp(8));
        skip.setOnClickListener(v -> finish());
        column.addView(skip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        icon = text("", 64, R.color.forge_text, false);
        icon.setPadding(0, dp(24), 0, dp(8));
        column.addView(icon);

        title = text("", 26, R.color.forge_text, true);
        column.addView(title);

        why = text("", 16, R.color.win_accent, false);
        why.setPadding(0, dp(16), 0, 0);
        column.addView(why);

        how = text("", 15, R.color.forge_text, false);
        how.setPadding(0, dp(20), 0, 0);
        how.setGravity(Gravity.START);
        how.setLineSpacing(dp(4), 1f);
        LinearLayout howCard = new LinearLayout(context);
        howCard.setPadding(dp(18), 0, dp(18), dp(20));
        GradientDrawable card = new GradientDrawable();
        card.setColor(context.getColor(R.color.win_card));
        card.setCornerRadius(dp(12));
        howCard.setBackground(card);
        howCard.addView(how, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams howParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        howParams.topMargin = dp(24);
        column.addView(howCard, howParams);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(column);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(28), 0, dp(28), dp(28));
        progress = text("", 13, R.color.forge_muted, false);
        progress.setPadding(0, 0, 0, dp(12));
        bottom.addView(progress);
        LinearLayout buttons = new LinearLayout(context);
        back = button(false);
        back.setText(R.string.tour_back);
        back.setOnClickListener(v -> show(index - 1));
        next = button(true);
        next.setOnClickListener(v -> {
            if (index == SLIDES.length - 1) {
                finish();
            } else {
                show(index + 1);
            }
        });
        buttons.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        nextParams.leftMargin = dp(12);
        buttons.addView(next, nextParams);
        bottom.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(bottom, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        scroll.setPadding(0, 0, 0, dp(120));
        scroll.setClipToPadding(false);

        show(0);
    }

    private void show(int target) {
        index = Math.max(0, Math.min(SLIDES.length - 1, target));
        icon.setText(ICONS[index]);
        title.setText(SLIDES[index][0]);
        why.setText(SLIDES[index][1]);
        how.setText(SLIDES[index][2]);
        back.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(index == SLIDES.length - 1 ? R.string.tour_done : R.string.tour_next);
        progress.setText(getContext().getString(R.string.tour_progress, index + 1, SLIDES.length));
    }

    private void finish() {
        onDone.run();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            swipeStartX = event.getX();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - swipeStartX;
            if (Math.abs(dx) > dp(80)) {
                show(dx < 0 ? index + 1 : index - 1);
                return true;
            }
        }
        return false;
    }

    private Button button(boolean accent) {
        Button button = new Button(getContext());
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTextColor(getContext().getColor(accent ? R.color.win_on_accent : R.color.forge_text));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(getContext().getColor(accent ? R.color.win_accent : R.color.win_control));
        shape.setCornerRadius(dp(10));
        button.setBackground(shape);
        button.setStateListAnimator(null);
        return button;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(getContext().getColor(color));
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
