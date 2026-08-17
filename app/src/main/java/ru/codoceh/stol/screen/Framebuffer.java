package ru.codoceh.stol.screen;

/**
 * Кадр стола в памяти: массив ARGB, который сеть заполняет, а экран показывает. Обычный
 * Java-класс без Android внутри — поэтому разбор кадра проверяется на настоящих байтах, а
 * не на моках.
 */
public final class Framebuffer implements FramebufferSink {

    /** Кому сказать, что кадр готов к показу. Вызывается из потока сети. */
    public interface FrameListener {
        void onFrame(Framebuffer framebuffer);
    }

    private final Object lock = new Object();
    private int width;
    private int height;
    private int[] pixels = new int[0];
    private volatile FrameListener listener;

    public void setFrameListener(FrameListener listener) {
        this.listener = listener;
    }

    public int width() {
        synchronized (lock) {
            return width;
        }
    }

    public int height() {
        synchronized (lock) {
            return height;
        }
    }

    @Override
    public void resize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("размер стола " + width + "x" + height);
        }
        synchronized (lock) {
            this.width = width;
            this.height = height;
            this.pixels = new int[width * height];
        }
    }

    @Override
    public void pixels(int x, int y, int rectWidth, int rectHeight, int[] argb) {
        synchronized (lock) {
            checkInside(x, y, rectWidth, rectHeight);
            for (int row = 0; row < rectHeight; row++) {
                System.arraycopy(argb, row * rectWidth, pixels, (y + row) * width + x, rectWidth);
            }
        }
    }

    @Override
    public void copy(int sourceX, int sourceY, int x, int y, int rectWidth, int rectHeight) {
        synchronized (lock) {
            checkInside(x, y, rectWidth, rectHeight);
            checkInside(sourceX, sourceY, rectWidth, rectHeight);
            // Прямоугольники могут перекрываться: если едем вниз — строки переносим снизу вверх.
            if (sourceY < y) {
                for (int row = rectHeight - 1; row >= 0; row--) {
                    moveRow(sourceX, sourceY + row, x, y + row, rectWidth);
                }
            } else {
                for (int row = 0; row < rectHeight; row++) {
                    moveRow(sourceX, sourceY + row, x, y + row, rectWidth);
                }
            }
        }
    }

    @Override
    public void frameComplete() {
        FrameListener current = listener;
        if (current == null || isEmpty()) {
            // Показывать нечего: размер у кадра появляется только после рукопожатия с
            // сервером, а перерисовки просит и поверхность экрана, которая готова раньше.
            return;
        }
        current.onFrame(this);
    }

    /** Кадра ещё нет: сервер не назвал размер стола. */
    public boolean isEmpty() {
        synchronized (lock) {
            return width <= 0 || height <= 0;
        }
    }

    /** Цвет точки — для проверок и отладки. */
    public int argbAt(int x, int y) {
        synchronized (lock) {
            checkInside(x, y, 1, 1);
            return pixels[y * width + x];
        }
    }

    /**
     * Снимает кадр целиком в чужой массив под одним замком — так на экран не попадает
     * половина одного кадра и половина другого.
     *
     * @return сколько значений скопировано, 0 если размер массива не подошёл
     */
    public int snapshot(int[] out) {
        synchronized (lock) {
            int count = width * height;
            if (out == null || out.length < count) {
                return 0;
            }
            System.arraycopy(pixels, 0, out, 0, count);
            return count;
        }
    }

    private void moveRow(int sourceX, int sourceY, int x, int y, int rectWidth) {
        System.arraycopy(pixels, sourceY * width + sourceX, pixels, y * width + x, rectWidth);
    }

    private void checkInside(int x, int y, int rectWidth, int rectHeight) {
        if (x < 0 || y < 0 || rectWidth < 0 || rectHeight < 0
                || x + rectWidth > width || y + rectHeight > height) {
            throw new IllegalArgumentException("прямоугольник " + rectWidth + "x" + rectHeight
                    + " в точке " + x + "," + y + " не влезает в кадр " + width + "x" + height);
        }
    }
}
