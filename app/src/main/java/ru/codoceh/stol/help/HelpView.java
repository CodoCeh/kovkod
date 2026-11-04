package ru.codoceh.stol.help;

import android.animation.LayoutTransition;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

import ru.codoceh.stol.R;

/** Помощь: поиск, карточки с анимацией и кнопкой «Попробовать». */
public class HelpView extends FrameLayout {

    private final List<HelpTopic> topics;
    private final Consumer<HelpAction> onTry;
    private final Runnable onClose;
    private final LinearLayout list;
    private final EditText search;
    private String openId;

    public HelpView(Context context, List<HelpTopic> topics, String firstOpenId,
                    Consumer<HelpAction> onTry, Runnable onClose) {
        super(context);
        this.topics = topics;
        this.onTry = onTry;
        this.onClose = onClose;
        this.openId = firstOpenId;
        setBackgroundColor(context.getColor(R.color.win_bg));
        setClickable(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20), dp(24), dp(20), dp(24));

        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(context.getString(R.string.help_title), 26, R.color.forge_text, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = button(context.getString(R.string.help_close), false);
        close.setOnClickListener(v -> close());
        head.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        column.addView(head);

        search = new EditText(context);
        search.setHint(R.string.help_search_hint);
        search.setSingleLine();
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setTextColor(context.getColor(R.color.forge_text));
        search.setHintTextColor(context.getColor(R.color.forge_muted));
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(shape(context.getColor(R.color.win_control), dp(10), context.getColor(R.color.win_stroke)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        searchParams.topMargin = dp(16);
        column.addView(search, searchParams);

        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        LayoutTransition transition = new LayoutTransition();
        transition.enableTransitionType(LayoutTransition.CHANGING);
        list.setLayoutTransition(transition);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listParams.topMargin = dp(16);
        column.addView(list, listParams);

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(column);
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        render();
    }

    private void close() {
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        onClose.run();
    }

    private void render() {
        list.removeAllViews();
        List<HelpTopic> found = HelpSearch.filter(topics, search.getText().toString());
        if (found.isEmpty()) {
            TextView empty = text(getContext().getString(R.string.help_empty), 15, R.color.forge_muted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(32), 0, 0);
            list.addView(empty);
            return;
        }
        for (HelpTopic topic : found) {
            list.addView(card(topic));
        }
    }

    private View card(HelpTopic topic) {
        Context context = getContext();
        boolean open = topic.id.equals(openId);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(shape(context.getColor(R.color.win_card), dp(12), open ? context.getColor(R.color.win_accent) : 0));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);

        card.addView(text(topic.title, 18, R.color.forge_text, true));
        TextView summary = text(topic.summary, 14, R.color.forge_muted, false);
        summary.setPadding(0, dp(4), 0, 0);
        card.addView(summary);

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        HelpPreviewView preview = new HelpPreviewView(context, topic.preview);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170));
        previewParams.topMargin = dp(14);
        body.addView(preview, previewParams);
        TextView steps = text(topic.steps, 15, R.color.forge_text, false);
        steps.setLineSpacing(dp(4), 1f);
        steps.setPadding(0, dp(14), 0, 0);
        body.addView(steps);
        if (topic.action != null) {
            Button action = button(topic.actionLabel, true);
            action.setOnClickListener(v -> onTry.accept(topic.action));
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            actionParams.topMargin = dp(16);
            body.addView(action, actionParams);
        }
        card.addView(body);

        card.setOnClickListener(v -> {
            openId = open ? null : topic.id;
            render();
        });
        return card;
    }

    private Button button(String label, boolean accent) {
        Context context = getContext();
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTextColor(context.getColor(accent ? R.color.win_on_accent : R.color.forge_text));
        button.setBackground(shape(context.getColor(accent ? R.color.win_accent : R.color.win_control), dp(10), 0));
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable shape(int color, int radius, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        if (strokeColor != 0) {
            shape.setStroke(dp(1), strokeColor);
        }
        return shape;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(getContext().getColor(color));
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
