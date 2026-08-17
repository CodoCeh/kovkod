package ru.codoceh.stol.about;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Реестр заимствованных частей из {@code assets/about/attributions.json} — единственное
 * место, где записано, что в сборке чужое.
 *
 * <p>До этого сведения лежали в трёх местах: описание родных бинарников в
 * {@code assets/native/SOURCES.md}, список библиотек java в файле сборки и тексты условий
 * в каталогах рядом. Экран «О программе» читает реестр, тест сверяет реестр со сборкой и
 * краснеет, если в сборке появилось чужое, а записи о нём нет.
 *
 * <p>Разбор свой и плоский, как у списка программ и описания образа: чужая библиотека
 * ради семи полей не нужна, а разбор на стороне телефона в тестах на настольной машине —
 * заглушка.
 */
public final class Attributions {

    /** Имя файла реестра в assets. */
    public static final String ASSET = "about/attributions.json";

    private static final Pattern FIELD =
            Pattern.compile("\"([a-z_]+)\"\\s*:\\s*\"([^\"]*)\"");

    private Attributions() {
    }

    /** Читает реестр из потока. Поток закрывает вызывающий. */
    public static List<Attribution> read(InputStream json) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = json.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return parse(new String(buffer.toByteArray(), Charset.forName("UTF-8")));
    }

    /** Разбирает реестр. Кривая запись — отказ с именем поля, а не тихий пропуск части. */
    public static List<Attribution> parse(String json) {
        List<Attribution> parts = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String entry : entries(json)) {
            Attribution part = one(entry);
            if (ids.contains(part.id())) {
                throw broken("часть " + part.id() + " записана дважды");
            }
            ids.add(part.id());
            parts.add(part);
        }
        if (parts.isEmpty()) {
            throw broken("в реестре нет ни одной части");
        }
        return Collections.unmodifiableList(parts);
    }

    private static Attribution one(String entry) {
        Map<String, String> fields = fields(entry);
        String id = required(fields, "id", entry);
        Attribution.Kind kind = Attribution.Kind.of(required(fields, "kind", entry));
        if (kind == null) {
            throw broken("у части " + id + " незнакомый вид «" + fields.get("kind") + "»");
        }
        String source = required(fields, "source", id);
        if (!source.startsWith("https://") && !source.startsWith("http://")) {
            throw broken("у части " + id + " адрес исходного кода не адрес: " + source);
        }
        return new Attribution(
                id,
                kind,
                required(fields, "name", id),
                required(fields, "note", id),
                list(required(fields, "ships", id)),
                required(fields, "author", id),
                required(fields, "license", id),
                yes(required(fields, "copyleft", id), id, "copyleft"),
                yes(required(fields, "modified", id), id, "modified"),
                source,
                list(required(fields, "license_text", id)));
    }

    /** «да» или «нет» — третьего ответа у этих полей нет, и молчание тоже не ответ. */
    private static boolean yes(String value, String id, String field) {
        if ("да".equals(value)) {
            return true;
        }
        if ("нет".equals(value)) {
            return false;
        }
        throw broken("у части " + id + " поле " + field + " отвечает не «да» и не «нет»: " + value);
    }

    private static List<String> list(String value) {
        List<String> items = new ArrayList<>();
        for (String item : Arrays.asList(value.split(","))) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return Collections.unmodifiableList(items);
    }

    /** Режет массив на записи. Реестр плоский: вложенных объектов в нём нет. */
    private static List<String> entries(String json) {
        List<String> entries = new ArrayList<>();
        boolean inString = false;
        int depth = 0;
        int start = -1;
        for (int at = 0; at < json.length(); at++) {
            char symbol = json.charAt(at);
            if (inString) {
                if (symbol == '\\') {
                    at++;
                } else if (symbol == '"') {
                    inString = false;
                }
                continue;
            }
            if (symbol == '"') {
                inString = true;
            } else if (symbol == '{') {
                if (depth++ == 0) {
                    start = at;
                }
            } else if (symbol == '}') {
                if (--depth == 0) {
                    entries.add(json.substring(start, at + 1));
                } else if (depth < 0) {
                    throw broken("реестр не читается");
                }
            }
        }
        if (depth != 0 || inString) {
            throw broken("реестр оборван на середине");
        }
        return entries;
    }

    private static Map<String, String> fields(String entry) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher found = FIELD.matcher(entry);
        while (found.find()) {
            fields.put(found.group(1), found.group(2).trim());
        }
        return fields;
    }

    private static String required(Map<String, String> fields, String key, String where) {
        String value = fields.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw broken("нет поля " + key + ": " + where.replace('\n', ' '));
        }
        return value.trim();
    }

    private static IllegalArgumentException broken(String what) {
        return new IllegalArgumentException("Реестр заимствованных частей испорчен: " + what);
    }
}
