package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

/** Centralized persistent configuration for the scheduler. */
public final class AppConfig {
    private static final String PREF = "task_config";
    private static final String KEY_HOUR = "hour";
    private static final String KEY_MINUTE = "minute";
    private static final String KEY_ENABLED = "schedule_enabled";
    private static final String KEY_LOCAL_TASK = "enable_local_task";
    private static final String KEY_VIDEO_TASK = "enable_video_task";
    private static final String KEY_JUMP_TASK = "enable_jump_task";

    public static final int DEFAULT_HOUR = 9;
    public static final int DEFAULT_MINUTE = 0;

    private AppConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static int getHour(Context context) {
        return Math.max(0, Math.min(23, prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR)));
    }

    public static int getMinute(Context context) {
        return Math.max(0, Math.min(59, prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE)));
    }

    public static boolean isScheduleEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void saveSchedule(Context context, int hour, int minute) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59)
            throw new IllegalArgumentException("Invalid schedule time");
        prefs(context).edit()
                .putInt(KEY_HOUR, hour)
                .putInt(KEY_MINUTE, minute)
                .putBoolean(KEY_ENABLED, true)
                .apply();
    }


    public static boolean isLocalTaskEnabled(Context context) {
        return prefs(context).getBoolean(KEY_LOCAL_TASK, true);
    }

    public static boolean isVideoTaskEnabled(Context context) {
        return prefs(context).getBoolean(KEY_VIDEO_TASK, false);
    }

    public static boolean isJumpTaskEnabled(Context context) {
        return prefs(context).getBoolean(KEY_JUMP_TASK, false);
    }

    public static void saveTaskSwitches(Context context, boolean local, boolean video, boolean jump) {
        prefs(context).edit()
                .putBoolean(KEY_LOCAL_TASK, local)
                .putBoolean(KEY_VIDEO_TASK, video)
                .putBoolean(KEY_JUMP_TASK, jump)
                .remove("enable_game_task")
                .apply();
    }

    public static void setScheduleEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }
}
