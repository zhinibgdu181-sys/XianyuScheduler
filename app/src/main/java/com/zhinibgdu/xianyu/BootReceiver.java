package com.zhinibgdu.xianyu;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores the saved daily alarm after a device reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!AppConfig.isScheduleEnabled(context)) return;

        boolean ok = MainActivity.schedule(
                context,
                AppConfig.getHour(context),
                AppConfig.getMinute(context),
                false
        );
        TaskStatusReceiver.writeLog(
                context,
                ok ? "INFO" : "FAILED",
                "调度",
                ok ? "开机后已恢复每日闹钟" : "开机后恢复每日闹钟失败"
        );
    }
}
