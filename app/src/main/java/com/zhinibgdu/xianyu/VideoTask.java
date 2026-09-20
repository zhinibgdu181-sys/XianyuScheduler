package com.zhinibgdu.xianyu;

import android.content.Context;

public final class VideoTask {
    private VideoTask() {}
    public static boolean matches(String title) {
        return TaskCategory.classify(title) == TaskCategory.VIDEO;
    }
    public static void run(Context context) {
        TaskForegroundService.start(context, TaskCategory.VIDEO);
    }
}
