package com.zhinibgdu.xianyu;

import android.content.Context;

/** Route only recognized task titles to their category. */
public final class TaskDispatcher {
    private TaskDispatcher() {}
    public static void dispatch(Context context, String title) {
        TaskCategory category = TaskCategory.classify(title);
        if (category == null) {
            TaskStatusReceiver.writeLog(context, "INFO", "任务分类", "未支持的任务，跳过：" + title);
            return;
        }
        TaskForegroundService.start(context, category);
    }
}
