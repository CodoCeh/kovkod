package ru.codoceh.stol.launch;

/**
 * Во что стол поднимать на этом экране: размер X-сервера в точках и масштаб интерфейса
 * XFCE. Обычный Java-класс — проверяется числами, без устройства.
 *
 * <p><b>Размер.</b> Стол поднимается ровно в тех точках, в которых он потом показан: одна
 * точка стола — одна точка экрана. Прежде размер был жёстким (1600×1000 в
 * {@code start-gui.sh}), и на экране 1080×2274 картинка не ложилась на сетку точек ни по
 * одной оси: её вписывали с множителем 0,675 и интерполяцией — границы размазывались,
 * тонкие линии и текст теряли контраст, цвета усреднялись с соседними. Отсюда «бледные
 * тона» и «раньше было резче».
 *
 * <p><b>Масштаб интерфейса.</b> Резкость даётся ценой размера: 1248 точек стола на экране
 * телефона — это интерфейс, набранный под ноутбук, на ладони. Поэтому вместе с размером
 * едет множитель, и он не выдуман, а взят у самого Android: там точка разметки (dp) равна
 * 1/160 дюйма, то есть {@code densityDpi / 160} и есть множитель, которым система сама
 * укрупняет свой интерфейс на этом экране.
 *
 * <p>Множитель целый, потому что уходит в {@code GDK_SCALE}: GTK при нём рисует ВЕСЬ
 * интерфейс в увеличенном размере — и шрифты, и значки, и панель, — но рисует его в
 * настоящих точках, а не растягивает готовую картинку. Дробного масштаба у GDK_SCALE нет,
 * и это к лучшему: дробный вернул бы ту самую интерполяцию, из-за которой всё и затевалось.
 *
 * <p>Потолок — два. На экране владельца (переопределённая плотность 360) Android берёт
 * 2,25, но при тройном масштабе от стола осталось бы 416 точек разметки по ширине — это
 * уже не рабочий стол, а телефонное приложение. При двойном остаётся 624.
 *
 * <p>Числами для экрана владельца (1248×1972, плотность 360, физическая 420): «Noto Sans
 * 10» — это 10 × 96 / 72 = 13,3 точки высоты, на физических 420 точках на дюйм 0,8 мм.
 * При масштабе 2 — 26,7 точки и 1,6 мм; для сравнения, обычный текст Android при
 * плотности 360 занимает 14 × 2,25 = 31,5 точки. Панель растёт с 40 точек до 80, значки
 * панели с 24 до 48.
 */
public final class DesktopFit {

    /** Меньше стол не поднимаем: в такой X-сервер не влезет ни одно окно. */
    public static final int MIN_SIDE = 320;

    /** Больше не поднимаем: кадр целиком лежит в памяти и приложения, и X-сервера. */
    public static final int MAX_SIDE = 4096;

    /** Масштаб интерфейса: 1 — как на мониторе, 2 — вдвое крупнее и по-прежнему резко. */
    public static final int MIN_SCALE = 1;
    public static final int MAX_SCALE = 2;

    /** Точка разметки Android — 1/160 дюйма. */
    private static final int ANDROID_BASE_DPI = 160;

    private final int width;
    private final int height;
    private final int uiScale;

    private DesktopFit(int width, int height, int uiScale) {
        this.width = width;
        this.height = height;
        this.uiScale = uiScale;
    }

    /**
     * @param viewWidth  ширина вида, в котором будет показан стол, в точках экрана
     * @param viewHeight высота того же вида
     * @param densityDpi плотность экрана Android ({@code DisplayMetrics.densityDpi})
     */
    public static DesktopFit forView(int viewWidth, int viewHeight, int densityDpi) {
        return new DesktopFit(clamp(viewWidth), clamp(viewHeight), scaleFor(densityDpi));
    }

    /** Ширина X-сервера в точках. */
    public int width() {
        return width;
    }

    /** Высота X-сервера в точках. */
    public int height() {
        return height;
    }

    /** Во сколько раз крупнее рисовать интерфейс XFCE. */
    public int uiScale() {
        return uiScale;
    }

    /** Размер в записи, которую понимает Xtigervnc. */
    public String geometry() {
        return width + "x" + height;
    }

    private static int clamp(int side) {
        if (side < MIN_SIDE) {
            return MIN_SIDE;
        }
        return side > MAX_SIDE ? MAX_SIDE : side;
    }

    private static int scaleFor(int densityDpi) {
        int scale = Math.round((float) densityDpi / ANDROID_BASE_DPI);
        if (scale < MIN_SCALE) {
            return MIN_SCALE;
        }
        return scale > MAX_SCALE ? MAX_SCALE : scale;
    }
}
