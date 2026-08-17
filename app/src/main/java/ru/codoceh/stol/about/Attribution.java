package ru.codoceh.stol.about;

import java.util.List;

/**
 * Одна заимствованная часть из {@code assets/about/attributions.json}: что за кусок, чья
 * это работа, на каких условиях он лежит внутри, изменён или нет и где взять его
 * исходный код.
 *
 * <p>Это данные, а не код, по той же причине, что и список программ: реестр читает экран
 * «О программе» и сверяет тест, а сведения о версиях и адресах меняются чаще, чем
 * приложение.
 */
public final class Attribution {

    /** Каким способом часть едет в сборке. От вида зависит, чем её сверять. */
    public enum Kind {
        /** Готовый бинарник в каталоге родных библиотек. Сверяется именами файлов. */
        NATIVE("native"),
        /** Библиотека java из списка зависимостей сборки. Сверяется её именем в сборке. */
        LIBRARY("library"),
        /** Готовая система, которая едет внутри приложения целиком. */
        SYSTEM("system");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** Слово из файла реестра. */
        public String word() {
            return word;
        }

        static Kind of(String word) {
            for (Kind kind : values()) {
                if (kind.word.equals(word)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private final String id;
    private final Kind kind;
    private final String name;
    private final String note;
    private final List<String> ships;
    private final String author;
    private final String license;
    private final boolean copyleft;
    private final boolean modified;
    private final String source;
    private final List<String> licenseTexts;

    Attribution(String id, Kind kind, String name, String note, List<String> ships,
                String author, String license, boolean copyleft, boolean modified,
                String source, List<String> licenseTexts) {
        this.id = id;
        this.kind = kind;
        this.name = name;
        this.note = note;
        this.ships = ships;
        this.author = author;
        this.license = license;
        this.copyleft = copyleft;
        this.modified = modified;
        this.source = source;
        this.licenseTexts = licenseTexts;
    }

    /** Ключ записи: по нему тест узнаёт часть, человеку он не показывается. */
    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    /** Как часть называется у своих авторов: имя работы и версия. */
    public String name() {
        return name;
    }

    /** Одна строка о том, зачем эта часть внутри. */
    public String note() {
        return note;
    }

    /**
     * Что именно едет в сборке: имена файлов родных библиотек, имя библиотеки java в
     * сборке или имя файла системы. По этому полю тест сверяет реестр со сборкой.
     */
    public List<String> ships() {
        return ships;
    }

    /** Чья работа. */
    public String author() {
        return author;
    }

    /** Под какими условиями лежит внутри. */
    public String license() {
        return license;
    }

    /**
     * Условия требуют открывать исходный код производных работ. Такие части кладутся
     * неизменёнными и запускаются отдельными программами — иначе продавать сборку нельзя
     * (R16).
     */
    public boolean isCopyleft() {
        return copyleft;
    }

    /** Изменяли ли мы эту часть. Для частей с сильными условиями ответ обязан быть «нет». */
    public boolean isModified() {
        return modified;
    }

    /** Адрес, по которому лежит исходный код именно этой части. */
    public String source() {
        return source;
    }

    /**
     * Где внутри приложения лежат полные тексты условий: файл или каталог с файлами.
     * Сети для их чтения не нужно — это требование R10i.1.
     */
    public List<String> licenseTexts() {
        return licenseTexts;
    }
}
