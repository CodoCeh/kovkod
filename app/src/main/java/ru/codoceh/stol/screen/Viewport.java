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

    public void setFrameSize(int width, int height) {
        this.frameWidth = width;
        this.frameHeight = height;
    }

    public void setViewSize(int width, int height) {
        this.viewWidth = width;
        this.viewHeight = height;
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

    /** Во сколько раз кадр показан крупнее оригинала. */
    public float scale() {
        if (!isReady()) {
            return 1f;
        }
        return Math.min((float) viewWidth / frameWidth, (float) viewHeight / frameHeight);
    }

    public float offsetX() {
        return (viewWidth - frameWidth * scale()) / 2f;
    }

    public float offsetY() {
        return (viewHeight - frameHeight * scale()) / 2f;
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
