package ru.codoceh.stol.settings;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки стола, которые читает {@code MainActivity} перед запуском и пишет
 * {@code SettingsActivity}. Один файл настроек на всё приложение — тот же, в котором
 * уже лежит режим ввода (тач/трекпад).
 */
public final class DesktopPrefs {

    public static final String PREFS_NAME = "stol_prefs";

    /** Углы экрана для панели управления столом (клавиатура, тач/трекпад, настройки). */
    public static final int PANEL_TOP_RIGHT = 0;
    public static final int PANEL_BOTTOM_RIGHT = 1;
    public static final int PANEL_TOP_LEFT = 2;
    public static final int PANEL_BOTTOM_LEFT = 3;

    private static final String KEY_WIDTH = "desktop_width";
    private static final String KEY_HEIGHT = "desktop_height";
    private static final String KEY_SCALE = "desktop_scale";
    private static final String KEY_ECONOMIZE_CPU = "cpu_economize";
    private static final String KEY_ECONOMIZE_SCREEN = "screen_economize";
    private static final String KEY_RAM_LIMIT_MB = "ram_limit_mb";
    private static final String KEY_PANEL_POSITION = "panel_position";
    private static final String KEY_SHORTCUT_WINDOW = "shortcut_window";
    private static final String KEY_REMOTE_PASSWORD = "remote_password";

    public static final String WINDOW_NORMAL = "normal";
    public static final String WINDOW_MAXIMIZED = "max";
    public static final String WINDOW_FULLSCREEN = "full";

    private DesktopPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Ширина стола в точках; {@code 0} — авто, взять размер вида. */
    public static int width(Context context) {
        return prefs(context).getInt(KEY_WIDTH, 0);
    }

    /** Высота стола в точках; {@code 0} — авто, взять размер вида. */
    public static int height(Context context) {
        return prefs(context).getInt(KEY_HEIGHT, 0);
    }

    /** Масштаб интерфейса XFCE (1 или 2); {@code 0} — авто, по плотности экрана. */
    public static int scale(Context context) {
        return prefs(context).getInt(KEY_SCALE, 0);
    }

    /** Понижен ли приоритет очереди стола — см. {@code DesktopLauncher.sessionCommand}. */
    public static boolean economizeCpu(Context context) {
        return prefs(context).getBoolean(KEY_ECONOMIZE_CPU, false);
    }

    /** Потолок памяти в мегабайтах; {@code 0} — без потолка. */
    public static int ramLimitMb(Context context) {
        return prefs(context).getInt(KEY_RAM_LIMIT_MB, 0);
    }

    /** Реже перерисовывать экран стола на телефоне, пока на столе что-то движется. */
    public static boolean economizeScreen(Context context) {
        return prefs(context).getBoolean(KEY_ECONOMIZE_SCREEN, false);
    }

    /** Угол экрана для панели управления столом; см. {@code PANEL_*}. */
    public static int panelPosition(Context context) {
        return prefs(context).getInt(KEY_PANEL_POSITION, PANEL_BOTTOM_RIGHT);
    }

    public static void setResolution(Context context, int width, int height) {
        prefs(context).edit().putInt(KEY_WIDTH, width).putInt(KEY_HEIGHT, height).apply();
    }

    public static void setScale(Context context, int scale) {
        prefs(context).edit().putInt(KEY_SCALE, scale).apply();
    }

    public static void setEconomizeCpu(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_ECONOMIZE_CPU, value).apply();
    }

    public static void setRamLimitMb(Context context, int mb) {
        prefs(context).edit().putInt(KEY_RAM_LIMIT_MB, mb).apply();
    }

    public static void setEconomizeScreen(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_ECONOMIZE_SCREEN, value).apply();
    }

    public static void setPanelPosition(Context context, int position) {
        prefs(context).edit().putInt(KEY_PANEL_POSITION, position).apply();
    }

    /** Как открывать программу со значка на главном экране; см. {@code WINDOW_*}. */
    public static String shortcutWindow(Context context) {
        return prefs(context).getString(KEY_SHORTCUT_WINDOW, WINDOW_FULLSCREEN);
    }

    public static void setShortcutWindow(Context context, String mode) {
        prefs(context).edit().putString(KEY_SHORTCUT_WINDOW, mode).apply();
    }

    /** Пароль стола в браузере; {@code null} — выключено. */
    public static String remotePassword(Context context) {
        return prefs(context).getString(KEY_REMOTE_PASSWORD, null);
    }

    public static void setRemotePassword(Context context, String password) {
        prefs(context).edit().putString(KEY_REMOTE_PASSWORD, password).apply();
    }
}
