package ru.codoceh.stol.apps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Список программ из {@code assets/apps.json} (шов 4): плоский перечень объектов со
 * строковыми полями.
 *
 * <p>Разбор свой, как и у описания образа: разбор JSON на стороне Android в тестах на
 * настольной машине — заглушка, а тащить чужую библиотеку ради десятка полей незачем.
 *
 * <p>Файл едет вместе с приложением, поэтому его порча — наша ошибка, а не положение
 * человека: разбор падает сразу и называет поле. Ловится тестом, а не пользователем.
 */
public final class AppsCatalog {

    /** Имя файла списка рядом с образом. */
    public static final String ASSET = "apps.json";

    private static final Pattern FIELD =
            Pattern.compile("\"([a-z_]+)\"\\s*:\\s*\"([^\"]*)\"");

    private AppsCatalog() {
    }

    /** Читает список из потока. Поток закрывает вызывающий. */
    public static List<AppSpec> read(InputStream json) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = json.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return parse(new String(buffer.toByteArray(), Charset.forName("UTF-8")));
    }

    /** Разбирает список программ. Кривая строка — отказ с именем поля, а не молчание. */
    public static List<AppSpec> parse(String json) {
        List<AppSpec> apps = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String entry : entries(json)) {
            AppSpec app = one(entry);
            if (ids.contains(app.id())) {
                throw broken("программа " + app.id() + " названа дважды");
            }
            ids.add(app.id());
            apps.add(app);
        }
        if (apps.isEmpty()) {
            throw broken("в списке программ нет ни одной программы");
        }
        return Collections.unmodifiableList(apps);
    }

    private static AppSpec one(String entry) {
        Map<String, String> fields = fields(entry);
        String id = required(fields, "id", entry);
        String name = required(fields, "name", entry);
        AppSpec.Kind kind = AppSpec.Kind.of(required(fields, "kind", entry));
        if (kind == null) {
            throw broken("у программы " + id + " незнакомый способ установки «"
                    + fields.get("kind") + "»");
        }

        String url = value(fields, "url");
        if (kind != AppSpec.Kind.REPO && url.isEmpty()) {
            throw broken("у программы " + id + " нет адреса");
        }
        String target = value(fields, "target");
        boolean unpacked = kind == AppSpec.Kind.APPIMAGE || kind == AppSpec.Kind.ARCHIVE;
        if (unpacked && target.isEmpty()) {
            throw broken("у программы " + id + " не сказано, куда её раскладывать");
        }

        String icon = value(fields, "icon");
        String profile = value(fields, "profile");
        String packageName = value(fields, "package");
        // Имя ярлыка не подставляем: у пакета он свой, и придуманное имя превратило бы
        // проверку установки в проверку выдумки.
        return new AppSpec(id, name, kind, url,
                icon.isEmpty() ? id : icon,
                value(fields, "desktop"),
                target,
                value(fields, "exec"),
                value(fields, "exec_flags"),
                profile.isEmpty() ? "/root/.config/" + id : profile,
                packageName.isEmpty() ? id : packageName);
    }

    /** Режет массив на объекты. Файл плоский: вложенных объектов и массивов в нём нет. */
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
                    throw broken("список программ не читается");
                }
            }
        }
        if (depth != 0 || inString) {
            throw broken("список программ оборван на середине");
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

    private static String required(Map<String, String> fields, String key, String entry) {
        String value = value(fields, key);
        if (value.isEmpty()) {
            throw broken("в списке программ нет поля " + key + ": " + entry.replace('\n', ' '));
        }
        return value;
    }

    private static String value(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null ? "" : value;
    }

    private static IllegalArgumentException broken(String what) {
        return new IllegalArgumentException("Список программ испорчен: " + what);
    }
}
