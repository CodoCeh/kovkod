package ru.codoceh.stol.about;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Письменное предложение выдать исходный код и перечень программ, к которому оно
 * отсылает (R16b, R16.5).
 *
 * <p>Внутри приложения едет вся система стола — сотни чужих программ, в основном на
 * условиях GNU GPL. Раздавая приложение, мы раздаём и их, а значит обязаны либо положить
 * рядом исходный код, либо дать письменное предложение выдать его по запросу. Выбран
 * второй путь: он прямо описан в самих условиях и стандартен для встроенных систем.
 *
 * <p>Текст предложения лежит данными в {@code assets/about/source-offer.txt}, перечень
 * программ — в {@code assets/about/packages.txt}. Данными, а не строковыми ресурсами: имена
 * и версии чужих программ на экране приложения ловит словарная проверка мастера, а
 * заменять их нечем — по ним человек и находит исходный код. Тем же приёмом живёт реестр
 * заимствованных частей.
 *
 * <p>Перечень создаётся из собранного образа и руками не правится: разойдясь с образом,
 * он перестаёт указывать на те исходники, что реально едут в сборке. Сверку держит
 * {@code SourceOfferTest}.
 */
public final class SourceOffer {

    /** Текст письменного предложения. */
    public static final String OFFER_ASSET = "about/source-offer.txt";

    /** Перечень программ системы стола: имя и точная версия. */
    public static final String PACKAGES_ASSET = "about/packages.txt";

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private SourceOffer() {
    }

    /** Читает текстовый файл приложения. Поток закрывает вызывающий. */
    public static String read(InputStream text) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = text.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        String value = new String(buffer.toByteArray(), UTF_8).trim();
        if (value.isEmpty()) {
            throw new IOException("файл пуст");
        }
        return value;
    }

    /**
     * Перечень для показа человеку: имя программы и её версия. Служебные строки — заголовок
     * перечня и пустые — на экран не идут.
     */
    public static List<String> packages(String list) {
        List<String> shown = new ArrayList<>();
        for (String line : list.split("\n")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            if (!entry.matches("[^\\s]+ [^\\s]+")) {
                throw new IllegalArgumentException(
                        "Перечень программ испорчен: строка не «имя версия»: " + entry);
            }
            shown.add(entry);
        }
        if (shown.isEmpty()) {
            throw new IllegalArgumentException("Перечень программ испорчен: в нём нет программ");
        }
        return Collections.unmodifiableList(shown);
    }
}
