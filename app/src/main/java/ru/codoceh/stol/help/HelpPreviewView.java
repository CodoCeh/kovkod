package ru.codoceh.stol.help;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

import ru.codoceh.stol.R;

/** Короткая зацикленная анимация к карточке помощи: мини-стол и палец, показывающий действие. */
public class HelpPreviewView extends View {

    private static final int LOOP_MS = 4800;
    private static final int SCREEN = 0xFF101A15;
    private static final int TASKBAR = 0xFF1B201D;
    private static final int WINDOW = 0xFF2B2F2D;
    private static final int LINE = 0xFF454B48;

    private final HelpPreview kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int accent;
    private ValueAnimator loop;
    private float t;

    public HelpPreviewView(Context context, HelpPreview kind) {
        super(context);
        this.kind = kind;
        this.accent = context.getColor(R.color.win_accent);
    }

    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && loop == null) {
            loop = ValueAnimator.ofFloat(0f, 1f);
            loop.setDuration(LOOP_MS);
            loop.setRepeatCount(ValueAnimator.INFINITE);
            loop.setInterpolator(new LinearInterpolator());
            loop.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            loop.start();
        } else if (!visible && loop != null) {
            loop.cancel();
            loop = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float r = dp(12);
        fill(c, 0, 0, w, h, r, SCREEN);
        float bar = h * 0.14f;
        fill(c, 0, h - bar, w, h, 0, TASKBAR);
        float startAlpha = kind == HelpPreview.START ? 0.5f + 0.5f * pulse(t * 3) : 1f;
        fill(c, dp(10), h - bar + bar * 0.25f, dp(10) + bar * 0.5f, h - bar * 0.25f, dp(3), withAlpha(accent, startAlpha));
        float sh = h - bar;
        switch (kind) {
            case START: start(c, w, sh, bar); break;
            case TOUCH: touch(c, w, sh); break;
            case SNAP: snap(c, w, sh); break;
            case PARAMETERS: parameters(c, w, sh); break;
            case STORE: store(c, w, sh); break;
            case EXE: exe(c, w, sh); break;
            case ANDROID: android(c, w, sh); break;
            case SHORTCUT: shortcut(c, w, sh); break;
            case MONITOR: monitor(c, w, sh); break;
            case SOUND: sound(c, w, sh); break;
            case NOTIFY: notifyToast(c, w, sh); break;
            case GAMEPAD: gamepad(c, w, sh); break;
            case BACKUP: backup(c, w, sh); break;
            case TROUBLE: trouble(c, w, sh); break;
            case KEYS: keys(c, w, sh); break;
            case SHARE: share(c, w, sh); break;
            case GPU: gpu(c, w, sh); break;
            case PANEL: panel(c, w, sh); break;
            case VIEW: view(c, w, sh); break;
            case UPDATE: update(c, w, sh); break;
            default: break;
        }
    }

    private void start(Canvas c, float w, float h, float bar) {
        float open = ease(span(t, 0.3f, 0.45f)) * (1 - span(t, 0.85f, 0.95f));
        float top = h - (h * 0.7f) * open;
        if (open > 0) {
            fill(c, dp(8), top, w * 0.42f, h, dp(8), WINDOW);
            for (int i = 0; i < 3; i++) {
                fill(c, dp(18), top + dp(14) + i * dp(22), w * 0.42f - dp(10), top + dp(30) + i * dp(22), dp(4), LINE);
            }
        }
        finger(c, dp(10) + bar * 0.25f, h + bar * 0.5f, span(t, 0.1f, 0.3f) * (1 - span(t, 0.3f, 0.4f)), true);
    }

    private void touch(Canvas c, float w, float h) {
        fill(c, w * 0.2f, h * 0.12f, w * 0.8f, h * 0.88f, dp(8), WINDOW);
        float scroll = span(t, 0.33f, 0.66f);
        for (int i = 0; i < 4; i++) {
            float y = h * 0.22f + i * dp(24) - dp(14) * scroll;
            fill(c, w * 0.25f, y, w * 0.75f, y + dp(12), dp(4), LINE);
        }
        if (t < 0.3f) {
            finger(c, w * 0.5f, h * 0.5f, 1, true);
            ring(c, w * 0.5f, h * 0.5f, span(t, 0.05f, 0.3f));
        } else if (t < 0.66f) {
            float dy = dp(24) * (float) Math.sin(span(t, 0.33f, 0.66f) * Math.PI * 2);
            finger(c, w * 0.45f, h * 0.5f + dy, 1, false);
            finger(c, w * 0.55f, h * 0.5f + dy, 1, false);
        } else {
            float gap = dp(8) + dp(34) * span(t, 0.7f, 0.95f);
            finger(c, w * 0.5f - gap, h * 0.5f + gap * 0.3f, 1, false);
            finger(c, w * 0.5f + gap, h * 0.5f - gap * 0.3f, 1, false);
        }
    }

    private void snap(Canvas c, float w, float h) {
        float[][] keys = {
                {0.25f, 0.2f, 0.75f, 0.8f}, {0.02f, 0.03f, 0.5f, 0.97f},
                {0.5f, 0.03f, 0.98f, 0.97f}, {0.02f, 0.03f, 0.5f, 0.5f}, {0.25f, 0.2f, 0.75f, 0.8f}};
        float p = t * (keys.length - 1);
        int i = Math.min((int) p, keys.length - 2);
        float k = ease(Math.max(0, Math.min(1, (p - i) * 1.6f)));
        float[] a = keys[i];
        float[] b = keys[i + 1];
        fill(c, w * lerp(a[0], b[0], k), h * lerp(a[1], b[1], k), w * lerp(a[2], b[2], k), h * lerp(a[3], b[3], k),
                dp(6), WINDOW);
        stroke(c, w * lerp(a[0], b[0], k), h * lerp(a[1], b[1], k), w * lerp(a[2], b[2], k), h * lerp(a[3], b[3], k), dp(6));
        label(c, "Win + Z", w * (lerp(a[0], b[0], k) + lerp(a[2], b[2], k)) / 2, h * 0.5f, 0xFFFFFFFF, dp(16));
    }

    private void parameters(Canvas c, float w, float h) {
        fill(c, w * 0.12f, h * 0.08f, w * 0.88f, h * 0.92f, dp(8), WINDOW);
        fill(c, w * 0.12f, h * 0.08f, w * 0.34f, h * 0.92f, dp(8), 0xFF242826);
        for (int i = 0; i < 4; i++) {
            fill(c, w * 0.15f, h * 0.16f + i * dp(24), w * 0.31f, h * 0.16f + i * dp(24) + dp(12), dp(4), i == 0 ? accent : LINE);
        }
        for (int i = 0; i < 3; i++) {
            float y = h * 0.2f + i * dp(30);
            fill(c, w * 0.4f, y, w * 0.62f, y + dp(10), dp(4), LINE);
            boolean on = t > 0.4f + i * 0.15f;
            fill(c, w * 0.72f, y - dp(3), w * 0.72f + dp(30), y + dp(14), dp(8), on ? accent : LINE);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(w * 0.72f + dp(on ? 22 : 8), y + dp(5.5f), dp(5), paint);
        }
        label(c, "Win + I", w * 0.8f, h * 0.86f, 0xFFFFFFFF, dp(13));
    }

    private void store(Canvas c, float w, float h) {
        for (int i = 0; i < 3; i++) {
            float x = w * (0.08f + i * 0.31f);
            fill(c, x, h * 0.15f, x + w * 0.27f, h * 0.7f, dp(8), WINDOW);
            fill(c, x + w * 0.08f, h * 0.2f, x + w * 0.19f, h * 0.2f + w * 0.11f, dp(8), i == 0 ? accent : LINE);
        }
        float x = w * 0.08f;
        float fillTo = span(t, 0.3f, 0.7f);
        fill(c, x + dp(6), h * 0.55f, x + w * 0.27f - dp(6), h * 0.55f + dp(12), dp(6), LINE);
        fill(c, x + dp(6), h * 0.55f, x + dp(6) + (w * 0.27f - dp(12)) * fillTo, h * 0.55f + dp(12), dp(6), accent);
        if (t > 0.72f) {
            float pop = ease(span(t, 0.72f, 0.8f));
            fill(c, w * 0.84f, h * 0.76f, w * 0.84f + dp(26) * pop, h * 0.76f + dp(26) * pop, dp(6), accent);
        }
        finger(c, x + w * 0.13f, h * 0.55f + dp(6), span(t, 0.1f, 0.25f) * (1 - span(t, 0.3f, 0.4f)), true);
    }

    private void exe(Canvas c, float w, float h) {
        fill(c, w * 0.1f, h * 0.3f, w * 0.1f + dp(46), h * 0.3f + dp(56), dp(6), WINDOW);
        label(c, ".exe", w * 0.1f + dp(23), h * 0.3f + dp(32), accent, dp(14));
        float arrow = span(t, 0.2f, 0.5f);
        paint.setColor(accent);
        paint.setStrokeWidth(dp(3));
        float ax = w * 0.3f;
        c.drawLine(ax, h * 0.5f, ax + w * 0.2f * arrow, h * 0.5f, paint);
        float open = ease(span(t, 0.55f, 0.7f));
        if (open > 0) {
            float cx = w * 0.75f;
            float cy = h * 0.5f;
            fill(c, cx - w * 0.2f * open, cy - h * 0.3f * open, cx + w * 0.2f * open, cy + h * 0.3f * open, dp(6), WINDOW);
            fill(c, cx - w * 0.2f * open, cy - h * 0.3f * open, cx + w * 0.2f * open, cy - h * 0.3f * open + dp(10) * open, dp(6), accent);
        }
        finger(c, w * 0.1f + dp(23), h * 0.3f + dp(30), span(t, 0.05f, 0.15f) * (1 - span(t, 0.18f, 0.25f)), true);
    }

    private void android(Canvas c, float w, float h) {
        fill(c, w * 0.08f, h * 0.15f, w * 0.52f, h * 0.8f, dp(8), WINDOW);
        float pop = ease(span(t, 0.3f, 0.45f)) * (1 - span(t, 0.9f, 1f));
        float cx = w * 0.7f;
        if (pop > 0) {
            float pw = h * 0.28f * pop;
            float ph = h * 0.46f * pop;
            fill(c, cx - pw, h * 0.5f - ph, cx + pw, h * 0.5f + ph, dp(12), 0xFF222725);
            stroke(c, cx - pw, h * 0.5f - ph, cx + pw, h * 0.5f + ph, dp(12));
            fill(c, cx - pw * 0.7f, h * 0.5f - ph * 0.8f, cx + pw * 0.7f, h * 0.5f - ph * 0.5f, dp(4), accent);
        }
        finger(c, w * 0.15f, h * 0.3f, span(t, 0.1f, 0.25f) * (1 - span(t, 0.3f, 0.35f)), true);
    }

    private void shortcut(Canvas c, float w, float h) {
        float grow = ease(span(t, 0.45f, 0.7f)) * (1 - span(t, 0.92f, 1f));
        for (int i = 0; i < 8; i++) {
            float x = w * 0.2f + (i % 4) * w * 0.15f;
            float y = h * 0.2f + (i / 4) * h * 0.3f;
            fill(c, x, y, x + dp(26), y + dp(26), dp(7), i == 5 ? accent : LINE);
        }
        if (grow > 0) {
            float x = w * 0.2f + 1 * w * 0.15f;
            float y = h * 0.2f + h * 0.3f;
            fill(c, lerp(x, 0, grow), lerp(y, 0, grow), lerp(x + dp(26), w, grow), lerp(y + dp(26), h, grow), dp(7 * (1 - grow)), accent);
        }
        finger(c, w * 0.2f + w * 0.15f + dp(13), h * 0.2f + h * 0.3f + dp(13), span(t, 0.2f, 0.4f) * (1 - span(t, 0.45f, 0.55f)), true);
    }

    private void monitor(Canvas c, float w, float h) {
        fill(c, w * 0.34f, h * 0.1f, w * 0.94f, h * 0.78f, dp(6), WINDOW);
        fill(c, w * 0.36f, h * 0.14f, w * 0.92f, h * 0.74f, dp(4), 0xFF132019);
        fill(c, w * 0.56f, h * 0.78f, w * 0.72f, h * 0.84f, 0, WINDOW);
        float move = (float) Math.sin(t * Math.PI * 2);
        fill(c, w * 0.42f + w * 0.1f * (move + 1) / 2, h * 0.3f, w * 0.62f + w * 0.1f * (move + 1) / 2, h * 0.55f, dp(4), accent);
        fill(c, w * 0.06f, h * 0.2f, w * 0.24f, h * 0.9f, dp(10), 0xFF222725);
        stroke(c, w * 0.06f, h * 0.2f, w * 0.24f, h * 0.9f, dp(10));
        finger(c, w * 0.12f + w * 0.05f * (move + 1) / 2, h * 0.55f, 1, false);
        paint.setColor(accent);
        paint.setStrokeWidth(dp(2));
        c.drawLine(w * 0.25f, h * 0.55f, w * 0.34f, h * 0.45f, paint);
    }

    private void sound(Canvas c, float w, float h) {
        float cx = w * 0.4f;
        float cy = h * 0.5f;
        fill(c, cx - dp(26), cy - dp(12), cx - dp(8), cy + dp(12), dp(3), WINDOW);
        paint.setColor(WINDOW);
        android.graphics.Path cone = new android.graphics.Path();
        cone.moveTo(cx - dp(10), cy - dp(12));
        cone.lineTo(cx + dp(14), cy - dp(28));
        cone.lineTo(cx + dp(14), cy + dp(28));
        cone.lineTo(cx - dp(10), cy + dp(12));
        cone.close();
        paint.setStyle(Paint.Style.FILL);
        c.drawPath(cone, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        for (int i = 0; i < 3; i++) {
            float p = (t * 3 - i * 0.5f) % 1f;
            if (p < 0) {
                p += 1f;
            }
            paint.setColor(withAlpha(accent, 1 - p));
            RectF arc = new RectF(cx + dp(14) - dp(8) * (i + 1) * (0.6f + p), cy - dp(12) * (i + 1) * (0.6f + p),
                    cx + dp(14) + dp(16) * (i + 1) * (0.6f + p), cy + dp(12) * (i + 1) * (0.6f + p));
            c.drawArc(arc, -50, 100, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void notifyToast(Canvas c, float w, float h) {
        float in = ease(span(t, 0.15f, 0.3f)) * (1 - ease(span(t, 0.75f, 0.9f)));
        fill(c, w * 0.1f, h * 0.2f, w * 0.5f, h * 0.8f, dp(8), WINDOW);
        float x = w - (w * 0.4f + dp(10)) * in;
        fill(c, x, dp(10), x + w * 0.4f, dp(10) + h * 0.3f, dp(8), 0xFF2F3532);
        stroke(c, x, dp(10), x + w * 0.4f, dp(10) + h * 0.3f, dp(8));
        fill(c, x + dp(8), dp(18), x + dp(26), dp(36), dp(5), accent);
        fill(c, x + dp(34), dp(20), x + w * 0.36f, dp(26), dp(3), LINE);
        fill(c, x + dp(34), dp(30), x + w * 0.3f, dp(35), dp(3), LINE);
    }

    private void gamepad(Canvas c, float w, float h) {
        float cx = w * 0.25f;
        float cy = h * 0.5f;
        int pressed = (int) (t * 8) % 4;
        float s = dp(15);
        float[][] arms = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            fill(c, cx + arms[i][0] * s * 1.4f - s * 0.6f, cy + arms[i][1] * s * 1.4f - s * 0.6f,
                    cx + arms[i][0] * s * 1.4f + s * 0.6f, cy + arms[i][1] * s * 1.4f + s * 0.6f, dp(5), i == pressed ? accent : WINDOW);
        }
        float bx = w * 0.75f;
        for (int i = 0; i < 4; i++) {
            paint.setColor(i == pressed ? accent : WINDOW);
            c.drawCircle(bx + arms[i][0] * s * 1.3f, cy + arms[i][1] * s * 1.3f, s * 0.6f, paint);
        }
    }

    private void backup(Canvas c, float w, float h) {
        fill(c, w * 0.1f, h * 0.3f, w * 0.1f + dp(48), h * 0.3f + dp(48), dp(8), WINDOW);
        fill(c, w * 0.78f, h * 0.3f, w * 0.78f + dp(48), h * 0.3f + dp(48), dp(8), WINDOW);
        float p = span(t, 0.15f, 0.65f);
        float x = lerp(w * 0.1f + dp(48), w * 0.78f, p);
        paint.setColor(accent);
        c.drawCircle(x, h * 0.3f + dp(24), dp(7), paint);
        paint.setStrokeWidth(dp(2));
        c.drawLine(w * 0.1f + dp(48), h * 0.3f + dp(24), w * 0.78f, h * 0.3f + dp(24), paint);
        if (t > 0.68f) {
            label(c, "✓", w * 0.78f + dp(24), h * 0.3f + dp(32), accent, dp(26));
        }
    }

    private void trouble(Canvas c, float w, float h) {
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float r = dp(30);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setColor(accent);
        float spin = t < 0.7f ? ease(span(t, 0, 0.7f)) * 720 : 0;
        c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), spin - 90, 270, false, paint);
        paint.setStyle(Paint.Style.FILL);
        if (t >= 0.7f) {
            label(c, "✓", cx, cy + dp(10), accent, dp(32));
        }
    }

    private void keys(Canvas c, float w, float h) {
        boolean pressed = t > 0.15f && t < 0.5f || t > 0.65f && t < 0.9f;
        float cx = w * 0.5f;
        fill(c, cx - dp(70), h * 0.55f, cx - dp(10), h * 0.55f + dp(44), dp(8), pressed ? accent : WINDOW);
        fill(c, cx + dp(10), h * 0.55f, cx + dp(70), h * 0.55f + dp(44), dp(8), pressed ? accent : WINDOW);
        label(c, "Win", cx - dp(40), h * 0.55f + dp(28), pressed ? SCREEN : 0xFFFFFFFF, dp(15));
        label(c, "D", cx + dp(40), h * 0.55f + dp(28), pressed ? SCREEN : 0xFFFFFFFF, dp(15));
        float k = ease(span(t, 0.2f, 0.3f)) * (1 - ease(span(t, 0.55f, 0.65f)));
        for (int i = 0; i < 2; i++) {
            float x = w * (0.16f + i * 0.34f);
            float y = h * (0.22f + i * 0.06f);
            fill(c, x, y, x + w * 0.3f, y + h * 0.28f * (1 - k) + dp(1), dp(6), WINDOW);
        }
        label(c, k > 0.5f ? "стол" : "окна", w * 0.5f, h * 0.14f, 0x66FFFFFF, dp(12));
    }

    private void share(Canvas c, float w, float h) {
        fill(c, w * 0.06f, h * 0.15f, w * 0.22f, h * 0.85f, dp(10), 0xFF222725);
        stroke(c, w * 0.06f, h * 0.15f, w * 0.22f, h * 0.85f, dp(10));
        fill(c, w * 0.09f, h * 0.25f, w * 0.19f, h * 0.38f, dp(4), accent);
        fill(c, w * 0.42f, h * 0.15f, w * 0.94f, h * 0.8f, dp(8), WINDOW);
        fill(c, w * 0.42f, h * 0.15f, w * 0.94f, h * 0.15f + dp(12), dp(8), LINE);
        float fly = span(t, 0.2f, 0.55f);
        float x = lerp(w * 0.19f, w * 0.55f, ease(fly));
        float y = lerp(h * 0.3f, h * 0.5f, ease(fly));
        float landed = span(t, 0.55f, 0.7f);
        if (fly < 1f) {
            fill(c, x, y, x + dp(22), y + dp(26), dp(4), accent);
        } else {
            fill(c, w * 0.5f, h * 0.45f, w * 0.5f + dp(22) * ease(landed), h * 0.45f + dp(26) * ease(landed), dp(4), accent);
        }
        finger(c, w * 0.14f, h * 0.32f, span(t, 0.05f, 0.18f) * (1 - span(t, 0.2f, 0.3f)), true);
    }

    private void gpu(Canvas c, float w, float h) {
        float boost = ease(span(t, 0.35f, 0.55f));
        fill(c, w * 0.1f, h * 0.15f, w * 0.9f, h * 0.85f, dp(8), WINDOW);
        int bars = 14;
        for (int i = 0; i < bars; i++) {
            float x = w * 0.14f + i * (w * 0.72f / bars);
            float low = 0.18f + 0.1f * (float) Math.abs(Math.sin(i * 1.7f + t * 20));
            float high = 0.55f + 0.3f * (float) Math.abs(Math.sin(i * 1.3f + t * 20));
            float bh = lerp(low, high, boost) * h * 0.6f;
            fill(c, x, h * 0.78f - bh, x + w * 0.72f / bars - dp(3), h * 0.78f, dp(2), boost > 0.5f ? accent : LINE);
        }
        label(c, boost > 0.5f ? "GPU" : "CPU", w * 0.5f, h * 0.27f, boost > 0.5f ? accent : 0xFFAAAAAA, dp(15));
    }

    private void panel(Canvas c, float w, float h) {
        int corner = (int) (t * 4) % 4;
        float bw = dp(34);
        float bh = dp(18);
        for (int i = 0; i < 3; i++) {
            float x = corner % 2 == 0 ? w - dp(10) - (i + 1) * (bw + dp(5)) : dp(10) + i * (bw + dp(5));
            float y = corner < 2 ? dp(10) : h - dp(10) - bh;
            boolean hidden = i == 0 && t > 0.5f;
            fill(c, x, y, x + bw, y + bh, dp(9), hidden ? 0x22FFFFFF : WINDOW);
            if (!hidden) {
                stroke(c, x, y, x + bw, y + bh, dp(9));
            }
        }
        label(c, "угол · кнопки", w * 0.5f, h * 0.55f, 0x66FFFFFF, dp(13));
    }

    private void view(Canvas c, float w, float h) {
        float k = 0.55f + 0.35f * (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
        float fw = w * 0.5f * k;
        float fh = h * 0.6f * k;
        fill(c, w * 0.5f - fw / 2, h * 0.45f - fh / 2, w * 0.5f + fw / 2, h * 0.45f + fh / 2, dp(6), WINDOW);
        stroke(c, w * 0.5f - fw / 2, h * 0.45f - fh / 2, w * 0.5f + fw / 2, h * 0.45f + fh / 2, dp(6));
        fill(c, w * 0.2f, h * 0.88f, w * 0.8f, h * 0.88f + dp(4), dp(2), LINE);
        paint.setColor(accent);
        c.drawCircle(w * (0.2f + 0.6f * (k - 0.55f) / 0.35f), h * 0.88f + dp(2), dp(7), paint);
    }

    private void update(Canvas c, float w, float h) {
        float p = span(t, 0.15f, 0.7f);
        fill(c, w * 0.3f, h * 0.55f, w * 0.7f, h * 0.55f + dp(12), dp(6), LINE);
        fill(c, w * 0.3f, h * 0.55f, w * 0.3f + w * 0.4f * p, h * 0.55f + dp(12), dp(6), accent);
        float bob = p < 1f ? (float) Math.sin(t * 30) * dp(2) : 0;
        label(c, p < 1f ? "↓" : "✓", w * 0.5f, h * 0.38f + bob, accent, dp(34));
    }

    private void finger(Canvas c, float x, float y, float alpha, boolean tapping) {
        if (alpha <= 0) {
            return;
        }
        paint.setColor(withAlpha(0xFFFFFFFF, 0.35f * alpha));
        c.drawCircle(x, y, dp(14), paint);
        paint.setColor(withAlpha(0xFFFFFFFF, 0.8f * alpha));
        c.drawCircle(x, y, dp(tapping ? 6 : 8), paint);
    }

    private void ring(Canvas c, float x, float y, float p) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(withAlpha(accent, 1 - p));
        c.drawCircle(x, y, dp(14) + dp(26) * p, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void fill(Canvas c, float l, float t, float r, float b, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        box.set(l, t, r, b);
        c.drawRoundRect(box, radius, radius, paint);
    }

    private void stroke(Canvas c, float l, float t, float r, float b, float radius) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(accent);
        box.set(l, t, r, b);
        c.drawRoundRect(box, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void label(Canvas c, String text, float x, float y, int color, float size) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, x, y, paint);
    }

    private static float span(float t, float from, float to) {
        return Math.max(0f, Math.min(1f, (t - from) / (to - from)));
    }

    private static float ease(float x) {
        return x * x * (3 - 2 * x);
    }

    private static float lerp(float a, float b, float k) {
        return a + (b - a) * k;
    }

    private static float pulse(float x) {
        return (float) (0.5 + 0.5 * Math.sin(x * Math.PI * 2));
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
