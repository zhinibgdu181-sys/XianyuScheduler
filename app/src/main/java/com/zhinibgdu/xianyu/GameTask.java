package com.zhinibgdu.xianyu;

import android.content.Context;

public final class GameTask {
    private GameTask() {}
    public static boolean matches(String title) {
        return TaskCategory.classify(title) == TaskCategory.GAME;
    }
    public static void run(Context context) {
        TaskForegroundService.start(context, TaskCategory.GAME);
    }
}
