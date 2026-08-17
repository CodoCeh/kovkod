package ru.codoceh.stol.apps;

/**
 * Одна программа из {@code assets/apps.json} (шов 4). Это данные, а не код: адреса
 * производителей меняются, и правка адреса не должна быть правкой приложения (R04.4i).
 *
 * <p>Видов установки ровно четыре, список закрытый. Новый вид добавляется кодом — новая
 * программа строкой в файле.
 */
public final class AppSpec {

    /** Как программа попадает на стол. */
    public enum Kind {
        /** Пакет Debian с сайта производителя. */
        DEB("deb", true),
        /**
         * AppImage с сайта производителя — распаковывается, а не запускается как есть:
         * файловой системы в памяти нам не дают, и запуск образа целиком не проходит.
         */
        APPIMAGE("appimage-extract", true),
        /** Окно поверх браузера, который уже лежит в образе. Сети производителя не нужно. */
        WEBAPP("webapp", false),
        /** Пакет из репозитория Debian. Сайт производителя тут ни при чём. */
        REPO("apt", false);

        private final String word;
        private final boolean download;

        Kind(String word, boolean download) {
            this.word = word;
            this.download = download;
        }

        /** Слово из файла списка программ. */
        public String word() {
            return word;
        }

        /** Нужно ли качать файл с сайта производителя. */
        public boolean needsDownload() {
            return download;
        }

        /**
         * Нужен ли интернет. Сайт производителя нужен только двум видам (шов 4), но
         * пакету из репозитория тоже неоткуда взяться без сети — и честнее пометить его
         * отложенным, чем отказавшим.
         */
        public boolean needsNetwork() {
            return this != WEBAPP;
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
    private final String name;
    private final Kind kind;
    private final String url;
    private final String icon;
    private final String desktop;
    private final String target;
    private final String execFlags;
    private final String profile;
    private final String packageName;

    AppSpec(String id, String name, Kind kind, String url, String icon, String desktop,
            String target, String execFlags, String profile, String packageName) {
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.url = url;
        this.icon = icon;
        this.desktop = desktop;
        this.target = target;
        this.execFlags = execFlags;
        this.profile = profile;
        this.packageName = packageName;
    }

    /** Короткое имя без пробелов: им зовутся файлы и ярлык. */
    public String id() {
        return id;
    }

    /** Как программа называется у человека на экране. */
    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** Адрес источника; у пакета из репозитория пусто. */
    public String url() {
        return url;
    }

    /** Имя значка для ярлыка. */
    public String icon() {
        return icon;
    }

    /** Имя файла ярлыка в каталоге меню программ. */
    public String desktop() {
        return desktop;
    }

    /** Куда распаковывается AppImage; у остальных видов пусто. */
    public String target() {
        return target;
    }

    /**
     * Ключи запуска из файла списка. К ним всегда добавляются обязательные — без них окна
     * Electron не появляются вовсе, это проверено на эталонном столе владельца.
     */
    public String execFlags() {
        return execFlags;
    }

    /** Где программа-окно держит свои данные. */
    public String profile() {
        return profile;
    }

    /** Имя пакета в репозитории; для остальных видов совпадает с {@link #id()}. */
    public String packageName() {
        return packageName;
    }
}
