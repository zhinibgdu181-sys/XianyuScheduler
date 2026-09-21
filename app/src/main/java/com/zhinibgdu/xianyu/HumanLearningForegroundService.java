package com.zhinibgdu.xianyu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps passive human gesture learning alive while the user switches from this
 * app to Xianyu. The recorder itself is owned by TaskExecutor so it can share
 * the already-tested root touchscreen discovery and coordinate normalization.
 */
public class HumanLearningForegroundService extends Service {
    private static final String CHANNEL_ID = "xianyu_human_learning";
    private static final int NOTIFICATION_ID = 18010;
    private boolean started;

    public static void start(Context context) {
        Intent intent = new Intent(context, HumanLearningForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, HumanLearningForegroundService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (TaskExecutor.isRunning()) {
            TaskStatusReceiver.writeLog(
                    this, "WARN", "手势细节学习", "自动任务正在运行，不能同时采集手势细节");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!started) {
            started = TaskExecutor.startStandaloneHumanLearningV450(
                    getApplicationContext());
        }

        if (!started) {
            TaskStatusReceiver.writeLog(
                    this, "FAILED", "手势细节学习", "手势细节学习启动失败，请检查 ROOT 权限");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        TaskExecutor.stopStandaloneHumanLearningV450();
        stopForegroundCompat();
        started = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "手势细节学习",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("仅学习闲鱼内单次点击/滑动动作风格，不学习任务流程");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                NOTIFICATION_ID,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("手势细节学习")
                .setContentText("仅学习点击抖动、按压和滑动距离/弧度/速度，不学习流程")
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                //noinspection deprecation
                stopForeground(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
