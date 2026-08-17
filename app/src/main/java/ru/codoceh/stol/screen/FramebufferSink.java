package ru.codoceh.stol.screen;

/**
 * Куда {@link RfbClient} кладёт разобранный кадр. Отдельный шов ровно для того, чтобы
 * протокол проверялся без экрана: в тестах вместо холста стоит {@link Framebuffer}.
 */
public interface FramebufferSink {

    /** Размер стола объявлен или изменился; прежнее содержимое считается потерянным. */
    void resize(int width, int height);

    /**
     * Прямоугольник пикселей, слева направо и сверху вниз.
     *
     * @param argb буфер клиента; годны первые {@code width * height} значений, хранить нельзя
     */
    void pixels(int x, int y, int width, int height, int[] argb);

    /** Прямоугольник переехал внутри кадра — сервер прислал {@code CopyRect}. */
    void copy(int sourceX, int sourceY, int x, int y, int width, int height);

    /** Все прямоугольники кадра разобраны: можно показывать. */
    void frameComplete();
}
