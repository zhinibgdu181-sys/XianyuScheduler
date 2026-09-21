package com.zhinibgdu.xianyu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/** Owns the process lifetime while TaskExecutor controls Xianyu. */
public class TaskForegroundService extends Service {
    private static final String TAG = "XianyuTaskService";
    private static final String CHANNEL_ID = "xianyu_scheduler";
    private static final int NOTIFICATION_ID = 18009;
    private static final String EXTRA_MODE = "run_mode";

    private PowerManager.WakeLock wakeLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean active;
    private boolean destroyed;
    private boolean finishing;
    private final Runnable renewWakeLock = new Runnable() {
        @Override public void run() {
            if (!active || destroyed) return;
            acquireWakeLock();
            mainHandler.postDelayed(this, 5L * 60L * 1000L);
        }
    };

    public static void start(Context context) {
        start(context, TaskCategory.ALL);
    }

    public static void start(Context context, TaskCategory category) {
        Intent intent = new Intent(context, TaskForegroundService.class);
        intent.putExtra(EXTRA_MODE, category.name());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        acquireWakeLock();
        Notification notification = buildNotification("正在准备");
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
        if (intent == null) {
            if (!active) stopSelf(startId);
            return START_NOT_STICKY;
        }
        String mode = intent.getStringExtra(EXTRA_MODE);
        TaskCategory category = TaskCategory.fromMode(mode);
        if (category == null) {
            if (!active) stopSelf(startId);
            return START_NOT_STICKY;
        }

        TaskStatusReceiver.writeLog(
                this,
                "INFO",
                "调度",
                "前台服务收到请求：" + category.label
        );

        if (active || TaskExecutor.isRunning()) {
            TaskStatusReceiver.writeLog(
                    this,
                    "INFO",
                    "调度",
                    "已有运行实例，忽略重复启动请求"
            );
            return START_NOT_STICKY;
        }

        active = true;
        mainHandler.post(renewWakeLock);
        Runnable complete = () -> mainHandler.post(this::finishAfterTaskExecutionV466);

        TaskExecutor.run(getApplicationContext(), category, complete);

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void finishAfterTaskExecutionV466() {
        if (destroyed || finishing) return;

        // V4.50.2: no automatic 90-second teaching observer exists anymore.
        // As soon as TaskExecutor finishes (including manual takeover), release
        // the service so a new task request cannot be blocked as a duplicate.
        finishing = true;
        active = false;
        mainHandler.removeCallbacks(renewWakeLock);
        TaskStatusReceiver.writeLog(
                getApplicationContext(),
                "INFO",
                "调度",
                "任务执行器已结束，立即停止前台服务；不保留90秒真人教学窗口"
        );
        stopServiceAfterCompletionSoundV472();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (active) TaskExecutor.requestStop("前台服务已销毁");
        // Explicit gesture learning owns a separate service. This task service
        // only stops its own physical monitor on destruction.
        TaskExecutor.stopHumanTeachingForServiceDestroyV466();
        active = false;
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "XianyuScheduler:TaskWakeLock"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10L * 60L * 1000L);
        } catch (Throwable t) {
            Log.e(TAG, "获取 WakeLock 失败", t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "闲鱼自动任务运行状态",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持分类自动任务在后台运行");
            manager.createNotificationChannel(channel);
        } catch (Throwable t) {
            Log.e(TAG, "创建通知渠道失败", t);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                18009,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("闲鱼定时助手")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build();
    }

    private void playTaskCompletionSoundAndStopV472() {
        final Context appContext = getApplicationContext();
        new Thread(() -> {
            ToneGenerator tone = null;
            try {
                tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100);
                boolean firstStarted = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
                Thread.sleep(260L);
                boolean secondStarted = tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180);
                Thread.sleep(260L);
                TaskStatusReceiver.writeLog(
                        appContext,
                        firstStarted && secondStarted ? "INFO" : "WARN",
                        "调度",
                        firstStarted && secondStarted
                                ? "任务完成提示音播放请求已提交（两声）"
                                : "任务完成提示音未被系统音频服务完整接受"
                );
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                TaskStatusReceiver.writeLog(
                        appContext,
                        "WARN",
                        "调度",
                        "任务完成提示音播放被中断"
                );
            } catch (Throwable t) {
                TaskStatusReceiver.writeLog(
                        appContext,
                        "ERROR",
                        "调度",
                        "任务完成提示音播放失败：" + t
                );
            } finally {
                if (tone != null) {
                    try {
                        tone.release();
                    } catch (Throwable ignored) {
                    }
                }
                mainHandler.post(this::stopServiceAfterCompletionSoundV472);
            }
        }, "XianyuCompletionTone").start();
    }

    private void stopServiceAfterCompletionSoundV472() {
        if (destroyed) return;
        stopForegroundCompat();
        stopSelf();
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
