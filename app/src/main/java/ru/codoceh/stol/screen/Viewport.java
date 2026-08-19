package ru.codoceh.stol.screen;

/**
 * Пересчёт между окном на телефоне и кадром стола: стол вписывается в окно целиком и
 * центрируется, поэтому обрезанного стола не бывает ни при повороте, ни при складывании
 * (R09.3). Обычный Java-класс — проверяется числами, без устройства.
 */
public final class Viewport {

    private int frameWidth;
    private int frameHeight;
    private int viewWidth;
    private int viewHeight;

    private float zoom = 1.0f;
    private float panX = 0f;
    private float panY = 0f;

    public void setFrameSize(int width, int height) {
        this.frameWidth = width;
        this.frameHeight = height;
        clampPan();
    }

    public void setViewSize(int width, int height) {
        this.viewWidth = width;
        this.viewHeight = height;
        clampPan();
    }

    public int frameWidth() {
        return frameWidth;
    }

    public int frameHeight() {
        return frameHeight;
    }

    public boolean isReady() {
        return frameWidth > 0 && frameHeight > 0 && viewWidth > 0 && viewHeight > 0;
    }

    /** Базовый масштаб вписывания (Fit-to-Screen). */
    public float baseScale() {
        if (!isReady()) {
            return 1f;
        }
        return Math.min((float) viewWidth / frameWidth, (float) viewHeight / frameHeight);
    }

    /** Во сколько раз кадр показан крупнее оригинала с учётом зума. */
    public float scale() {
        return baseScale() * zoom;
    }

    public float zoom() {
        return zoom;
    }

    public float panX() {
        return panX;
    }

    public float panY() {
        return panY;
    }

    public float offsetX() {
        return (viewWidth - frameWidth * scale()) / 2f + panX;
    }

    public float offsetY() {
        return (viewHeight - frameHeight * scale()) / 2f + panY;
    }

    /**
     * Задать зум с фокусом на экранной точке (focusViewX, focusViewY).
     */
    public void setZoom(float newZoom, float focusViewX, float focusViewY) {
        if (!isReady()) {
            return;
        }
        float oldScale = scale();
        float clampedZoom = Math.max(1.0f, Math.min(newZoom, 5.0f));
        if (Math.abs(clampedZoom - 1.0f) < 0.001f) {
            resetZoom();
            return;
        }

        float worldX = focusViewX - offsetX();
        float worldY = focusViewY - offsetY();

        this.zoom = clampedZoom;
        float newScale = scale();

        float newBaseOffsetX = (viewWidth - frameWidth * newScale) / 2f;
        float newBaseOffsetY = (viewHeight - frameHeight * newScale) / 2f;

        float ratio = newScale / oldScale;
        panX = focusViewX - newBaseOffsetX - worldX * ratio;
        panY = focusViewY - newBaseOffsetY - worldY * ratio;

        clampPan();
    }

    public void pan(float deltaViewX, float deltaViewY) {
        if (zoom <= 1.0f) {
            return;
        }
        panX += deltaViewX;
        panY += deltaViewY;
        clampPan();
    }

    public void resetZoom() {
        zoom = 1.0f;
        panX = 0f;
        panY = 0f;
    }

    private void clampPan() {
        if (!isReady() || zoom <= 1.0f) {
            panX = 0f;
            panY = 0f;
            return;
        }
        float scaledW = frameWidth * scale();
        float scaledH = frameHeight * scale();

        float maxPanX = Math.max(0f, (scaledW - viewWidth) / 2f);
        float maxPanY = Math.max(0f, (scaledH - viewHeight) / 2f);

        panX = Math.max(-maxPanX, Math.min(panX, maxPanX));
        panY = Math.max(-maxPanY, Math.min(panY, maxPanY));
    }

    /** Точка касания — в координату стола, с прижатием к краям кадра. */
    public int frameX(float viewX) {
        return clamp((int) ((viewX - offsetX()) / scale()), frameWidth);
    }

    public int frameY(float viewY) {
        return clamp((int) ((viewY - offsetY()) / scale()), frameHeight);
    }

    private static int clamp(int value, int size) {
        if (value < 0) {
            return 0;
        }
        return value >= size ? Math.max(size - 1, 0) : value;
    }
}
