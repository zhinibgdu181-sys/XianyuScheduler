package com.zhinibgdu.xianyu;

import android.content.Context;

public final class XianyuLocalTask {
    private XianyuLocalTask() {}
    public static boolean matches(String title) {
        return TaskCategory.classify(title) == TaskCategory.LOCAL;
    }
    public static void run(Context context) {
        TaskForegroundService.start(context, TaskCategory.LOCAL);
    }
}
