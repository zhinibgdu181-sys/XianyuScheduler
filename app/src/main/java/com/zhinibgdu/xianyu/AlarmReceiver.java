package com.zhinibgdu.xianyu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Exact-alarm entry point. Keep onReceive short and delegate long work to a FGS. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();

        if (!AppConfig.isScheduleEnabled(app)) {
            TaskStatusReceiver.writeLog(app, "INFO", "调度", "定时已关闭，忽略旧闹钟");
            return;
        }
        // Schedule tomorrow first so an executor crash cannot break the daily chain.
        if (AppConfig.isScheduleEnabled(app)) {
            boolean nextScheduled = MainActivity.schedule(
                    app,
                    AppConfig.getHour(app),
                    AppConfig.getMinute(app),
                    false
            );
            if (!nextScheduled) {
                TaskStatusReceiver.writeLog(
                        app, "FAILED", "调度", "下一次每日闹钟安排失败"
                );
            }
        }

        TaskStatusReceiver.writeLog(app, "INFO", "调度", "定时闹钟触发");

        if (!AppConfig.isLocalTaskEnabled(app)
                && !AppConfig.isVideoTaskEnabled(app)
                && !AppConfig.isJumpTaskEnabled(app)) {
            TaskStatusReceiver.writeLog(app, "INFO", "调度", "任务开关均关闭，跳过执行");
            return;
        }

        try {
            TaskForegroundService.start(app);
        } catch (Throwable t) {
            TaskStatusReceiver.writeLog(
                    app,
                    "FAILED",
                    "调度",
                    "启动前台服务失败: " + t
            );
        }
    }
}
