package ru.codoceh.stol.setup;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

/**
 * Кузница — построчный перенос ForgeAnimation.swift из CehoIsland. Взята не «на глаз», а
 * тем же файлом, что уже кует на экране ожидания лаунчера
 * ({@code ru.codoceh.desktoplauncher.ForgeView}): константы, цвета и порядок слоёв не
 * пересчитывались.
 *
 * Все координаты в долях 0..1: канва масштабируется в единичный квадрат
 * (canvas.scale(side, side)), как context.scaleBy(x: side, y: side) в оригинале.
 *
 * <p>От исходного переноса отличается тремя вещами, и все три названы здесь: пакет,
 * {@link #show(SetupWizard.Phase)} — чтобы кузница шла за ходом сборки, а не за отдельной
 * командой экрана, — и ослабленная до пакетной видимость расчётной части, чтобы кадры
 * сверялись тестом с числами канона, а не глазами.
 */
public class ForgeView extends View {

    public enum State { EMPTY, IDLE, FORGING, QUESTION, AWAITING }

    private static final double CYCLE_DURATION = 0.82;
    private static final float IMPACT_X = 0.505f;
    private static final float IMPACT_Y = 0.455f;

    private static final int ACCENT = Color.rgb(46, 140, 102);
    private static final int ACCENT_BRIGHT = Color.rgb(95, 214, 166);
    private static final int AMBER = Color.rgb(224, 168, 74);
    private static final int DARK = Color.rgb(20, 25, 22);
    /** SwiftUI Color.orange — это systemOrange, а не чистый #FF8000. */
    private static final int ORANGE = Color.rgb(255, 149, 0);

    State state = State.FORGING;
    boolean animating = true;

    /** Фаза в СЕКУНДАХ — от неё считаются cycleIndex/cycleTime, как в Swift. */
    double phase;
    private long nextFrameMs;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Rect textBounds = new Rect();
    private final Pose pose = new Pose();

    private final BlurMaskFilter blur075 = new BlurMaskFilter(0.075f, BlurMaskFilter.Blur.NORMAL);
    private final BlurMaskFilter blur024 = new BlurMaskFilter(0.024f, BlurMaskFilter.Blur.NORMAL);
    private final BlurMaskFilter blur020 = new BlurMaskFilter(0.020f, BlurMaskFilter.Blur.NORMAL);
    private final BlurMaskFilter blur013 = new BlurMaskFilter(0.013f, BlurMaskFilter.Blur.NORMAL);
    private final BlurMaskFilter blur008 = new BlurMaskFilter(0.008f, BlurMaskFilter.Blur.NORMAL);
    private final BlurMaskFilter blur005 = new BlurMaskFilter(0.005f, BlurMaskFilter.Blur.NORMAL);

    private final Path anvilSilhouette = new Path();
    private final Path anvilTop = new Path();
    private final Path anvilWaist = new Path();
    private final Path handle = new Path();
    private final Path handleGrain = new Path();
    private final Path head = new Path();
    private final Path band = new Path();
    private final Path headTopFace = new Path();
    private final Path headBotFace = new Path();
    private final Path headLeftEdge = new Path();
    private final Path streakShape = new Path();
    private final Path scratch = new Path();

    public ForgeView(Context context) { super(context); init(); }
    public ForgeView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        // BlurMaskFilter и setShadowLayer для геометрии (свечение, дымка, искры,
        // уголёк, облачко вопроса) аппаратный конвейер honors только начиная со
        // Skia-HWUI, то есть с API 28. На 26–27 без софтверного слоя всё это молча
        // пропадёт; на 28+ софтверный слой не нужен и втрое дороже по хвосту кадра
        // (замер на месте: p90 113 мс против 36 мс).
        setLayerType(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? LAYER_TYPE_NONE
                        : LAYER_TYPE_SOFTWARE,
                null);
        buildStaticPaths();
    }

    /** Работа закончена: молот отставлен, остаётся остывающий металл. */
    public void settle() {
        state = State.IDLE;
        animating = false;
        invalidate();
    }

    public void setState(State next) {
        if (next == null || next == state) return;
        state = next;
        animating = true;
        invalidate();
    }

    /**
     * Кузница идёт за ходом сборки: пока разворачивается образ — кует, когда стол собран —
     * успокаивается. Отказ работой не считается: молот отставлен, металл остывает.
     */
    public void show(SetupWizard.Phase phase) {
        switch (phase) {
            case BUILDING:
                setState(State.FORGING);
                break;
            case NOT_BUILT:
                setState(State.AWAITING);
                break;
            default:
                settle();
                break;
        }
    }

    // MARK: - Кадр

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float side = Math.min(getWidth(), getHeight());
        if (side <= 0) return;

        phase = System.nanoTime() / 1e9;

        int save = canvas.save();
        canvas.translate((getWidth() - side) * 0.5f, (getHeight() - side) * 0.5f);
        canvas.scale(side, side);
        drawScene(canvas);
        canvas.restoreToCount(save);

        scheduleNext();
    }

    /** forging — 30 к/с, спокойные состояния — 12 к/с, как minimumInterval в TimelineView. */
    private void scheduleNext() {
        if (!animating) return;

        long interval = state == State.FORGING ? 1000L / 30L : 1000L / 12L;
        long now = System.currentTimeMillis();

        // Срок следующего кадра считаем от предыдущего СРОКА, а не от факта
        // отрисовки. Иначе кадр, пришедший раньше срока, укорачивает следующий
        // интервал, пара «рано-поздно» зацикливается и частота разгоняется:
        // замер показал 41 к/с вместо заказанных 30.
        nextFrameMs = Math.max(nextFrameMs + interval, now);
        long wait = nextFrameMs - now;

        if (wait <= 0) postInvalidateOnAnimation();
        else postInvalidateDelayed(wait);
    }

    private void drawScene(Canvas c) {
        drawAmbientGlow(c);
        drawHeatHaze(c);
        drawGroundShadow(c);

        float impact = impactEnvelope();
        float squash = state == State.FORGING ? impact * 0.045f : 0f;

        drawAnvil(c, squash);

        switch (state) {
            case FORGING:
                drawImpactEffects(c);
                drawSparks(c);
                drawHammer(c, forgingHammerPose());
                break;

            case AWAITING:
                drawAwaitingPulse(c);
                drawHammer(c, awaitingHammerPose());
                break;

            case QUESTION:
                drawQuestion(c);
                drawHammer(c, restingHammerPose());
                break;

            case IDLE:
                drawIdleEmber(c, 1f);
                drawHammer(c, restingHammerPose());
                break;

            case EMPTY:
                drawIdleEmber(c, 0.28f);
                drawHammer(c, hiddenHammerPose());
                break;
        }
    }

    // MARK: - Время удара

    private long cycleIndex() {
        return (long) Math.floor(phase / CYCLE_DURATION);
    }

    double cycleTime() {
        return positiveModulo(phase, CYCLE_DURATION) / CYCLE_DURATION;
    }

    float impactEnvelope() {
        if (state != State.FORGING) return 0f;

        double t = cycleTime();
        double distance = Math.abs(t - 0.565);
        return (float) Math.exp(-Math.pow(distance / 0.026, 2));
    }

    float flashEnvelope() {
        if (state != State.FORGING) return 0f;

        double age = cycleTime() - 0.565;
        if (age < 0 || age >= 0.18) return 0f;
        return (float) Math.pow(1 - age / 0.18, 2.5);
    }

    // MARK: - Поза молота

    static final class Pose {
        float angle;
        float centerX;
        float centerY;
        float streak;
        float opacity;
    }

    Pose forgingHammerPose() {
        double t = cycleTime();

        if (t < 0.24) {
            // Медленная антиципация назад.
            float p = smoothstep((float) (t / 0.24));
            pose.angle = lerp(-0.50f, -0.78f, p);
            pose.centerX = lerp(0.38f, 0.29f, p);
            pose.centerY = lerp(0.235f, 0.145f, p);
            pose.streak = 0f;

        } else if (t < 0.565) {
            // Ускоряющийся тяжёлый ход вниз.
            float raw = (float) ((t - 0.24) / 0.325);
            float p = (float) Math.pow(raw, 2.75);
            pose.angle = lerp(-0.78f, 0.055f, p);
            pose.centerX = cubic(p, 0.29f, 0.41f, 0.53f, 0.505f);
            pose.centerY = cubic(p, 0.145f, 0.11f, 0.26f, 0.405f);
            pose.streak = smoothstep(remap(raw, 0.45f, 1.0f));

        } else if (t < 0.66) {
            // Резкий отскок после контакта.
            float raw = (float) ((t - 0.565) / 0.095);
            float p = easeOutBack(raw);
            pose.angle = lerp(0.055f, -0.31f, p);
            pose.centerX = lerp(0.505f, 0.445f, p);
            pose.centerY = lerp(0.405f, 0.285f, p);
            pose.streak = (1 - raw) * 0.36f;

        } else {
            // Плавный возврат в исходную позицию.
            float raw = (float) ((t - 0.66) / 0.34);
            float p = smootherstep(raw);
            pose.angle = lerp(-0.31f, -0.50f, p);
            pose.centerX = lerp(0.445f, 0.38f, p);
            pose.centerY = lerp(0.285f, 0.235f, p);
            pose.streak = 0f;
        }

        pose.opacity = 1f;
        return pose;
    }

    private Pose awaitingHammerPose() {
        float breathe = (float) Math.sin(phase * 2.1) * 0.006f;

        pose.angle = -0.72f;
        pose.centerX = 0.305f;
        pose.centerY = 0.155f + breathe;
        pose.streak = 0f;
        pose.opacity = 1f;
        return pose;
    }

    private Pose restingHammerPose() {
        float breathe = (float) Math.sin(phase * 1.35) * 0.003f;

        pose.angle = -0.48f;
        pose.centerX = 0.255f;
        pose.centerY = 0.22f + breathe;
        pose.streak = 0f;
        pose.opacity = 0.9f;
        return pose;
    }

    private Pose hiddenHammerPose() {
        pose.angle = -0.58f;
        pose.centerX = 0.12f;
        pose.centerY = 0.15f;
        pose.streak = 0f;
        pose.opacity = 0.26f;
        return pose;
    }

    // MARK: - Фон и атмосфера

    private void drawAmbientGlow(Canvas c) {
        float pulse = (float) (0.5 + 0.5 * Math.sin(phase * 1.7));

        int glowColor;
        float baseOpacity;

        switch (state) {
            case FORGING:
                glowColor = ACCENT_BRIGHT;
                baseOpacity = 0.16f + impactEnvelope() * 0.24f;
                break;

            case IDLE:
                glowColor = ACCENT;
                baseOpacity = 0.075f + pulse * 0.028f;
                break;

            case EMPTY:
                glowColor = ACCENT;
                baseOpacity = 0.025f + pulse * 0.008f;
                break;

            default:
                glowColor = AMBER;
                baseOpacity = 0.09f + pulse * 0.04f;
                break;
        }

        rect.set(0.25f, 0.29f, 0.25f + 0.52f, 0.29f + 0.43f);

        Paint p = fill();
        p.setMaskFilter(blur075);
        p.setShader(new RadialGradient(
                rect.centerX(), rect.centerY(), 0.26f,
                new int[] {
                        mul(glowColor, baseOpacity),
                        mul(glowColor, baseOpacity * 0.3f),
                        mul(glowColor, 0f)
                },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));

        c.drawOval(rect, p);
    }

    private void drawGroundShadow(Canvas c) {
        rect.set(0.23f, 0.775f, 0.23f + 0.57f, 0.775f + 0.09f);

        Paint p = fill();
        p.setMaskFilter(blur024);
        p.setColor(mul(DARK, 0.78f));
        c.drawOval(rect, p);
    }

    private void drawHeatHaze(Canvas c) {
        if (state != State.FORGING && state != State.IDLE) return;

        float strength = state == State.FORGING ? 1f : 0.25f;

        int layer = c.saveLayerAlpha(null, alpha255(0.14f * strength));

        for (int index = 0; index < 3; index++) {
            float seed = noise(index, 11);
            float drift = (float) Math.sin(phase * (0.72 + seed * 0.22) + seed * 8);

            scratch.reset();
            scratch.moveTo(0.45f + index * 0.055f, 0.42f);
            scratch.cubicTo(
                    0.42f + index * 0.065f + drift * 0.012f, 0.37f,
                    0.49f + index * 0.045f - drift * 0.012f, 0.31f,
                    0.44f + index * 0.06f + drift * 0.018f, 0.255f);

            Paint p = stroke(0.007f, Paint.Cap.ROUND, Paint.Join.MITER);
            p.setMaskFilter(blur008);
            p.setShader(new LinearGradient(
                    0.5f, 0.44f, 0.5f, 0.24f,
                    new int[] {
                            mul(ACCENT_BRIGHT, 0.04f),
                            mul(ACCENT_BRIGHT, 0.34f),
                            mul(ACCENT_BRIGHT, 0f)
                    },
                    new float[] { 0f, 0.5f, 1f },
                    Shader.TileMode.CLAMP));

            c.drawPath(scratch, p);
        }

        c.restoreToCount(layer);
    }

    // MARK: - Наковальня

    private void drawAnvil(Canvas c, float squash) {
        float verticalScale = 1 - squash;
        float verticalOffset = IMPACT_Y * (1 - verticalScale);

        int save = c.save();
        c.translate(0, verticalOffset);
        c.scale(1 + squash * 0.16f, verticalScale);

        Paint p = fill();
        p.setShader(new LinearGradient(
                0.43f, 0.38f, 0.62f, 0.81f,
                new int[] {
                        mul(ACCENT_BRIGHT, 0.93f),
                        ACCENT,
                        mul(ACCENT, 0.96f),
                        mul(ACCENT, 0.66f)
                },
                new float[] { 0f, 0.18f, 0.58f, 1f },
                Shader.TileMode.CLAMP));
        c.drawPath(anvilSilhouette, p);

        p = stroke(0.009f, Paint.Cap.BUTT, Paint.Join.ROUND);
        p.setColor(mul(ACCENT_BRIGHT, 0.28f));
        c.drawPath(anvilSilhouette, p);

        drawAnvilHighlights(c);

        c.restoreToCount(save);
    }

    private void drawAnvilHighlights(Canvas c) {
        Paint p = stroke(0.012f, Paint.Cap.ROUND, Paint.Join.MITER);
        p.setShader(new LinearGradient(
                0.2f, 0.455f, 0.68f, 0.455f,
                new int[] {
                        mul(ACCENT_BRIGHT, 0.2f),
                        mul(ACCENT_BRIGHT, 0.92f),
                        mul(ACCENT_BRIGHT, 0.34f)
                },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
        c.drawPath(anvilTop, p);

        p = stroke(0.01f, Paint.Cap.ROUND, Paint.Join.MITER);
        p.setColor(mul(ACCENT_BRIGHT, 0.22f));
        c.drawPath(anvilWaist, p);
    }

    // MARK: - Молот

    private void drawHammer(Canvas c, Pose hammer) {
        float angle = hammer.angle;
        float centerX = hammer.centerX;
        float centerY = hammer.centerY;
        float opacity = hammer.opacity;

        if (hammer.streak > 0.01f) {
            drawHammerStreak(c, hammer);
        }

        int save = opacity >= 0.999f
                ? c.save()
                : c.saveLayerAlpha(null, alpha255(opacity));

        c.translate(centerX, centerY);
        c.rotate((float) Math.toDegrees(angle));

        drawHammerHandle(c);
        drawHammerHead(c);

        c.restoreToCount(save);
    }

    private void drawHammerHandle(Canvas c) {
        Paint p = fill();
        p.setShader(new LinearGradient(
                0f, -0.02f, 0f, 0.035f,
                new int[] {
                        rgb(0.27f, 0.16f, 0.09f),
                        rgb(0.56f, 0.33f, 0.16f),
                        rgb(0.34f, 0.19f, 0.1f)
                },
                new float[] { 0f, 0.45f, 1f },
                Shader.TileMode.CLAMP));
        c.drawPath(handle, p);

        p = stroke(0.006f, Paint.Cap.ROUND, Paint.Join.MITER);
        p.setColor(mul(Color.WHITE, 0.16f));
        c.drawPath(handleGrain, p);
    }

    private void drawHammerHead(Canvas c) {
        final float hw = 0.115f;
        final float hh = 0.165f;
        final float ch = 0.036f;

        Paint p = fill();
        p.setShader(new LinearGradient(
                -hw, -hh, hw, hh,
                new int[] {
                        rgb(0.74f, 0.77f, 0.79f),
                        rgb(0.50f, 0.54f, 0.56f),
                        rgb(0.26f, 0.29f, 0.31f),
                        rgb(0.11f, 0.13f, 0.14f)
                },
                new float[] { 0f, 0.32f, 0.72f, 1f },
                Shader.TileMode.CLAMP));
        c.drawPath(head, p);

        p = stroke(0.006f, Paint.Cap.BUTT, Paint.Join.ROUND);
        p.setColor(mul(ACCENT_BRIGHT, 0.55f));
        c.drawPath(head, p);

        p = fill();
        p.setShader(new LinearGradient(
                0f, -0.05f, 0f, 0.05f,
                new int[] {
                        rgb(0.62f, 0.65f, 0.67f),
                        rgb(0.34f, 0.37f, 0.39f),
                        rgb(0.16f, 0.18f, 0.19f)
                },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
        c.drawPath(band, p);

        p = stroke(0.005f, Paint.Cap.BUTT, Paint.Join.MITER);
        p.setColor(mul(Color.BLACK, 0.30f));
        c.drawPath(band, p);

        for (int i = 0; i < 2; i++) {
            float ry = i == 0 ? -0.028f : 0.028f;

            rect.set(-0.012f, ry - 0.009f, -0.012f + 0.018f, ry - 0.009f + 0.018f);

            p = fill();
            p.setShader(new RadialGradient(
                    -0.006f, ry - 0.003f, 0.012f,
                    mul(Color.WHITE, 0.85f), rgb(0.4f, 0.42f, 0.44f),
                    Shader.TileMode.CLAMP));
            c.drawOval(rect, p);
        }

        p = stroke(0.01f, Paint.Cap.ROUND, Paint.Join.MITER);
        p.setShader(new LinearGradient(
                -hw, 0f, hw, 0f,
                new int[] {
                        mul(Color.WHITE, 0.2f),
                        mul(Color.WHITE, 0.8f),
                        mul(Color.WHITE, 0.2f)
                },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
        c.drawPath(headTopFace, p);

        p = stroke(0.008f, Paint.Cap.ROUND, Paint.Join.MITER);
        p.setShader(new LinearGradient(
                -hw, 0f, hw, 0f,
                new int[] {
                        mul(ACCENT_BRIGHT, 0.15f),
                        mul(ACCENT_BRIGHT, 0.55f),
                        mul(ACCENT_BRIGHT, 0.15f)
                },
                new float[] { 0f, 0.5f, 1f },
                Shader.TileMode.CLAMP));
        c.drawPath(headBotFace, p);

        p = stroke(0.006f, Paint.Cap.ROUND, Paint.Join.MITER);
        p.setColor(mul(Color.WHITE, 0.30f));
        c.drawPath(headLeftEdge, p);
    }

    private void drawHammerStreak(Canvas c, Pose hammer) {
        int outer = c.saveLayerAlpha(null, alpha255(hammer.streak * 0.38f));

        for (int index = 0; index < 3; index++) {
            float offset = (index + 1) * 0.038f;

            int ghost = c.saveLayerAlpha(null, alpha255(1 - index * 0.27f));
            c.translate(
                    hammer.centerX - offset * 0.25f,
                    hammer.centerY - offset);
            c.rotate((float) Math.toDegrees(hammer.angle - offset * 0.55f));

            Paint p = fill();
            p.setMaskFilter(blur013);
            p.setShader(new LinearGradient(
                    -0.1f, 0f, 0.16f, 0f,
                    new int[] {
                            mul(ACCENT_BRIGHT, 0.02f),
                            mul(ACCENT_BRIGHT, 0.5f),
                            mul(ACCENT_BRIGHT, 0f)
                    },
                    new float[] { 0f, 0.5f, 1f },
                    Shader.TileMode.CLAMP));
            c.drawPath(streakShape, p);

            c.restoreToCount(ghost);
        }

        c.restoreToCount(outer);
    }

    // MARK: - Контакт и ударная волна

    private void drawImpactEffects(Canvas c) {
        float flash = flashEnvelope();
        if (flash <= 0.001f) return;

        float radius = 0.018f + flash * 0.055f;
        rect.set(
                IMPACT_X - radius,
                IMPACT_Y - radius * 0.55f,
                IMPACT_X + radius,
                IMPACT_Y - radius * 0.55f + radius * 1.1f);

        int gold = rgb(1f, 0.86f, 0.34f);
        int fire = rgb(1f, 0.32f, 0.04f);

        Paint p = fill();
        p.setMaskFilter(blur020);
        p.setShader(new RadialGradient(
                IMPACT_X, IMPACT_Y, radius,
                new int[] {
                        mul(Color.WHITE, flash),
                        mul(gold, flash * 0.92f),
                        mul(fire, flash * 0.5f),
                        mul(fire, 0f)
                },
                new float[] { 0f, 0.24f, 0.58f, 1f },
                Shader.TileMode.CLAMP));
        c.drawOval(rect, p);

        float age = (float) Math.max(0, cycleTime() - 0.565);
        final float waveLife = 0.17f;

        if (age < waveLife) {
            float t = age / waveLife;
            float ringRadius = 0.045f + t * 0.145f;
            float opacity = (float) Math.pow(1 - t, 2);

            rect.set(
                    IMPACT_X - ringRadius,
                    IMPACT_Y - ringRadius * 0.36f,
                    IMPACT_X + ringRadius,
                    IMPACT_Y - ringRadius * 0.36f + ringRadius * 0.72f);

            p = stroke(0.009f * (1 - t * 0.6f), Paint.Cap.ROUND, Paint.Join.MITER);
            p.setShader(new LinearGradient(
                    IMPACT_X - ringRadius, IMPACT_Y, IMPACT_X + ringRadius, IMPACT_Y,
                    new int[] {
                            mul(Color.WHITE, 0f),
                            mul(Color.WHITE, opacity * 0.8f),
                            mul(ACCENT_BRIGHT, opacity * 0.5f),
                            mul(ACCENT_BRIGHT, 0f)
                    },
                    new float[] { 0f, 1f / 3f, 2f / 3f, 1f },
                    Shader.TileMode.CLAMP));
            c.drawOval(rect, p);
        }
    }

    // MARK: - Искры

    private void drawSparks(Canvas c) {
        long currentCycle = cycleIndex();

        for (int cycleOffset = 0; cycleOffset <= 1; cycleOffset++) {
            long sourceCycle = currentCycle - cycleOffset;
            double localAge = phase
                    - sourceCycle * CYCLE_DURATION
                    - CYCLE_DURATION * 0.565;

            if (localAge < 0 || localAge >= 1.12) continue;

            drawSparkBurst(c, (float) localAge, sourceCycle);
        }
    }

    private void drawSparkBurst(Canvas c, float age, long burstSeed) {
        final int particleCount = 30;

        for (int index = 0; index < particleCount; index++) {
            float seedA = noise(index, burstSeed * 7 + 3);
            float seedB = noise(index, burstSeed * 11 + 17);
            float seedC = noise(index, burstSeed * 13 + 29);
            float seedD = noise(index, burstSeed * 19 + 41);

            boolean longEmber = index == 3 || index == 17;
            float life = longEmber
                    ? 0.92f + seedC * 0.18f
                    : 0.32f + seedC * 0.38f;

            if (age >= life) continue;

            float normalizedAge = age / life;

            float directionSign = seedA < 0.48f ? -1f : 1f;
            float horizontalSpeed =
                    directionSign
                            * (0.18f + seedB * 0.47f)
                            * (longEmber ? 0.58f : 1f);

            float verticalSpeed =
                    -(0.37f + seedA * 0.58f)
                            * (longEmber ? 0.72f : 1f);

            float drag = longEmber ? 1.05f : 1.65f;
            float gravity = longEmber ? 0.57f : 0.93f;

            float dragFactor = (float) ((1 - Math.exp(-drag * age)) / drag);

            float drift = longEmber
                    ? (float) Math.sin(age * (3.2 + seedD * 2.4) + seedB * 9) * 0.026f * normalizedAge
                    : 0f;

            float x = IMPACT_X + horizontalSpeed * dragFactor + drift;
            float y = IMPACT_Y + verticalSpeed * dragFactor + gravity * age * age * 0.5f;

            float previousAge = Math.max(0, age - (longEmber ? 0.07f : 0.045f));
            float previousDrag = (float) ((1 - Math.exp(-drag * previousAge)) / drag);

            float previousDrift = longEmber
                    ? (float) Math.sin(previousAge * (3.2 + seedD * 2.4) + seedB * 9)
                            * 0.026f * (previousAge / life)
                    : 0f;

            float previousX = IMPACT_X + horizontalSpeed * previousDrag + previousDrift;
            float previousY = IMPACT_Y + verticalSpeed * previousDrag
                    + gravity * previousAge * previousAge * 0.5f;

            float flicker = 0.74f
                    + 0.26f * (float) Math.sin(phase * (18 + seedB * 24) + seedD * 20);

            float fade = (float) Math.pow(1 - normalizedAge, longEmber ? 1.2 : 1.5);
            float opacity = Math.max(0, fade * flicker);
            float radius = (longEmber ? 0.020f : 0.013f)
                    + seedD * (longEmber ? 0.016f : 0.012f);

            int color = sparkColor(normalizedAge, opacity);

            scratch.reset();
            scratch.moveTo(previousX, previousY);
            scratch.lineTo(x, y);

            Paint p = stroke(radius * 0.72f, Paint.Cap.ROUND, Paint.Join.MITER);
            p.setShadowLayer(radius * 2.6f, 0f, 0f, mul(color, opacity * 0.78f));
            p.setShader(new LinearGradient(
                    previousX, previousY, x, y,
                    new int[] {
                            mul(color, 0.02f),
                            mul(color, opacity * 0.72f),
                            mul(Color.WHITE, opacity)
                    },
                    new float[] { 0f, 0.5f, 1f },
                    Shader.TileMode.CLAMP));
            c.drawPath(scratch, p);

            rect.set(x - radius, y - radius, x + radius, y + radius);

            p = fill();
            p.setShadowLayer(radius * 2.6f, 0f, 0f, mul(color, opacity * 0.78f));
            p.setShader(new RadialGradient(
                    x, y, radius,
                    new int[] {
                            mul(Color.WHITE, opacity),
                            mul(color, opacity),
                            mul(color, 0f)
                    },
                    new float[] { 0f, 0.5f, 1f },
                    Shader.TileMode.CLAMP));
            c.drawOval(rect, p);
        }
    }

    private static final float[] SPARK_HOT = { 1.0f, 0.98f, 0.82f };
    private static final float[] SPARK_GOLD = { 1.0f, 0.69f, 0.14f };
    private static final float[] SPARK_ORANGE = { 1.0f, 0.25f, 0.035f };
    private static final float[] SPARK_EMBER = { 0.47f, 0.035f, 0.018f };

    /** Остывание уголька: бело-раскалённый → золото → оранж → багрово. */
    private static int sparkColor(float temperature, float brightness) {
        float[] hot = SPARK_HOT;
        float[] gold = SPARK_GOLD;
        float[] orange = SPARK_ORANGE;
        float[] ember = SPARK_EMBER;

        float[] a;
        float[] b;
        float t;

        if (temperature < 0.18f) {
            a = hot; b = gold; t = temperature / 0.18f;
        } else if (temperature < 0.58f) {
            a = gold; b = orange; t = (temperature - 0.18f) / 0.4f;
        } else {
            a = orange; b = ember; t = (temperature - 0.58f) / 0.42f;
        }

        float u = clamp01(t);
        return Color.argb(
                Math.round(clamp01(brightness) * 255f),
                channel(a[0] + (b[0] - a[0]) * u),
                channel(a[1] + (b[1] - a[1]) * u),
                channel(a[2] + (b[2] - a[2]) * u));
    }

    // MARK: - Спокойные состояния

    private void drawIdleEmber(Canvas c, float strength) {
        double emberCycle = positiveModulo(phase, 4.7);
        if (emberCycle <= 3.72) return;

        float age = (float) (emberCycle - 3.72);
        final float life = 0.82f;
        if (age >= life) return;

        float t = age / life;
        float seed = noise((long) Math.floor(phase / 4.7), 91);

        float x = IMPACT_X
                + (seed - 0.5f) * 0.14f
                + (float) Math.sin(age * 4.2 + seed * 7) * 0.014f;

        float y = IMPACT_Y
                - age * (0.16f + seed * 0.08f)
                + age * age * 0.08f;

        float radius = 0.005f + seed * 0.004f;
        float opacity = (float) Math.pow(1 - t, 1.7) * strength;

        rect.set(x - radius, y - radius, x + radius, y + radius);

        Paint p = fill();
        p.setShadowLayer(0.018f, 0f, 0f, mul(ORANGE, opacity));
        p.setColor(mul(rgb(1f, 0.48f, 0.08f), opacity));
        c.drawOval(rect, p);
    }

    private void drawQuestion(Canvas c) {
        float bob = (float) Math.sin(phase * 2.4) * 0.02f;
        float pulse = (float) (0.5 + 0.5 * Math.sin(phase * 3.4));
        final float cx = 0.52f;
        float cy = 0.2f + bob;

        // Амбер-облачко над наковальней с хвостиком вниз — «агент спрашивает».
        scratch.reset();
        rect.set(cx - 0.11f, cy - 0.12f, cx - 0.11f + 0.22f, cy - 0.12f + 0.2f);
        scratch.addRoundRect(rect, 0.06f, 0.06f, Path.Direction.CW);
        scratch.moveTo(cx - 0.03f, cy + 0.075f);
        scratch.lineTo(cx - 0.005f, cy + 0.13f);
        scratch.lineTo(cx + 0.045f, cy + 0.07f);
        scratch.close();

        Paint p = fill();
        p.setShadowLayer(0.03f, 0f, 0f, mul(AMBER, 0.55f + pulse * 0.3f));
        p.setShader(new LinearGradient(
                cx, cy - 0.12f, cx, cy + 0.1f,
                AMBER, rgb(0.82f, 0.55f, 0.16f),
                Shader.TileMode.CLAMP));
        c.drawPath(scratch, p);

        // «?» крупным кеглем в уменьшенном подслое → чёткий глиф
        // (иначе 0.2 pt растеризуется в ноль).
        int save = c.save();
        c.translate(cx, cy - 0.012f);
        final float k = 0.006f;
        c.scale(k, k);

        p = fill();
        p.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        p.setTextSize(26f);
        p.setColor(rgb(0.12f, 0.09f, 0.03f));
        p.getTextBounds("?", 0, 1, textBounds);
        c.drawText("?", 0, 1, -textBounds.exactCenterX(), -textBounds.exactCenterY(), p);

        c.restoreToCount(save);
    }

    private void drawAwaitingPulse(Canvas c) {
        float pulse = (float) (0.5 + 0.5 * Math.sin(phase * 3.1));
        float radius = 0.23f + pulse * 0.025f;
        float opacity = 0.18f + pulse * 0.34f;

        rect.set(IMPACT_X - radius, 0.41f, IMPACT_X + radius, 0.41f + 0.38f);

        Paint p = stroke(0.011f + pulse * 0.006f, Paint.Cap.ROUND, Paint.Join.MITER);
        p.setMaskFilter(blur005);
        p.setShader(new LinearGradient(
                0.27f, 0.6f, 0.75f, 0.6f,
                new int[] {
                        mul(AMBER, 0f),
                        mul(AMBER, opacity),
                        mul(AMBER, opacity * 0.74f),
                        mul(AMBER, 0f)
                },
                new float[] { 0f, 1f / 3f, 2f / 3f, 1f },
                Shader.TileMode.CLAMP));
        c.drawOval(rect, p);
    }

    // MARK: - Статичная геометрия

    private void buildStaticPaths() {
        // Плоская верхняя грань: пятка, рабочая плита и основание рога.
        anvilSilhouette.moveTo(0.185f, 0.455f);
        anvilSilhouette.quadTo(0.185f, 0.442f, 0.198f, 0.442f);
        anvilSilhouette.lineTo(0.655f, 0.442f);

        // Классический заострённый рог.
        anvilSilhouette.cubicTo(0.735f, 0.442f, 0.815f, 0.451f, 0.865f, 0.468f);
        anvilSilhouette.cubicTo(0.815f, 0.48f, 0.735f, 0.498f, 0.675f, 0.505f);

        // Резкая ступень под рогом к шейке.
        anvilSilhouette.lineTo(0.62f, 0.532f);
        anvilSilhouette.lineTo(0.603f, 0.585f);

        // Узкая правая сторона талии.
        anvilSilhouette.cubicTo(0.596f, 0.617f, 0.601f, 0.645f, 0.615f, 0.665f);

        // Расширение к широкому основанию.
        anvilSilhouette.cubicTo(0.64f, 0.692f, 0.675f, 0.718f, 0.705f, 0.733f);
        anvilSilhouette.lineTo(0.748f, 0.772f);
        anvilSilhouette.quadTo(0.752f, 0.795f, 0.73f, 0.802f);

        // Плоский низ основания.
        anvilSilhouette.lineTo(0.275f, 0.802f);
        anvilSilhouette.quadTo(0.253f, 0.795f, 0.257f, 0.772f);
        anvilSilhouette.lineTo(0.3f, 0.733f);

        // Левое расширение основания к талии.
        anvilSilhouette.cubicTo(0.33f, 0.718f, 0.365f, 0.692f, 0.39f, 0.665f);

        // Узкая левая сторона талии.
        anvilSilhouette.cubicTo(0.404f, 0.645f, 0.409f, 0.617f, 0.402f, 0.585f);

        // Резкая ступень под рабочей плитой.
        anvilSilhouette.lineTo(0.382f, 0.532f);
        anvilSilhouette.lineTo(0.325f, 0.505f);

        // Небольшая прямоугольная пятка слева.
        anvilSilhouette.lineTo(0.198f, 0.505f);
        anvilSilhouette.quadTo(0.185f, 0.505f, 0.185f, 0.492f);
        anvilSilhouette.close();

        anvilTop.moveTo(0.205f, 0.455f);
        anvilTop.lineTo(0.672f, 0.455f);

        anvilWaist.moveTo(0.398f, 0.575f);
        anvilWaist.cubicTo(0.401f, 0.628f, 0.38f, 0.683f, 0.35f, 0.719f);

        // Короче и толще — пропорция кузнечной рукояти под тяжёлую головку.
        handle.moveTo(-0.28f, -0.028f);
        handle.lineTo(0.012f, -0.02f);
        handle.cubicTo(0.036f, -0.018f, 0.05f, -0.004f, 0.055f, 0.011f);
        handle.cubicTo(0.05f, 0.026f, 0.036f, 0.04f, 0.012f, 0.042f);
        handle.lineTo(-0.28f, 0.035f);
        handle.close();

        handleGrain.moveTo(-0.34f, 0.001f);
        handleGrain.cubicTo(-0.24f, -0.008f, -0.13f, 0.014f, -0.03f, 0.005f);

        // Молот Тора (Мьёльнир): массивный прямоугольный стальной блок
        // с фасками по углам. Рукоять входит в центр левой грани,
        // ударные плоскости — верхняя и нижняя.
        final float hw = 0.115f;   // половина ширины (по оси рукояти)
        final float hh = 0.165f;   // половина высоты (блок выше — читается как молот)
        final float ch = 0.036f;   // фаска

        head.moveTo(-hw, -hh + ch);
        head.lineTo(-hw + ch, -hh);
        head.lineTo(hw - ch, -hh);
        head.lineTo(hw, -hh + ch);
        head.lineTo(hw, hh - ch);
        head.lineTo(hw - ch, hh);
        head.lineTo(-hw + ch, hh);
        head.lineTo(-hw, hh - ch);
        head.close();

        // Поперечная стальная обвязка в центре — куда крепится рукоять.
        RectF bandRect = new RectF(-hw - 0.006f, -0.05f, -hw - 0.006f + hw * 2 + 0.012f, 0.05f);
        band.addRoundRect(bandRect, 0.014f, 0.014f, Path.Direction.CW);

        headTopFace.moveTo(-hw + ch, -hh + 0.006f);
        headTopFace.lineTo(hw - ch, -hh + 0.006f);

        headBotFace.moveTo(-hw + ch, hh - 0.006f);
        headBotFace.lineTo(hw - ch, hh - 0.006f);

        headLeftEdge.moveTo(-hw + 0.006f, -hh + ch);
        headLeftEdge.lineTo(-hw + 0.006f, hh - ch);

        RectF streakRect = new RectF(-0.095f, -0.085f, -0.095f + 0.245f, -0.085f + 0.155f);
        streakShape.addRoundRect(streakRect, 0.025f, 0.025f, Path.Direction.CW);
    }

    // MARK: - Кисти

    private Paint fill() {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    private Paint stroke(float width, Paint.Cap cap, Paint.Join join) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(cap);
        paint.setStrokeJoin(join);
        return paint;
    }

    // MARK: - Математика

    private static double positiveModulo(double value, double modulus) {
        double result = value % modulus;
        return result >= 0 ? result : result + modulus;
    }

    private static float noise(long x, long y) {
        double value = Math.sin(x * 127.1 + y * 311.7) * 43_758.545_312_3;
        return (float) (value - Math.floor(value));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smoothstep(float value) {
        float t = clamp01(value);
        return t * t * (3 - 2 * t);
    }

    private static float smootherstep(float value) {
        float t = clamp01(value);
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static float remap(float value, float minimum, float maximum) {
        if (maximum == minimum) return 0f;
        return clamp01((value - minimum) / (maximum - minimum));
    }

    private static float easeOutBack(float value) {
        float t = clamp01(value);
        final float c1 = 1.70158f;
        final float c3 = c1 + 1;

        return (float) (1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2));
    }

    private static float cubic(float t, float p0, float p1, float p2, float p3) {
        float u = 1 - t;
        float tt = t * t;
        float uu = u * u;
        float uuu = uu * u;
        float ttt = tt * t;

        return uuu * p0 + 3 * uu * t * p1 + 3 * u * tt * p2 + ttt * p3;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int channel(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static int rgb(float r, float g, float b) {
        return Color.rgb(channel(r), channel(g), channel(b));
    }

    /** Как Color.opacity(_:) в SwiftUI — множит уже имеющуюся альфу, а не заменяет её. */
    private static int mul(int color, float factor) {
        int a = Math.round(Color.alpha(color) * clamp01(factor));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int alpha255(float opacity) {
        return Math.round(clamp01(opacity) * 255f);
    }
}
