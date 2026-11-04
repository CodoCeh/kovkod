package ru.codoceh.stol.help;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import ru.codoceh.stol.R;

/** Все карточки помощи в порядке показа. */
public final class HelpTopics {

    private HelpTopics() {
    }

    public static List<HelpTopic> all(Context context) {
        List<HelpTopic> topics = new ArrayList<>();
        topics.add(new HelpTopic("start", context.getString(R.string.help_start_title),
                context.getString(R.string.help_start_summary), context.getString(R.string.help_start_steps),
                context.getString(R.string.help_start_keywords), HelpPreview.START, HelpAction.START_MENU, context.getString(R.string.help_start_try)));
        topics.add(new HelpTopic("touch", context.getString(R.string.help_touch_title),
                context.getString(R.string.help_touch_summary), context.getString(R.string.help_touch_steps),
                context.getString(R.string.help_touch_keywords), HelpPreview.TOUCH, HelpAction.SHOW_KEYBOARD, context.getString(R.string.help_touch_try)));
        topics.add(new HelpTopic("snap", context.getString(R.string.help_snap_title),
                context.getString(R.string.help_snap_summary), context.getString(R.string.help_snap_steps),
                context.getString(R.string.help_snap_keywords), HelpPreview.SNAP, HelpAction.SNAP_LAYOUTS, context.getString(R.string.help_snap_try)));
        topics.add(new HelpTopic("keys", context.getString(R.string.help_keys_title),
                context.getString(R.string.help_keys_summary), context.getString(R.string.help_keys_steps),
                context.getString(R.string.help_keys_keywords), HelpPreview.KEYS, HelpAction.SHOW_DESKTOP, context.getString(R.string.help_keys_try)));
        topics.add(new HelpTopic("params", context.getString(R.string.help_params_title),
                context.getString(R.string.help_params_summary), context.getString(R.string.help_params_steps),
                context.getString(R.string.help_params_keywords), HelpPreview.PARAMETERS, HelpAction.PARAMETERS, context.getString(R.string.help_params_try)));
        topics.add(new HelpTopic("store", context.getString(R.string.help_store_title),
                context.getString(R.string.help_store_summary), context.getString(R.string.help_store_steps),
                context.getString(R.string.help_store_keywords), HelpPreview.STORE, HelpAction.OPEN_STORE, context.getString(R.string.help_store_try)));
        topics.add(new HelpTopic("exe", context.getString(R.string.help_exe_title),
                context.getString(R.string.help_exe_summary), context.getString(R.string.help_exe_steps),
                context.getString(R.string.help_exe_keywords), HelpPreview.EXE, HelpAction.OPEN_WINDOWS_PROGRAMS, context.getString(R.string.help_exe_try)));
        topics.add(new HelpTopic("android", context.getString(R.string.help_android_title),
                context.getString(R.string.help_android_summary), context.getString(R.string.help_android_steps),
                context.getString(R.string.help_android_keywords), HelpPreview.ANDROID, HelpAction.START_MENU, context.getString(R.string.help_android_try)));
        topics.add(new HelpTopic("share", context.getString(R.string.help_share_title),
                context.getString(R.string.help_share_summary), context.getString(R.string.help_share_steps),
                context.getString(R.string.help_share_keywords), HelpPreview.SHARE, null, null));
        topics.add(new HelpTopic("shortcut", context.getString(R.string.help_shortcut_title),
                context.getString(R.string.help_shortcut_summary), context.getString(R.string.help_shortcut_steps),
                context.getString(R.string.help_shortcut_keywords), HelpPreview.SHORTCUT, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_shortcut_try)));
        topics.add(new HelpTopic("gpu", context.getString(R.string.help_gpu_title),
                context.getString(R.string.help_gpu_summary), context.getString(R.string.help_gpu_steps),
                context.getString(R.string.help_gpu_keywords), HelpPreview.GPU, null, null));
        topics.add(new HelpTopic("monitor", context.getString(R.string.help_monitor_title),
                context.getString(R.string.help_monitor_summary), context.getString(R.string.help_monitor_steps),
                context.getString(R.string.help_monitor_keywords), HelpPreview.MONITOR, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_monitor_try)));
        topics.add(new HelpTopic("sound", context.getString(R.string.help_sound_title),
                context.getString(R.string.help_sound_summary), context.getString(R.string.help_sound_steps),
                context.getString(R.string.help_sound_keywords), HelpPreview.SOUND, null, null));
        topics.add(new HelpTopic("notify", context.getString(R.string.help_notify_title),
                context.getString(R.string.help_notify_summary), context.getString(R.string.help_notify_steps),
                context.getString(R.string.help_notify_keywords), HelpPreview.NOTIFY, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_notify_try)));
        topics.add(new HelpTopic("gamepad", context.getString(R.string.help_gamepad_title),
                context.getString(R.string.help_gamepad_summary), context.getString(R.string.help_gamepad_steps),
                context.getString(R.string.help_gamepad_keywords), HelpPreview.GAMEPAD, HelpAction.SHOW_GAMEPAD, context.getString(R.string.help_gamepad_try)));
        topics.add(new HelpTopic("panel", context.getString(R.string.help_panel_title),
                context.getString(R.string.help_panel_summary), context.getString(R.string.help_panel_steps),
                context.getString(R.string.help_panel_keywords), HelpPreview.PANEL, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_panel_try)));
        topics.add(new HelpTopic("view", context.getString(R.string.help_view_title),
                context.getString(R.string.help_view_summary), context.getString(R.string.help_view_steps),
                context.getString(R.string.help_view_keywords), HelpPreview.VIEW, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_view_try)));
        topics.add(new HelpTopic("backup", context.getString(R.string.help_backup_title),
                context.getString(R.string.help_backup_summary), context.getString(R.string.help_backup_steps),
                context.getString(R.string.help_backup_keywords), HelpPreview.BACKUP, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_backup_try)));
        topics.add(new HelpTopic("update", context.getString(R.string.help_update_title),
                context.getString(R.string.help_update_summary), context.getString(R.string.help_update_steps),
                context.getString(R.string.help_update_keywords), HelpPreview.UPDATE, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_update_try)));
        topics.add(new HelpTopic("trouble", context.getString(R.string.help_trouble_title),
                context.getString(R.string.help_trouble_summary), context.getString(R.string.help_trouble_steps),
                context.getString(R.string.help_trouble_keywords), HelpPreview.TROUBLE, HelpAction.OPEN_SETTINGS, context.getString(R.string.help_trouble_try)));
        return topics;
    }
}
