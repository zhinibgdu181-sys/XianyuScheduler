package com.zhinibgdu.xianyu;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** Executes selected task categories using verified page state and fixed timing. */
public final class TaskExecutor {

    private static final String TAG =
            "XianyuTaskExecutor";

    private static final String TARGET_PACKAGE =
            "com.taobao.idlefish";

    private static final String MODULE_PACKAGE =
            "com.zhinibgdu.xianyu";

    private static final String TARGET_MAIN_ACTIVITY =
            TARGET_PACKAGE
                    + "/com.taobao.fleamarket.home.activity.InitActivity";

    private static final String NOTIFICATION_CHANNEL =
            "xianyu_task_v3";

    private static final int NOTIFICATION_ID =
            18008;

    private static final String LOG_FILE_NAME =
            "xianyu_log.txt";

    private static final String UI_DUMP_PREFIX =
            "/data/local/tmp/xianyu_ui_";

    private static final long ROOT_TIMEOUT_MS =
            8000L;

    // V4.17: UIAutomator is fallback only. Never allow one dump to stall navigation
    // for the full generic root timeout.
    private static final long UI_DUMP_TIMEOUT_MS_V417 = 2800L;
    private static final int UI_DUMP_ATTEMPTS_V417 = 1;

    private static final int ROOT_PROBE_ATTEMPTS =
            2;

    private static final Pattern COMPONENT_PATTERN =
            Pattern.compile(
                    "(?:\\bu0\\s+)?([A-Za-z0-9_.$]+)/(?:[A-Za-z0-9_.$]+)"
            );

    private static final Pattern BOUNDS_PATTERN =
            Pattern.compile(
                    "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"
            );

    private static final String[] SKIP_TASK_KEYWORDS = {
            // 有明显副作用或会进入安装流程的任务默认不自动执行。
            "发布",
            "添加闲鱼币到桌面",
            "下载",
            "安装",
            "未知任务"
    };

    private static final String[] INTERNAL_BROWSE_KEYWORDS = {
            "浏览",
            "逛一逛",
            "指定频道",
            "好物",
            "福利",
            "商城",
            "会场"
    };

    private static final String[] REPEATABLE_TASK_KEYWORDS = {
            "视频",
            "指定频道",
            "浏览"
    };

    private static volatile boolean running =
            false;

    /**
     * 供 MainActivity 查询任务是否正在运行。
     */
    public static boolean isRunning() {
        return running;
    }

    private static volatile boolean userAborted =
            false;

    private static volatile boolean inBounceTask =
            false;

    private static volatile String cachedSuPath;

    private static volatile Context lastContext;

    // V4.8: physical touchscreen monitor. A real finger touch on the hardware
    // touchscreen is treated as an immediate manual takeover request.
    private static volatile boolean physicalTouchDetected = false;
    private static volatile long physicalTouchAt = 0L;
    private static volatile String physicalTouchDevice = "";
    // V4.69: touchscreen event coordinates may use a raw ABS range (e.g. 0..4095)
    // instead of physical pixels. Cache the device ranges so learned coordinates
    // are normalized before being persisted.
    private static volatile int physicalTouchMaxXV469 = 0;
    private static volatile int physicalTouchMaxYV469 = 0;
    // V4.64: the automation still stops immediately on real touch, but a passive
    // observer keeps learning from the human continuation instead of discarding it.
    private static volatile String currentExecutingTaskV464 = "";
    // V4.80: keep the verification baseline after immediate human takeover.
    private static volatile TaskVerificationSnapshotV411 lastTeachingBeforeV480;
    private static volatile boolean humanTeachingStartedV464 = false;
    private static volatile Thread humanTeachingThreadV464;
    private static volatile long humanTeachingGenerationV464 = 0L;
    // V4.67: generic passive gesture observer for every task category. It keeps
    // the foreground service alive during the teaching window and never issues input.
    private static volatile Thread humanOperationTeachingThreadV467;
    private static volatile long humanOperationTeachingGenerationV467 = 0L;
    private static final long HUMAN_TEACHING_WINDOW_MS_V464 = 90_000L;
    private static final long HUMAN_TEACHING_SAMPLE_MS_V464 = 950L;
    private static volatile Process touchMonitorProcess;
    private static volatile Thread touchMonitorThread;

    // V4.11 manual-takeover hardening: shell-generated tap/swipe events are
    // ignored only inside a very short correlation window so the monitor does
    // not stop itself on devices that echo synthetic input to getevent.
    private static volatile long syntheticInputIgnoreUntilV411 = 0L;
    private static volatile long lastSyntheticInputAtV411 = 0L;

    // V4.11 OCR cache. The cache is deliberately short lived and is invalidated
    // after any UI-affecting root command.
    private static volatile ScreenOcr.Snapshot cachedOcrV411 = ScreenOcr.Snapshot.empty();
    private static volatile long cachedOcrAtV411 = 0L;
    private static final long OCR_CACHE_MS_V411 = 350L;

    // V4.15: a verified TASK_PANEL probe is much more valuable than a generic
    // OCR cache entry. Keep it briefly so completion verification can reuse the
    // frame that was already captured by conditional-return/recovery logic.
    private static volatile ScreenOcr.Snapshot lastTaskPanelOcrV415 = ScreenOcr.Snapshot.empty();
    private static volatile long lastTaskPanelOcrAtV415 = 0L;
    private static final long TASK_PANEL_OCR_REUSE_MS_V415 = 2200L;
    // V4.16: once a return/page probe has already confirmed TASK_PANEL, the very
    // same OCR frame may be consumed by the next scan. This removes the extra
    // screenshot round-trip between one completed task and the next click.
    private static final long TASK_PANEL_CHAIN_REUSE_MS_V416 = 2600L;

    // V4.20/V4.21 game-page ownership. While a visual solver owns the current
    // Xianyu surface, generic recovery is forbidden.
    private static volatile boolean gameSolverOwnsPageV420 = false;
    private static volatile String gameSolverKindV420 = "";

    // V4.21: if a game solver SAFE_STOPs while the actual game is still visible,
    // keep the page in place. Verification/scanning must never start generic
    // back/navigation loops from an unfinished game.
    private static volatile boolean gameIncompleteHoldV421 = false;
    private static volatile String gameIncompleteKindV421 = "";
    private static volatile String gameIncompleteTaskV421 = "";

    // V4.60: explicit game-failure handoff. A confirmed failure page is not a
    // user abort and must be able to return to the task panel so other tasks continue.
    private static volatile boolean lastTaskAbandonedV460 = false;
    private static volatile String lastTaskAbandonedReasonV460 = "";

    private static final String PROFILE_PREFS_V48 = "xianyu_task_profiles_v48";
    private static final int PROFILE_SCHEMA_V411 = 411;
    private static final long FEATURE_TTL_MS_V411 = 90L * 24L * 60L * 60L * 1000L;
    private static final long TASK_COOLDOWN_MS_V411 = 10L * 60L * 1000L;
    private static final int TASK_COOLDOWN_FAILS_V411 = 3;
    private static final int RECENT_HISTORY_MAX_V411 = 12;
    private static final int MAX_DIAGNOSTIC_SCREENSHOTS_V411 = 20;
    private static final String DIAGNOSTIC_DIR_V411 =
            "/sdcard/Android/data/" + MODULE_PACKAGE + "/files/xianyu_diagnostics";
    private static final Pattern PROGRESS_PATTERN_V411 =
            Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");

    private TaskExecutor() {
    }

    private static volatile TaskCategory activeCategory = TaskCategory.ALL;
    // V4.43.8: a category may finish scanning without actually being complete.
    // Keep this result separate so execute() never returns to the scheduler app on an unresolved category.
    private static volatile boolean lastCategoryExhaustedV438 = false;

    public static String getActiveCategoryLabel() {
        return activeCategory.label;
    }

    public static void requestStop(String reason) {
        if (running) markUserAbortV48(reason == null || reason.isEmpty() ? "用户请求停止" : reason);
    }

    /**
     * V4.66: 前台服务真正销毁时，立即终止真人教学观察。
     * 教学线程不再拥有独立于服务生命周期的后台存活能力。
     */
    public static boolean isHumanTeachingActiveV466() {
        Thread fruitThread = humanTeachingThreadV464;
        Thread operationThread = humanOperationTeachingThreadV467;
        boolean fruitActive = humanTeachingStartedV464
                && fruitThread != null && fruitThread.isAlive();
        boolean operationActive = operationThread != null && operationThread.isAlive();
        return fruitActive || operationActive;
    }

    public static void stopHumanTeachingForServiceDestroyV466() {
        stopHumanTeachingCaptureV464();
        stopHumanOperationTeachingV467();
        stopPhysicalTouchMonitorV48();
    }

    public static void run(Context context) { run(context, null); }

    public static void run(Context context, Runnable complete) {
        run(context, TaskCategory.ALL, complete);
    }

    public static synchronized void run(Context context, TaskCategory category, Runnable complete) {
        if (running) return;
        if (context == null) {
            if (complete != null) complete.run();
            return;
        }
        lastContext = context.getApplicationContext();
        // A prior 90s passive observer must never overlap a new automation run.
        // In particular, its final ScreenOcr.close() must not race the new solver.
        stopHumanTeachingCaptureV464();
        LegacyDataCleanup.remove(lastContext);
        activeCategory = category == null ? TaskCategory.ALL : category;
        running = true;
        userAborted = false;
        inBounceTask = false;
        physicalTouchDetected = false;
        physicalTouchAt = 0L;
        syntheticInputIgnoreUntilV411 = 0L;
        lastSyntheticInputAtV411 = 0L;
        gameSolverOwnsPageV420 = false;
        gameIncompleteHoldV421 = false;
        gameIncompleteKindV421 = "";
        gameIncompleteTaskV421 = "";
        lastTaskAbandonedV460 = false;
        lastTaskAbandonedReasonV460 = "";
        currentExecutingTaskV464 = "";
        lastTeachingBeforeV480 = null;
        invalidateOcrCacheV411();
        lastTaskPanelOcrAtV415 = 0L;
        diagnostic("[数据路径] " + buildDataPathsLogV435(lastContext));
        diagnostic("========== " + activeCategory.label + "开始 · " + getAppVersionName(lastContext) + " ==========");
        notifyTask(lastContext, activeCategory.label, "任务已启动");
        new Thread(() -> {
            try {
                execute(lastContext);
            } catch (Throwable t) {
                diagnostic("任务线程异常", t);
                sendStatus("", "FAILED", "任务线程异常：" + t.getClass().getSimpleName());
            } finally {
                // V4.67: after human takeover, keep the physical input reader alive
                // for the passive teaching window. The foreground service owns its final
                // shutdown; before V4.67 this finally block killed the observer too early.
                if (!isHumanTeachingActiveV466()) {
                    stopPhysicalTouchMonitorV48();
                }
                running = false;
                currentExecutingTaskV464 = "";
                if (!isHumanTeachingThreadAliveV464()) {
                    ScreenOcr.close();
                }
                inBounceTask = false;
                diagnostic("========== 任务结束 ==========");
                notifyTask(lastContext, activeCategory.label, userAborted ? "任务已中止" : "任务已结束，已返回 APP");
                if (complete != null) {
                    try { complete.run(); } catch (Throwable t) { diagnostic("完成回调异常", t); }
                }
            }
        }, "XianyuTask").start();
    }

    private static void returnToApp(Context ctx, String suPath) {
        if (ctx == null) return;

        final String moduleActivity = MODULE_PACKAGE + "/.MainActivity";
        final long deadline = SystemClock.elapsedRealtime() + 9000L;

        try {
            diagnostic("[完成] 所有任务分类均已确认完成，开始返回定时任务 APP");

            // 普通 startActivity() 在 Android 新版本上可能受到后台启动限制。
            // 这里优先使用已经验证可用的 Root shell 直接启动 MainActivity，
            // 不使用 force-stop，避免把当前任务服务一起杀掉。
            for (int attempt = 1;
                    !userAborted && SystemClock.elapsedRealtime() < deadline && attempt <= 3;
                    attempt++) {

                RootResult rootLaunch = rootWithPath(
                        suPath,
                        "am start -n " + moduleActivity
                                + " -a android.intent.action.MAIN"
                                + " -c android.intent.category.LAUNCHER"
                );

                diagnostic("[完成] Root 返回定时任务 APP 尝试 #" + attempt
                        + "：exit=" + rootLaunch.exitCode
                        + "，输出=" + trimForLog(rootLaunch.stdout, 180));

                // Root 启动后给 Activity 一小段时间完成切换，然后用真实前台包名确认。
                long verifyUntil = Math.min(
                        deadline,
                        SystemClock.elapsedRealtime() + 2200L
                );
                while (!userAborted && SystemClock.elapsedRealtime() < verifyUntil) {
                    String fg = getFg(suPath, false);
                    if (MODULE_PACKAGE.equals(fg)) {
                        diagnostic("[完成] 已确认返回定时任务 APP：" + MODULE_PACKAGE);
                        return;
                    }
                    sleepAbortableV48(180L);
                }

                // 第一次 Root 启动失败时，再使用 Android Context 启动作为补偿路径。
                // 仍然不 force-stop，不会把任务服务当成“人工接管”。
                if (attempt == 1 && !userAborted) {
                    try {
                        Intent launch = ctx.getPackageManager()
                                .getLaunchIntentForPackage(MODULE_PACKAGE);
                        if (launch != null) {
                            launch.addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            );
                            ctx.startActivity(launch);
                            diagnostic("[完成] 已执行 Context.startActivity() 补偿返回");
                        }
                    } catch (Throwable fallbackError) {
                        diagnostic("[完成] Context 返回补偿失败", fallbackError);
                    }
                }
            }

            String finalFg = getFg(suPath, false);
            if (MODULE_PACKAGE.equals(finalFg)) {
                diagnostic("[完成] 已确认返回定时任务 APP：" + MODULE_PACKAGE);
            } else {
                // 这是返回动作未确认的恢复性故障，不等同于人工接管。
                // 人工接管只由真实物理触摸/明确停止请求触发。
                diagnostic("[完成] ⚠️ 已多次发起返回定时任务 APP，但仍未确认前台："
                        + printableFg(finalFg));
            }
        } catch (Throwable t) {
            diagnostic("返回本 APP 失败", t);
        }
    }

    private static void goHome(
            Context ctx
    ) {

        try {

            RootResult r =
                    rootWithPath(
                            findSuPath(),
                            "input keyevent 3"
                    );

            diagnostic(
                    "[HOME] exit="
                            + r.exitCode
            );

        } catch (Throwable t) {

            diagnostic(
                    "返回桌面失败",
                    t
            );
        }
    }

    private static void execute(
            Context ctx
    ) {

        // V4.11: prepare schema, remove duplicate/stale cases, and keep one
        // canonical record for each identical case.
        TaskProfileStoreV48.prepareV411();
        TaskProfileStoreV48.compactUniqueCases();

        String suPath =
                findSuPathWithRetry();
        cachedSuPath = suPath;

        if (suPath == null) {

            sendStatus(
                    "",
                    "FAILED",
                    "Root 未授权或不可用；请在 KernelSU → 超级用户中给闲鱼定时助手授权"
            );

            return;
        }

        RootResult id =
                rootWithPath(
                        suPath,
                        "id"
                );

        diagnostic(
                "[ROOT] id="
                        + trimForLog(
                        id.stdout,
                        300
                )
        );

        if (id.exitCode != 0
                || !id.stdout.contains("uid=0")) {

            diagnostic(
                    "❌ Root 权限验证失败"
            );

            return;
        }

        startPhysicalTouchMonitorV48(suPath);

        // Scheduled runs often fire while the display is off. Wake the display,
        // but deliberately do not bypass a secure keyguard.
        rootWithPath(suPath, "input keyevent KEYCODE_WAKEUP");
        SystemClock.sleep(500L);

        try {
            KeyguardManager km = (KeyguardManager)
                    ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                diagnostic("❌ 当前设备处于锁屏状态；为安全起见不尝试绕过锁屏");
                sendStatus("", "FAILED", "设备锁屏，无法执行 UI 自动化");
                return;
            }
        } catch (Throwable t) {
            diagnostic("检查锁屏状态失败，继续尝试执行", t);
        }

        String fg =
                getFg(
                        suPath,
                        false
                );
        boolean freshLaunchV421 = false;

        if (!TARGET_PACKAGE.equals(fg)) {

            diagnostic(
                    "当前前台="
                            + printableFg(fg)
                            + "，启动闲鱼"
            );

            rootWithPath(
                    suPath,
                    "am start -n "
                            + TARGET_MAIN_ACTIVITY
            );

            if (!waitFg(
                    suPath,
                    45000L
            )) {

                diagnostic(
                        "❌ 闲鱼未能进入前台"
                );

                return;
            }
            freshLaunchV421 = true;
        }

        // V4.21: do not pay an 8-second UIAutomator preflight before the OCR
        // navigator. On a fresh InitActivity launch the bottom “我的” tab is the
        // stable first action; destination OCR is the verification.
        if (!enterViaMineCoin(
                suPath,
                freshLaunchV421
        )) {
            return;
        }

        int completed = 0;
        boolean allEnabledCategoriesFinished = true;
        // 每次新的自动任务运行都重新计算分类完成状态。
        lastCategoryExhaustedV438 = false;
        TaskCategory requested = activeCategory;
        TaskCategory[] categories = requested == TaskCategory.ALL
                ? new TaskCategory[]{TaskCategory.LOCAL, TaskCategory.VIDEO, TaskCategory.GAME, TaskCategory.JUMP}
                : new TaskCategory[]{requested};
        for (int i = 0; i < categories.length; i++) {
            if (userAborted || gameIncompleteHoldV421) break;
            if (requested == TaskCategory.ALL && !isCategoryEnabled(ctx, categories[i])) continue;
            lastCategoryExhaustedV438 = false;
            activeCategory = categories[i];
            diagnostic("[任务分类] 开始：" + activeCategory.label);
            notifyTask(ctx, activeCategory.label, "正在扫描任务");
            if (i > 0 && !resetTaskPanelTop(suPath)) break;
            completed += scanAndExecuteTasks(suPath, ctx);
            if (!lastCategoryExhaustedV438 && !userAborted && !gameIncompleteHoldV421) {
                allEnabledCategoriesFinished = false;
                diagnostic("[任务分类] " + activeCategory.label + " 尚未确认完成，禁止提前返回定时任务 APP");
                break;
            }
        }

        // 只有所有已开启分类都明确确认“没有更多未完成任务”后，才返回定时任务 APP。
        if (!userAborted && !gameIncompleteHoldV421 && allEnabledCategoriesFinished) {
            returnToApp(ctx, suPath);
        }

        diagnostic(
                "本次完成任务数="
                        + completed
        );

        if (userAborted) {
            sendStatus(
                    "",
                    "ABORTED",
                    "人工接管，已停止；本次已验证完成 "
                            + completed
                            + " 个任务"
            );
        } else {
            sendStatus(
                    "",
                    "FINISHED",
                    "本次完成 "
                            + completed
                            + " 个任务"
            );
        }
    }

    private static boolean enterViaMineCoin(
            String suPath
    ) {
        return enterViaMineCoin(suPath, false);
    }

    private static boolean enterViaMineCoin(
            String suPath,
            boolean freshLaunchV421
    ) {

        diagnostic("[极速导航V4.26] 首页 → 我的 → 闲鱼币 → 赚骰子 → 任务面板");

        if (!ensureFg(suPath)) {
            diagnostic("[极速导航V4.26] 闲鱼没有在前台");
            return false;
        }

        // 闲鱼启动后偶尔会先展示全屏广告。先专门处理广告页，再开始导航，
        // 避免把广告当成未知子页面或直接误点右下角“我的”。
        if (!dismissOpeningAdV47(suPath)) {
            diagnostic("[广告恢复] 启动广告处理失败，继续使用安全页面探测");
        }

        PageProbeV411 page;

        // InitActivity can restore a product detail page. Identify it before tapping.
        if (freshLaunchV421) {
            if (!sleepAbortableV48(500L)) return false;
            invalidateOcrCacheV411();
            page = probePageV411(suPath, "启动后先确认当前页面");
            int deepRecovery = 0;
            while (!userAborted
                    && page != null
                    && page.kind == PageKindV411.UNKNOWN_XIANYU
                    && deepRecovery < 2) {
                deepRecovery++;
                diagnostic("[极速导航V4.26] 新启动恢复到旧子页面：" + page.kind
                        + "，执行右侧边缘返回 #" + deepRecovery);
                int[] size = getScreenSizeV43(suPath);
                int w = size == null ? 1440 : size[0];
                int h = size == null ? 3120 : size[1];
                int y = Math.max(1, Math.round(h * 0.75f));
                syntheticInputIgnoreUntilV411 = SystemClock.elapsedRealtime() + 900L;
                rootWithPath(suPath, "input swipe " + Math.max(1, w - 2) + " " + y
                        + " " + Math.max(1, Math.round(w * 0.76f)) + " " + y + " 250");
                if (!paceSleepV415(120L, 190L)) return false;
                page = probePageV411(suPath, "极速导航V4.23/旧子页面恢复#" + deepRecovery);
            }
        } else {
            page = probePageV411(suPath, "极速导航初始");
        }

        if (page.kind == PageKindV411.FRUIT_PAIR_GAME
                || page.kind == PageKindV411.MAHJONG_PAIR_GAME) {
            // 用户单独启动“小游戏任务”时，闲鱼可能恢复到上次未完成的
            // 游戏页。此时直接交回对应 Solver，而不是把游戏页当未知页面
            // 连续返回。ALL/其他分类仍保持原保护，不擅自操作遗留游戏。
            if (activeCategory == TaskCategory.GAME) {
                diagnostic("[游戏恢复V4.43.9] 小游戏模块检测到遗留游戏页，直接恢复求解："
                        + page.kind);
                boolean resumed = page.kind == PageKindV411.FRUIT_PAIR_GAME
                        ? executeFruitPairGameV418(suPath, "去消了还想消玩1关")
                        : executeMahjongPairGameV419(suPath, "点点消不停");
                if (!resumed || userAborted || gameIncompleteHoldV421) return false;
                // Solver 完成并受控返回后重新识别当前页；此调用不再属于
                // fresh launch，因此不会重复进入遗留页恢复分支。
                return enterViaMineCoin(suPath, false);
            }
            diagnostic("[游戏独占V4.26] 当前已经在小游戏页面，禁止导航流程把游戏当未知页退出");
            return false;
        }

        if (!isFastNavKnownPageV420(page.kind)) {
            diagnostic("[极速导航V4.26] 初始页=" + page.kind + "，先执行一次安全页面恢复");
            String recovered = recoverNavigationContextV45(suPath);
            if (recovered == null || userAborted) return false;
            page = probePageV411(suPath, "极速导航恢复后");
        }

        if (page.kind == PageKindV411.TASK_PANEL) {
            diagnostic("[极速导航V4.26] ✅ 已在任务面板");
            return true;
        }

        // HOME -> MINE. Home bottom navigation is stable; after HOME has been
        // positively identified, one direct proportional tap is faster than a
        // second OCR/UIAutomator pass.
        if (page.kind == PageKindV411.XIANYU_HOME) {
            diagnostic("[极速导航V4.26] 首页已确认，立即点击‘我的’");
            if (!tapByRatioV43(suPath, 0.885f, 0.950f, "极速导航-首页-我的", true)) {
                return false;
            }
            if (!paceSleepV415(300L, 460L)) return false;
            page = waitFastNavPageV420(suPath, PageKindV411.MINE, 5600L, "等待我的页");
            if (page == null) return false;
        }

        if (page.kind == PageKindV411.TASK_PANEL) return true;
        if (page.kind == PageKindV411.COIN_HOME) {
            // fall through; reuse this exact OCR frame to click earn-dice.
        } else if (page.kind == PageKindV411.MINE) {
            // MINE -> COIN_HOME. Reuse the OCR frame that confirmed MINE.
            diagnostic("[极速导航V4.26] 复用‘我的’页OCR，立即点击闲鱼币");
            boolean clickedCoin = clickOcrTextAnyV45(
                    suPath, page.ocr, false,
                    "闲鱼币", "闲鱼币中心", "赚闲鱼币", "领闲鱼币");
            if (!clickedCoin) {
                diagnostic("[极速导航V4.26] OCR未找到闲鱼币，使用已确认个人页比例坐标兜底");
                clickedCoin = tapByRatioV43(
                        suPath, 0.105f, 0.720f, "极速导航-我的-闲鱼币", false);
            }
            if (!clickedCoin) return false;

            // V4.23 optimistic chain: COIN_HOME's "赚骰子" entry is stable on
            // this layout. Avoid a full OCR round just to confirm COIN_HOME.
            // If the direct tap is too early/misses, the final task-panel probe
            // below will classify COIN_HOME and perform the normal OCR fallback.
            if (!paceSleepV415(850L, 1150L)) return false;
            diagnostic("[极速导航V4.26] 已点闲鱼币，乐观直点‘赚骰子’，减少一次整屏OCR");
            boolean optimisticEarn = tapByRatioV43(
                    suPath, 0.735f, 0.495f, "极速导航V4.23-闲鱼币-赚骰子快速点击", false);
            if (optimisticEarn && !paceSleepV415(650L, 950L)) return false;

            PageProbeV411 optimisticTask = probePageV411(suPath, "极速导航V4.23/乐观任务面板确认");
            if (optimisticTask.kind == PageKindV411.TASK_PANEL) {
                diagnostic("[极速导航V4.26] ✅ 乐观链路直接进入任务面板");
                return true;
            }
            if (optimisticTask.kind == PageKindV411.UNKNOWN_XIANYU) {
                // Coin home / task panel may still be rendering. One short retry
                // is cheaper than falling into full recovery and prevents false failures.
                if (!paceSleepV415(420L, 620L)) return false;
                optimisticTask = probePageV411(suPath, "极速导航V4.23/乐观链路短重试");
                if (optimisticTask.kind == PageKindV411.TASK_PANEL) {
                    diagnostic("[极速导航V4.26] ✅ 短重试后进入任务面板");
                    return true;
                }
            }
            page = optimisticTask;
        } else {
            diagnostic("[极速导航V4.26] 未到‘我的/闲鱼币’页面：" + page.kind);
            return false;
        }

        if (page.kind == PageKindV411.TASK_PANEL) return true;
        if (page.kind != PageKindV411.COIN_HOME) {
            diagnostic("[极速导航V4.26] 未确认闲鱼币主页，停止导航：" + page.kind);
            return false;
        }

        // COIN_HOME -> TASK_PANEL. Again, reuse the confirmation OCR frame.
        diagnostic("[极速导航V4.26] 复用闲鱼币主页OCR，立即点击‘赚骰子’");
        boolean clickedEarn = clickEarnDiceV417(suPath, page.ocr);
        if (!clickedEarn) {
            diagnostic("[极速导航V4.26] ‘赚骰子’OCR仍不稳定，使用已确认COIN_HOME比例坐标");
            clickedEarn = tapByRatioV43(
                    suPath, 0.735f, 0.495f, "极速导航-闲鱼币-赚骰子", false);
        }
        if (!clickedEarn) return false;
        if (!paceSleepV415(300L, 460L)) return false;

        PageProbeV411 task = waitFastNavPageV420(
                suPath, PageKindV411.TASK_PANEL, 5600L, "等待任务面板");
        if (task != null && task.kind == PageKindV411.TASK_PANEL) {
            diagnostic("[极速导航V4.26] ✅ 任务面板打开成功");
            return true;
        }

        // The only known dangerous mis-click is the adjacent 1-cent exchange.
        if (task != null && looksLikeCoinExchangePageV417(task.ocr)) {
            diagnostic("[极速导航V4.26] ⚠️ 误入闲鱼币兑好礼，单次右侧返回后重试");
            if (!backOneLevelToCoinHomeV417(suPath)) return false;
            PageProbeV411 coin = probePageV411(suPath, "兑换页返回后");
            if (coin.kind != PageKindV411.COIN_HOME) return false;
            if (!clickEarnDiceV417(suPath, coin.ocr)) return false;
            task = waitFastNavPageV420(
                    suPath, PageKindV411.TASK_PANEL, 6500L, "赚骰子重试");
            if (task != null && task.kind == PageKindV411.TASK_PANEL) {
                diagnostic("[极速导航V4.26] ✅ 重试后进入任务面板");
                return true;
            }
        }

        diagnostic("❌ [极速导航V4.26] 无法打开‘得骰子赚闲鱼币’任务面板");
        return false;
    }

    private static boolean dismissOpeningAdV47(String suPath) {
        if (userAborted || !ensureFg(suPath)) return false;

        long end = SystemClock.elapsedRealtime() + 6500L;
        int pass = 0;
        boolean sawAd = false;

        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted || physicalTouchDetected) return false;

            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "启动广告检查#" + (++pass));
            String text = ocr == null ? "" : ocr.fullText;
            if (text == null) text = "";

            boolean adLike = text.contains("跳转至详情页面或第三方应用")
                    || (text.contains("滑动或点击") && text.contains("第三方应用"))
                    || text.contains("跳过广告")
                    // 闲鱼启动广告经常只显示“跳过5/跳过3”，OCR 不一定带“广告”二字。
                    // dismissOpeningAdV47 只在冷启动后执行，因此这里识别“跳过”是安全的。
                    || text.contains("跳过")
                    || text.contains("广告");
            if (!adLike) {
                return true;
            }

            sawAd = true;

            // 最优先点击广告右上角的“跳过广告 N”。
            if (clickOcrTextAnyV45(suPath, ocr, false, "跳过广告", "跳过")) {
                diagnostic("[广告恢复] 已点击启动广告跳过按钮");
                sleepAbortableV48(500L);
                ScreenOcr.Snapshot after = captureOcrV45(suPath, "跳过广告后检查");
                String afterText = after == null ? "" : after.fullText;
                if (afterText == null) afterText = "";
                if (!afterText.contains("跳转至详情页面或第三方应用")
                        && !afterText.contains("滑动或点击")) {
                    return true;
                }
                continue;
            }

            // 没有跳过按钮时先等待广告倒计时结束，不直接点击广告主体。
            if (!sleepAbortableV48(700L)) return false;
        }

        if (sawAd) {
            diagnostic("[广告恢复] 广告页在等待窗口内未出现可点击的‘跳过广告’，执行一次返回");
            if (!ensureFg(suPath)) return false;
            RootResult back = rootWithPath(suPath, "input keyevent KEYCODE_BACK");
            if (back.exitCode != 0 || !sleepAbortableV48(650L)) return false;
            return ensureFg(suPath);
        }

        return true;
    }

    private static boolean isFastNavKnownPageV420(PageKindV411 kind) {
        return kind == PageKindV411.XIANYU_HOME
                || kind == PageKindV411.MINE
                || kind == PageKindV411.COIN_HOME
                || kind == PageKindV411.TASK_PANEL;
    }

    private static PageProbeV411 waitFastNavPageV420(
            String suPath,
            PageKindV411 expected,
            long timeoutMs,
            String reason
    ) {
        long end = SystemClock.elapsedRealtime() + Math.max(1200L, timeoutMs);
        int pass = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted || physicalTouchDetected) return null;
            PageProbeV411 page = probePageV411(
                    suPath, "极速导航/" + reason + "#" + (++pass));
            if (page.kind == expected || page.kind == PageKindV411.TASK_PANEL) {
                return page;
            }
            if (looksLikeCoinExchangePageV417(page.ocr)) {
                diagnostic("[极速导航V4.26] 检测到闲鱼币兑好礼，提前结束等待以便纠错");
                return page;
            }
            if (page.kind == PageKindV411.FRUIT_PAIR_GAME
                    || page.kind == PageKindV411.MAHJONG_PAIR_GAME
                    || page.kind == PageKindV411.MODULE_APP
                    || page.kind == PageKindV411.EXTERNAL_APP) {
                diagnostic("[极速导航V4.26] 等待" + expected + "时进入非导航页面：" + page.kind);
                return page;
            }
            if (!sleepAbortableV48(120L)) return null;
        }
        diagnostic("[极速导航V4.26] 等待" + expected + "超时：" + reason);
        return null;
    }

    /**
     * 在执行坐标兜底前先把闲鱼恢复到一个已知页面。
     * 这用于修复“红果免费短剧应用详情”之类的闲鱼内嵌页面被误当成首页，
     * 然后盲点右下角坐标的问题。
     */
    private static String recoverNavigationContextV45(String suPath) {

        for (int i = 0; i < 5; i++) {
            if (!ensureFg(suPath) || userAborted) return null;

            // V4.17: OCR first. Most IdleFish surfaces are WebView/Flutter-like and
            // UIAutomator is both slower and less reliable than the screenshot OCR.
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "页面恢复#" + (i + 1));
            if (isTaskPageV45(null, ocr)
                    || isCoinPageV45(null, ocr)
                    || isMinePageV45(null, ocr)
                    || isHomePageV45(null, ocr)) {
                return "";
            }

            String xml = dumpUi(suPath);
            if (isTaskPageV45(xml, ocr)
                    || isCoinPageV45(xml, ocr)
                    || isMinePageV45(xml, ocr)
                    || isHomePageV45(xml, ocr)) {
                return xml == null ? "" : xml;
            }

            String text = combinedTextV45(xml, ocr);
            diagnostic("[导航恢复V4.17] 未知闲鱼子页面，返回上一层："
                    + trimForLog(text, 300));

            rootWithPath(suPath, "input keyevent KEYCODE_BACK");
            if (!sleepAbortableV48(420L)) return null;
        }

        diagnostic("[导航恢复V4.17] 连续返回仍无法识别，重启闲鱼到主页面");
        rootWithPath(
                suPath,
                "am force-stop " + TARGET_PACKAGE
                        + "; sleep 1; am start -n " + TARGET_MAIN_ACTIVITY
        );

        if (!waitFg(suPath, 15000L)) return null;
        if (!sleepAbortableV48(650L)) return null;

        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "重启后的闲鱼");
        if (isTaskPageV45(null, ocr)
                || isCoinPageV45(null, ocr)
                || isMinePageV45(null, ocr)
                || isHomePageV45(null, ocr)) {
            return "";
        }
        String xml = dumpUi(suPath);
        if (isTaskPageV45(xml, ocr)
                || isCoinPageV45(xml, ocr)
                || isMinePageV45(xml, ocr)
                || isHomePageV45(xml, ocr)) {
            return xml == null ? "" : xml;
        }

        diagnostic("[导航恢复V4.17] 重启后仍无法识别闲鱼主页面");
        return null;
    }

    private static ScreenOcr.Snapshot captureOcrV45(
            String suPath,
            String reason
    ) {
        if (userAborted || physicalTouchDetected) {
            return ScreenOcr.Snapshot.empty();
        }
        long now = SystemClock.elapsedRealtime();
        ScreenOcr.Snapshot cached = cachedOcrV411;
        if (cached != null
                && !cached.isEmpty()
                && now - cachedOcrAtV411 >= 0L
                && now - cachedOcrAtV411 <= OCR_CACHE_MS_V411) {
            diagnostic("[OCR缓存V4.11] " + reason + "，age="
                    + (now - cachedOcrAtV411) + "ms");
            return cached;
        }

        ScreenOcr.Snapshot snapshot = ScreenOcr.capture(lastContext, suPath,
                () -> userAborted || physicalTouchDetected);
        if (snapshot != null && !snapshot.isEmpty()) {
            cachedOcrV411 = snapshot;
            cachedOcrAtV411 = now;
            diagnostic("[OCR] " + reason + "："
                    + trimForLog(snapshot.fullText, 500));
            return snapshot;
        }
        return ScreenOcr.Snapshot.empty();
    }

    private static void invalidateOcrCacheV411() {
        cachedOcrV411 = ScreenOcr.Snapshot.empty();
        cachedOcrAtV411 = 0L;
        lastTaskPanelOcrV415 = ScreenOcr.Snapshot.empty();
        lastTaskPanelOcrAtV415 = 0L;
    }

    private static String combinedTextV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        StringBuilder sb = new StringBuilder();
        if (xml != null) sb.append(xml);
        if (ocr != null && !ocr.fullText.isEmpty()) {
            sb.append('\n').append(ocr.fullText);
        }
        return sb.toString();
    }

    private static boolean isHomePageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        String text = combinedTextV45(xml, ocr);
        if (text.isEmpty()) return false;
        if (text.contains("应用详情") && text.contains("立即下载")) return false;
        if (text.contains("得骰子赚闲鱼币")) return false;
        return text.contains("首页")
                && (text.contains("我的")
                || text.contains("消息")
                || text.contains("同城")
                || text.contains("卖闲置")
                || text.contains("领 ×1")
                || text.contains("领×1"));
    }

    private static boolean isMinePageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        if (isMinePageV43(xml)) return true;
        String text = combinedTextV45(null, ocr);
        int score = 0;
        if (text.contains("我的收藏")) score++;
        if (text.contains("历史浏览")) score++;
        if (text.contains("我的关注")) score++;
        if (text.contains("我的交易")) score++;
        if (text.contains("我发布的")) score++;
        if (text.contains("我卖出的")) score++;
        if (text.contains("闲鱼币")) score++;
        return score >= 2;
    }

    private static boolean isCoinPageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        if (isCoinPageV43(xml)) return true;
        String text = combinedTextV45(null, ocr);
        int score = 0;
        if (text.contains("扔骰子寻宝")) score += 2;
        if (text.contains("赚骰子")) score += 2;
        if (text.contains("碎片收集")) score++;
        if (text.contains("背包")) score++;
        if (text.contains("1分兑换")) score++;
        if (text.contains("闲鱼币抵扣")) score++;
        if (text.contains("IP兑换")) score++;
        return score >= 2;
    }

    private static boolean isTaskPageV45(
            String xml,
            ScreenOcr.Snapshot ocr
    ) {
        // XML 能明确看到真实任务按钮时仍然直接接受。
        if (isRealTaskPage(xml)) return true;

        if (ocr == null || ocr.isEmpty()) return false;

        String text = combinedTextV45(null, ocr);
        if (text.isEmpty()) return false;

        // 广告/试玩页里经常出现“继续试玩才能领取奖励”等文案，
        // 不能再仅凭“领取奖励”四个字判断为闲鱼任务面板。
        if (looksLikeAdOrInstallPageV47(text)) return false;

        boolean hasHeader = text.contains("得骰子赚闲鱼币");
        boolean hasRewardTag = text.contains("收益+10%")
                || text.contains("收益 +10%")
                || text.contains("收益十10%")
                || text.contains("收益＋10%");

        int validActions = countValidTaskActionsOcrV47(ocr);

        // 顶部仍可见时，标题 + 一个右侧合法按钮即可。
        if (hasHeader && validActions >= 1) return true;

        // 向下滚动后标题可能离开屏幕，此时每行仍会带“收益+10%”。
        // 要求奖励标签 + 至少一个右侧合法动作按钮，避免误把广告识别为任务页。
        return hasRewardTag && validActions >= 1;
    }

    private static int countValidTaskActionsOcrV47(ScreenOcr.Snapshot snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return 0;
        int count = 0;
        for (ScreenOcr.Item item : snapshot.items) {
            if (isValidTaskActionOcrV47(snapshot, item)) count++;
        }
        return count;
    }

    private static boolean isValidTaskActionOcrV47(
            ScreenOcr.Snapshot snapshot,
            ScreenOcr.Item item
    ) {
        if (snapshot == null || item == null || item.text == null) return false;

        String raw = item.text.trim();
        if (raw.isEmpty()) return false;
        String compact = raw.replaceAll("\\s+", "");

        boolean isSignAction = "签到".equals(compact)
                || compact.endsWith("签到");

        boolean action = compact.contains("去完成")
                || compact.contains("领取奖励")
                || compact.contains("领取笑励")
                || isSignAction;
        if (!action) return false;

        // 明确排除试玩/下载广告文案。
        if (containsAny(compact,
                "试玩", "继续", "才能", "免费下载", "立即下载",
                "点击/滑动", "前往跳转", "广告", "跳过", "安装")) {
            return false;
        }

        // 真实任务按钮都位于卡片右侧。用户 1440 宽设备上中心约 x=1200，
        // 用比例而不是固定像素，可兼容其它分辨率。
        if (snapshot.width > 0) {
            float xRatio = (float) item.centerX() / (float) snapshot.width;
            if (xRatio < 0.66f) return false;
        }

        // 顶部标题/系统栏中的“签到”等误识别也不应成为任务按钮。
        if (snapshot.height > 0) {
            float yRatio = (float) item.centerY() / (float) snapshot.height;
            // 顶部“签到”按钮本来就高于普通任务行，单独放宽到 12%~40%。
            if (isSignAction) {
                if (yRatio < 0.12f || yRatio > 0.40f) return false;
            } else if (yRatio < 0.30f || yRatio > 0.975f) {
                return false;
            }
        }

        return true;
    }

    private static boolean looksLikeAdOrInstallPageV47(String text) {
        if (text == null || text.isEmpty()) return false;
        int score = 0;
        if (text.contains("试玩") || text.contains("继续试玩")) score++;
        if (text.contains("免费下载") || text.contains("立即下载")) score++;
        if (text.contains("点击/滑动前往跳转或下载应用")) score += 2;
        if (text.contains("广告")) score++;
        if (text.contains("应用详情") || text.contains("版本号：") || text.contains("开发者：")) score += 2;
        return score >= 2;
    }

    private static boolean waitMinePageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        int loop = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待我的页");
            if (isMinePageV45(null, ocr)
                    || isCoinPageV45(null, ocr)
                    || isTaskPageV45(null, ocr)) {
                return true;
            }
            // XML fallback only once when OCR is ambiguous.
            if (loop++ == 0) {
                String xml = dumpUi(suPath);
                if (isMinePageV45(xml, ocr)
                        || isCoinPageV45(xml, ocr)
                        || isTaskPageV45(xml, ocr)) return true;
            }
            if (!sleepAbortableV48(220L)) return false;
        }
        return false;
    }

    private static boolean waitCoinPageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        int loop = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待闲鱼币页");
            if (isCoinPageV45(null, ocr)) {
                diagnostic("[导航V4.17] ✅ 已确认闲鱼币主页");
                return true;
            }
            if (isTaskPageV45(null, ocr)) return true;
            if (loop++ == 0) {
                String xml = dumpUi(suPath);
                if (isCoinPageV45(xml, ocr)) {
                    diagnostic("[导航V4.17] ✅ XML兜底确认闲鱼币主页");
                    return true;
                }
                if (isTaskPageV45(xml, ocr)) return true;
            }
            if (!sleepAbortableV48(220L)) return false;
        }
        return false;
    }

    private static boolean waitTaskPageV45(String suPath, long timeout) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, timeout);
        int loop = 0;
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted) return false;
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "等待任务面板");
            if (isTaskPageV45(null, ocr)) return true;
            // Fail fast: this is the exact wrong page caused by OCR merging
            // “赚骰子 1分兑换”. The caller will return one level immediately.
            if (looksLikeCoinExchangePageV417(ocr)) return false;
            if (loop++ == 0 && ocr.isEmpty()) {
                String xml = dumpUi(suPath);
                if (isTaskPageV45(xml, ocr)) return true;
            }
            if (!sleepAbortableV48(220L)) return false;
        }
        return false;
    }

    private static boolean looksLikeCoinExchangePageV417(ScreenOcr.Snapshot ocr) {
        if (ocr == null || ocr.isEmpty()) return false;
        String text = ocr.fullText == null ? "" : ocr.fullText;
        int score = 0;
        if (text.contains("闲鱼币兑好礼")) score += 2;
        if (text.contains("每晚8点抢兑") || text.contains("开抢中")) score++;
        if (text.contains("已兑换") || text.contains("人想要")) score++;
        if (text.contains("预约") || text.contains("提醒我")) score++;
        return score >= 2;
    }

    private static boolean clickEarnDiceV417(String suPath, ScreenOcr.Snapshot ocr) {
        if (ocr == null || ocr.isEmpty()) return false;

        for (ScreenOcr.Item item : ocr.items) {
            if (item == null || item.text == null) continue;
            String t = normalizeEarnDiceTextV420(item.text);
            if (!t.contains("赚骰子")) continue;

            // A clean OCR box can be clicked at its center.
            if (!containsAny(t, "1分兑换", "1分兑換", "兑换", "兑換", "兑好物")) {
                int x = item.centerX();
                int y = item.centerY();
                diagnostic("[导航V4.17] OCR精确点击‘赚骰子’：" + item.text + " → " + x + "," + y);
                RootResult r = rootWithPath(suPath, "input tap " + x + " " + y);
                if (r.exitCode != 0) return false;
                return sleepAbortableV48(240L);
            }

            // ML Kit sometimes merges the two adjacent controls into one line:
            // “赚骰子  1分兑换”. Never click the center; click the left quarter.
            int width = Math.max(1, item.bounds.right - item.bounds.left);
            int x = item.bounds.left + Math.round(width * 0.26f);
            int y = item.centerY();
            diagnostic("[导航V4.17] OCR粘连‘赚骰子/1分兑换’，只点左侧："
                    + item.text + " → " + x + "," + y);
            RootResult r = rootWithPath(suPath, "input tap " + x + " " + y);
            if (r.exitCode != 0) return false;
            return sleepAbortableV48(240L);
        }
        return false;
    }

    private static String normalizeEarnDiceTextV420(String raw) {
        if (raw == null) return "";
        String t = raw.replace(" ", "")
                .replace("賺", "赚")
                .replace("股子", "骰子")
                .replace("股字", "骰子")
                .replace("酸子", "骰子")
                .replace("酸字", "骰子")
                .replace("般子", "骰子")
                .replace("般字", "骰子")
                .replace("骰字", "骰子");
        // OCR sometimes recognizes only one of the two characters. Constrain
        // this repair to strings beginning with “赚” so unrelated text is not changed.
        if (t.startsWith("赚") && t.length() >= 3 && !t.contains("赚骰子")) {
            char c = t.charAt(1);
            if (c == '股' || c == '酸' || c == '般' || c == '骰') {
                t = "赚骰子" + t.substring(Math.min(3, t.length()));
            }
        }
        return t;
    }

    private static boolean backOneLevelToCoinHomeV417(String suPath) {
        int[] screen = getScreenSizeV43(suPath);
        int w = screen != null && screen.length >= 2 ? screen[0] : 1440;
        int h = screen != null && screen.length >= 2 ? screen[1] : 3120;
        int sx = Math.max(1, w - 2);
        int sy = Math.max(1, Math.round(h * 0.75f));
        int ex = Math.max(1, Math.round(w * 0.76f));
        diagnostic("[导航V4.17] 单次右侧返回兑换页：" + sx + "," + sy + " → " + ex + "," + sy);
        RootResult r = rootWithPath(suPath, "input swipe " + sx + " " + sy + " " + ex + " " + sy + " 260");
        if (r.exitCode != 0) return false;
        if (!sleepAbortableV48(140L)) return false;
        return waitCoinPageV45(suPath, 4200L);
    }

    private static boolean dismissVersionUpdatePopupV461(
            String suPath,
            ScreenOcr.Snapshot snapshot
    ) {
        if (snapshot == null || snapshot.isEmpty()) return false;

        String text = combinedTextV45(null, snapshot);
        if (text == null || text.isEmpty()) return false;

        boolean versionTitle = text.contains("有新版本可以升级了")
                || text.contains("有新版本可以升级");
        if (!versionTitle) return false;

        // 必须同时存在明确的“暂不升级”操作；只看到“更新/版本”字样时不动作。
        ScreenOcr.Item dismissItem = snapshot.findBest("暂不升级", "暂不更新");
        if (dismissItem == null) {
            diagnostic("[版本弹窗V4.61] 检测到升级标题，但未找到“暂不升级”按钮，保持安全停止");
            return false;
        }

        boolean clicked = clickOcrTextAnyV45(
                suPath, snapshot, false, "暂不升级", "暂不更新");
        if (!clicked) {
            diagnostic("[版本弹窗V4.61] ⚠️ “暂不升级”点击失败，不执行其它坐标兜底");
            return false;
        }

        diagnostic("[版本弹窗V4.61] ✅ 已关闭“有新版本可以升级了”弹窗，继续当前任务流程");
        return true;
    }

    private static boolean clickOcrTextAnyV45(
            String suPath,
            ScreenOcr.Snapshot snapshot,
            boolean allowBottom,
            String... tokens
    ) {
        if (snapshot == null || snapshot.isEmpty()) return false;
        ScreenOcr.Item item = snapshot.findBest(tokens);
        if (item == null) return false;

        int x = item.centerX();
        int y = item.centerY();
        int height = snapshot.height;

        if (height > 0) {
            int gestureZone = Math.max(60, Math.round(height * 0.03f));
            if (y > height - gestureZone && !allowBottom) {
                diagnostic("[OCR点击] 位于系统手势区，取消：" + item.text);
                return false;
            }
        }

        if (!ensureFg(suPath)) return false;

        diagnostic("[OCR点击] " + item.text + " → " + x + "," + y);
        RootResult result = rootWithPath(suPath, "input tap " + x + " " + y);
        if (result.exitCode != 0) return false;
        SystemClock.sleep(900L);
        return true;
    }

    private static boolean isMinePageV43(
            String xml
    ) {

        if (xml == null
                || xml.isEmpty()) {
            return false;
        }

        int score = 0;

        if (xml.contains("我的收藏")) score++;
        if (xml.contains("历史浏览")) score++;
        if (xml.contains("我的关注")) score++;
        if (xml.contains("我的交易")) score++;
        if (xml.contains("我发布的")) score++;
        if (xml.contains("我卖出的")) score++;
        if (xml.contains("闲鱼币")) score++;

        return score >= 2;
    }

    private static boolean isCoinPageV43(
            String xml
    ) {

        if (xml == null
                || xml.isEmpty()) {
            return false;
        }

        int score = 0;

        if (xml.contains("扔骰子寻宝")) score += 2;
        if (xml.contains("赚骰子")) score += 2;
        if (xml.contains("碎片收集")) score++;
        if (xml.contains("背包")) score++;
        if (xml.contains("1分兑换")) score++;
        if (xml.contains("闲鱼币抵扣")) score++;
        if (xml.contains("IP兑换")) score++;

        return score >= 2;
    }

    private static boolean isRealTaskPage(
            String xml
    ) {

        if (xml == null
                || xml.isEmpty()) {
            return false;
        }

        boolean hasAction =
                xml.contains("领取奖励")
                        || xml.contains("去完成");

        if (!hasAction) {
            return false;
        }

        int score = 0;

        if (xml.contains("得骰子赚闲鱼币")) score += 2;
        if (xml.contains("领取奖励")) score += 2;
        if (xml.contains("去完成")) score += 2;
        if (xml.contains("签到")) score++;
        if (xml.contains("倒计时")) score++;
        if (xml.contains("看15秒视频")) score++;

        return score >= 2;
    }

    private static boolean waitMinePageV43(
            String suPath,
            long timeout
    ) {

        long end =
                SystemClock.elapsedRealtime()
                        + Math.max(0L, timeout);

        while (SystemClock.elapsedRealtime() < end) {

            if (userAborted) {
                return false;
            }

            String xml =
                    dumpUi(suPath);

            if (xml != null) {

                if (isMinePageV43(xml)
                        || isCoinPageV43(xml)
                        || isRealTaskPage(xml)) {

                    return true;
                }
            }

            SystemClock.sleep(350L);
        }

        return false;
    }

    private static boolean waitCoinPageV43(
            String suPath,
            long timeout
    ) {

        long end =
                SystemClock.elapsedRealtime()
                        + Math.max(0L, timeout);

        while (SystemClock.elapsedRealtime() < end) {

            if (userAborted) {
                return false;
            }

            String xml =
                    dumpUi(suPath);

            if (xml != null) {

                if (isCoinPageV43(xml)) {

                    diagnostic(
                            "[导航] ✅ 已确认闲鱼币主页"
                    );

                    return true;
                }

                if (isRealTaskPage(xml)) {
                    return true;
                }
            }

            SystemClock.sleep(350L);
        }

        return false;
    }

    private static boolean waitRealTaskPageV43(
            String suPath,
            long timeout
    ) {

        long end =
                SystemClock.elapsedRealtime()
                        + Math.max(0L, timeout);

        while (SystemClock.elapsedRealtime() < end) {

            if (userAborted) {
                return false;
            }

            String xml =
                    dumpUi(suPath);

            if (isRealTaskPage(xml)) {
                return true;
            }

            SystemClock.sleep(350L);
        }

        return false;
    }

    private static boolean tapByRatioV43(
            String suPath,
            float xRatio,
            float yRatio,
            String name,
            boolean allowBottom
    ) {

        int[] screen =
                getScreenSizeV43(suPath);

        if (screen == null) {

            diagnostic(
                    "[比例点击] 无法读取屏幕尺寸："
                            + name
            );

            return false;
        }

        int width =
                screen[0];

        int height =
                screen[1];

        int x =
                Math.round(
                        width * xRatio
                );

        int y =
                Math.round(
                        height * yRatio
                );

        diagnostic(
                "[比例点击] "
                        + name
                        + " → "
                        + x
                        + ","
                        + y
                        + " / "
                        + width
                        + "x"
                        + height
        );

        int gestureZone =
                Math.max(
                        60,
                        Math.round(
                                height * 0.03f
                        )
                );

        if (y > height - gestureZone) {

            if (!allowBottom) {

                diagnostic(
                        "[比例点击] 位于系统手势区，取消："
                                + name
                );

                return false;
            }

            diagnostic(
                    "[比例点击] 底部导航允许点击："
                            + name
            );
        }

        if (!ensureFg(suPath)) {
            return false;
        }

        RootResult result =
                rootWithPath(
                        suPath,
                        "input tap "
                                + x
                                + " "
                                + y
                );

        if (result.exitCode != 0) {

            diagnostic(
                    "[比例点击] 点击失败："
                            + name
                            + " exit="
                            + result.exitCode
            );

            return false;
        }

        sleepAbortableV48(450L);
        return !userAborted;
    }

    private static int[] getScreenSizeV43(
            String suPath
    ) {

        RootResult result =
                rootWithPath(
                        suPath,
                        "wm size 2>/dev/null"
                );

        if (result.exitCode != 0
                || result.stdout == null) {

            return null;
        }

        Matcher matcher =
                Pattern.compile(
                        "(\\d+)x(\\d+)"
                ).matcher(
                        result.stdout
                );

        int width = 0;
        int height = 0;

        while (matcher.find()) {

            try {

                width =
                        Integer.parseInt(
                                matcher.group(1)
                        );

                height =
                        Integer.parseInt(
                                matcher.group(2)
                        );

            } catch (Throwable ignored) {
            }
        }

        if (width <= 0
                || height <= 0) {

            return null;
        }

        if (width > height) {

            int temp = width;
            width = height;
            height = temp;
        }

        return new int[]{
                width,
                height
        };
    }

    private static boolean clickTextAny(String suPath, String xml, String... texts) {
        if (texts == null) return false;
        for (String text : texts) {
            if (text != null && !text.isEmpty() && clickText(suPath, xml, text, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bottom navigation labels (especially "我的") legitimately live very close to
     * the gesture area.  Treating every node in the last 200 px as unsafe made the
     * navigator find "我的" and then deliberately refuse to tap it.
     */
    private static boolean clickTextAnyAllowBottom(String suPath, String xml, String... texts) {
        if (texts == null) return false;
        for (String text : texts) {
            if (text != null && !text.isEmpty() && clickText(suPath, xml, text, true)) {
                return true;
            }
        }
        return false;
    }

    private static void logVisibleTexts(String xml) {
        try {
            if (xml == null) return;
            Document doc = parseXml(xml);
            if (doc == null) return;
            NodeList nodes = doc.getElementsByTagName("node");
            StringBuilder sb = new StringBuilder("[UI文本]");
            int count = 0;
            for (int i = 0; i < nodes.getLength() && count < 80; i++) {
                Node n = nodes.item(i);
                String text = getAttr(n, "text");
                String desc = getAttr(n, "content-desc");
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(" ").append(text.trim());
                    count++;
                } else if (desc != null && !desc.trim().isEmpty()) {
                    sb.append(" [").append(desc.trim()).append("]");
                    count++;
                }
            }
            diagnostic(trimForLog(sb.toString(), 4000));
        } catch (Throwable t) {
            diagnostic("读取当前 UI 文本失败", t);
        }
    }

    private static boolean isCategoryEnabled(Context context, TaskCategory category) {
        switch (category) {
            case LOCAL: return AppConfig.isLocalTaskEnabled(context);
            case VIDEO: return AppConfig.isVideoTaskEnabled(context);
            case GAME: return AppConfig.isGameTaskEnabled(context);
            case JUMP: return AppConfig.isJumpTaskEnabled(context);
            default: return false;
        }
    }

    private static boolean resetTaskPanelTop(String suPath) {
        if (userAborted || gameIncompleteHoldV421 || gameSolverOwnsPageV420) return false;
        PageProbeV411 page = probePageV411(suPath, "切换任务分类");
        if (page.kind != PageKindV411.TASK_PANEL) return false;
        int[] size = getScreenSizeV43(suPath);
        if (size == null || size.length < 2) return false;
        int w = size[0], h = size[1];
        if (w <= 0 || h <= 0) return false;
        for (int i = 0; i < 8; i++) {
            if (userAborted) return false;
            RootResult r = rootWithPath(suPath, "input swipe " + w / 2 + " " + h / 2
                    + " " + w / 2 + " " + h * 4 / 5 + " 400");
            if (r.exitCode != 0 || !sleepAbortableV48(250L)) return false;
        }
        lastTaskPanelOcrAtV415 = 0L;
        return true;
    }

    private static int scanAndExecuteTasks(
            String suPath,
            Context ctx
    ) {

        int completed = 0;
        Set<String> executed = new HashSet<>();
        Map<String, Integer> attemptsByTask = new HashMap<>();
        // V4.60: failed/abandoned game tasks are retired only for this scan run.
        // They must not block classification completion or be reselected on a changed viewport.
        Set<String> abandonedTaskNames = new HashSet<>();
        int consecutiveFail = 0;
        String exhaustedViewport = "";
        int repeatedViewport = 0;
        int consecutiveEmptyCandidateScans = 0;
        boolean categoryExhausted = false;
        boolean categoryBlockedByUnfinishedTask = false;

        for (int pass = 0; pass < 30; pass++) {

            if (userAborted) break;

            diagnostic("===== 快速扫描 " + (pass + 1) + "/30 =====");

            // V4.8: task panel is a WebView, so OCR is the fast primary path.
            // We only pay the much slower uiautomator cost when OCR is unclear.
            String xml = null;
            ScreenOcr.Snapshot taskOcr = ScreenOcr.Snapshot.empty();

            if (!ensureFg(suPath)) {
                if (userAborted) break;

                String fg = getFg(suPath, false);
                if (fg != null
                        && !fg.isEmpty()
                        && !TARGET_PACKAGE.equals(fg)
                        && !MODULE_PACKAGE.equals(fg)) {
                    diagnostic("[扫描恢复] 意外离开闲鱼：" + fg);
                    TaskProfileStoreV48.recordRecovery("__GLOBAL__", "scan_external:" + fg);
                    if (recoverToXianyuTaskPanelV47(suPath, "扫描阶段外部页面恢复")) {
                        consecutiveFail = 0;
                        sleepAbortableV48(350L);
                        continue;
                    }
                }

                consecutiveFail++;
                if (consecutiveFail >= 4) {
                    diagnostic("连续失败 4 次，退出扫描");
                    TaskProfileStoreV48.recordFailure("__GLOBAL__", "scan_fg_fail_x4");
                    break;
                }
                sleepAbortableV48(600L);
                continue;
            }

            taskOcr = consumeTaskPanelOcrForNextScanV416();
            if (taskOcr != null && !taskOcr.isEmpty()) {
                diagnostic("[连贯执行V4.16] 复用刚确认的任务面板，立即挑选下一任务");
            } else {
                taskOcr = captureOcrV45(suPath, "快速扫描任务页");
            }

            // V4.61：版本升级弹窗可能在任务扫描期间异步出现。
            // 先关闭覆盖层，再重新扫描任务页，避免把“无任务/未知页面”误判成导航失败。
            if (dismissVersionUpdatePopupV461(suPath, taskOcr)) {
                sleepAbortableV48(250L);
                continue;
            }

            // V4.30: TASK_PANEL 识别优先于小游戏关键词。
            // 任务面板本身会出现“玩游戏/小游戏”等任务文案；不能因为这些
            // 关键词存在，就把真正的 TASK_PANEL 当成游戏页并提前 SAFE_STOP。
            // 只有在“明确不是 TASK_PANEL”时，小游戏守卫才有权接管扫描流程。
            boolean taskPage = isTaskPageV45(null, taskOcr);
            String scanTextV421 = combinedTextV45(null, taskOcr);
            boolean looksLikeGameV430 =
                    FruitGameSolver.looksLikeFruitGame(scanTextV421)
                            || FruitGameSolver.looksLikeFruitStartScreen(scanTextV421)
                            || MahjongGameSolver.looksLikeMahjongPairGame(scanTextV421);

            if (!taskPage && looksLikeGameV430) {
                diagnostic("[游戏守卫V4.30] 已确认不是任务面板且检测到小游戏；停止普通扫描，禁止导航/返回乱操作"
                        + (gameIncompleteHoldV421 ? " / reason=solver_safe_stop" : ""));
                break;
            }

            if (taskPage && looksLikeGameV430) {
                diagnostic("[游戏守卫V4.30] TASK_PANEL 优先：忽略任务文案中的小游戏关键词，继续任务选择");
            }

            if (!taskPage) {
                // Reuse the same OCR frame to close common reward popups without
                // taking another screenshot or XML dump.
                if (closePopupFromSnapshotV48(suPath, taskOcr)) {
                    sleepAbortableV48(300L);
                    continue;
                }

                xml = dumpUi(suPath);
                taskPage = isTaskPageV45(xml, taskOcr);
            }

            if (!taskPage) {
                diagnostic("[任务页] OCR/XML 均不像任务页，执行安全恢复");
                TaskProfileStoreV48.recordFailure("__NAV__", "task_panel_not_recognized");
                if (!enterViaMineCoin(suPath)) {
                    sleepAbortableV48(650L);
                }
                consecutiveFail++;
                continue;
            }

            consecutiveFail = 0;

            List<TaskCandidate> candidates =
                    findTaskCandidatesOcrV45(taskOcr);

            // V4.43.8: UIAutomator 在实机 WebView 上单次通常需要约 2.4~2.9 秒。
            // OCR 已稳定识别到按钮时先走快速路径；只有 OCR 完全为空，或当前
            // OCR 视口与上轮耗尽视口相同、准备结束分类时，才做一次 XML 复核。
            // 这样保留最终防漏检查，同时避免每滚动一屏都支付 XML dump 成本。
            String ocrViewportFingerprint = taskCandidateFingerprintV4438(candidates);
            boolean finalXmlConfirmation = !ocrViewportFingerprint.isEmpty()
                    && ocrViewportFingerprint.equals(exhaustedViewport);

            // OCR 完全为空时使用 XML 恢复候选；重复视口即将结束分类时，
            // 再用 XML 合并候选完成一次保守复核。
            if (xml == null && (candidates.isEmpty() || finalXmlConfirmation)) {
                // OCR 漏掉整屏按钮时仍做 XML 兜底；到达重复视口、准备结束
                // 分类时也做一次最终复核，不能仅凭单一 OCR 帧宣称完成。
                xml = dumpUi(suPath);
            }
            if (xml != null) {
                List<TaskCandidate> xmlCandidates = findTaskCandidates(xml);
                if (!xmlCandidates.isEmpty()) {
                    Set<String> candidateKeys = new HashSet<>();
                    for (TaskCandidate existing : candidates) {
                        if (existing != null) candidateKeys.add(existing.key());
                    }
                    for (TaskCandidate extra : xmlCandidates) {
                        if (extra != null && candidateKeys.add(extra.key())) {
                            candidates.add(extra);
                        }
                    }
                }
            }

            diagnostic("候选任务=" + candidates.size());

            if (candidates.isEmpty()) {
                // OCR can miss a whole frame transiently. Require three consecutive
                // empty scans before treating a category as exhausted, so one bad
                // OCR frame cannot produce a false "category completed" message.
                consecutiveEmptyCandidateScans++;
                if (consecutiveEmptyCandidateScans >= 3) {
                    diagnostic("[任务分类] 连续3次未识别到任务，视为当前分类没有更多可执行任务："
                            + activeCategory.label);
                    categoryExhausted = true;
                    break;
                }
                if (!swipeUp(suPath)) {
                    sleepAbortableV48(500L);
                } else {
                    sleepAbortableV48(300L);
                }
                continue;
            }
            consecutiveEmptyCandidateScans = 0;

            TaskCandidate target = null;
            int targetPriority = Integer.MAX_VALUE;

            // “看到任务卡”与“仍有未完成任务”必须严格区分：
            // 已验证成功、冷却中、被安全跳过的任务卡都不应该阻止分类完成。
            // 只有真正未完成且本轮已经达到尝试上限的任务，才属于阻塞态。
            boolean hasCurrentCategoryTaskCard = false;
            boolean hasActionableCurrentCategoryTask = false;
            for (TaskCandidate c : candidates) {
                if (c == null || c.bounds().isEmpty()) continue;
                if (TaskCategory.classify(c.name) != activeCategory) continue;
                hasCurrentCategoryTaskCard = true;

                if (shouldSkip(c.name)) {
                    diagnostic("[跳过] " + c.name);
                    executed.add(c.key());
                    continue;
                }

                String candidateAttemptKey = normalizeTaskAttemptKeyV46(c.name);
                if (abandonedTaskNames.contains(candidateAttemptKey)) {
                    diagnostic("[本轮淘汰V4.60] 已淘汰小游戏任务，不再重复选择：" + c.name);
                    continue;
                }

                long cooldownRemain = TaskProfileStoreV48.cooldownRemainingMsV411(c.name);
                // 小游戏任务必须优先进入真实游戏执行器。
                // 普通任务的失败冷却只用于防止反复点击外部/浏览任务；
                // 不能因为历史冷却记录把仍显示“去完成”的小游戏直接过滤掉，
                // 否则会出现“任务卡仍在，但候选任务=0，分类被错误判定完成”。
                boolean gameTask = activeCategory == TaskCategory.GAME;
                if (cooldownRemain > 0L && !gameTask) {
                    diagnostic("[冷却V4.11] 本轮暂不执行：" + c.name
                            + "，剩余约" + Math.max(1L, cooldownRemain / 1000L) + "秒");
                    continue;
                }
                if (cooldownRemain > 0L && gameTask) {
                    diagnostic("[小游戏冷却旁路V4.44] 仍检测到小游戏任务“" + c.name
                            + "”，忽略历史冷却，继续启动游戏执行器");
                }

                String attemptKey = normalizeTaskAttemptKeyV46(c.name);
                int attempts = attemptsByTask.getOrDefault(attemptKey, 0);
                int maxAttempts = maxAttemptsForTaskV46(c.name, c.isClaimReward);

                if (attempts >= maxAttempts) {
                    categoryBlockedByUnfinishedTask = true;
                    diagnostic("[阻塞] 未完成任务已达到本轮尝试上限，不能把分类当成完成："
                            + attempts + "/" + maxAttempts + "：" + c.name);
                    continue;
                }

                // executed 只表示“已验证成功”的任务。失败/未验证任务绝不能
                // 因为曾经点击过一次就永久排除，否则会造成“任务还在，但扫描认为没任务”。
                if (!isRepeatableTaskV46(c.name)
                        && executed.contains(c.key())) {
                    continue;
                }

                hasActionableCurrentCategoryTask = true;
                int priority = taskPriorityV46(c);
                if (priority < targetPriority) {
                    target = c;
                    targetPriority = priority;
                }
            }

            if (target == null) {
                if (categoryBlockedByUnfinishedTask) {
                    diagnostic("[任务分类] 检测到仍未完成的 " + activeCategory.label
                            + " 任务，但本轮尝试已耗尽；禁止宣称分类完成，也禁止返回定时任务 APP");
                    break;
                }

                if (hasCurrentCategoryTaskCard && !hasActionableCurrentCategoryTask) {
                    diagnostic("[任务分类] 当前页面存在 " + activeCategory.label
                            + " 任务卡，但没有任何可执行任务；将继续确认页面，已完成/冷却/跳过任务不再阻塞分类完成");
                }
                String fingerprint = taskCandidateFingerprintV4438(candidates);
                repeatedViewport = fingerprint.equals(exhaustedViewport) ? repeatedViewport + 1 : 0;
                exhaustedViewport = fingerprint;
                // 第二次看到同一视口时，前面已经强制执行过一次 XML 最终复核；
                // 此时可以安全结束，不再为相同底部页面多做一整轮 OCR/XML。
                if (repeatedViewport >= 1) {
                    diagnostic("[任务分类] 连续多次扫描仍无可执行任务，确认当前分类没有更多可执行任务："
                            + activeCategory.label);
                    categoryExhausted = true;
                    break;
                }
                if (!swipeUp(suPath)) {
                    sleepAbortableV48(500L);
                } else {
                    sleepAbortableV48(300L);
                }
                continue;
            }

            repeatedViewport = 0;
            exhaustedViewport = "";
            String targetAttemptKey = normalizeTaskAttemptKeyV46(target.name);
            attemptsByTask.put(
                    targetAttemptKey,
                    attemptsByTask.getOrDefault(targetAttemptKey, 0) + 1
            );

            diagnostic("[任务] " + target.name
                    + " / claim=" + target.isClaimReward
                    + " / priority=" + targetPriority
                    + " / attempt=" + attemptsByTask.get(targetAttemptKey)
                    + " / profile=" + TaskProfileStoreV48.summary(target.name));

            TaskProfileStoreV48.recordAttempt(target.name);
            TaskRunContextV411 flow = new TaskRunContextV411(target.name);
            TaskVerificationSnapshotV411 before =
                    buildTaskVerificationSnapshotV411(taskOcr, target.name, target.isClaimReward);
            if ("UNKNOWN".equals(before.action)) {
                before = new TaskVerificationSnapshotV411(
                        before.task,
                        true,
                        target.isClaimReward ? "CLAIM" : "GO",
                        before.current,
                        before.total,
                        before.pageText
                );
            }
            flow.move(TaskRunStateV411.DISCOVERED, "before=" + before.describe());

            long taskStart = SystemClock.elapsedRealtime();
            flow.move(TaskRunStateV411.CLICKING, target.bounds());

            if (!clickBounds(suPath, xml, target.bounds())) {
                flow.move(TaskRunStateV411.FAILED, "click_failed");
                TaskProfileStoreV48.recordFailure(target.name, "click_failed");
                TeachingOutcomeStore.setTaskResult(lastContext, TeachingOutcomeStore.FAILURE, "click_failed");
                captureFailureDiagnosticV411(suPath, target.name, "click_failed");
                continue;
            }

            flow.move(TaskRunStateV411.EXECUTING,
                    target.isClaimReward ? "claim_reward" : "task_action");

            lastTaskAbandonedV460 = false;
            lastTaskAbandonedReasonV460 = "";
            currentExecutingTaskV464 = target.name;
            lastTeachingBeforeV480 = before;
            TeachingOutcomeStore.begin(lastContext, target.name, "TASK_PANEL");

            boolean executionReturned;
            if (target.isClaimReward) {
                executionReturned = paceSleepV415(120L, 240L) && !userAborted;
            } else {
                executionReturned = executeSingleTask(suPath, target.name);
            }

            if (userAborted) break;

            // V4.60: intentional terminal outcome for the current game.
            if (lastTaskAbandonedV460) {
                abandonedTaskNames.add(targetAttemptKey);
                executed.add(target.key());
                flow.move(TaskRunStateV411.UNVERIFIED, lastTaskAbandonedReasonV460);
                TaskProfileStoreV48.recordFailure(target.name, lastTaskAbandonedReasonV460);
                sendStatus(
                        target.name,
                        "FAILED",
                        "本轮已淘汰：" + lastTaskAbandonedReasonV460
                );
                diagnostic("[本轮淘汰V4.60] " + target.name
                        + " / " + lastTaskAbandonedReasonV460
                        + " / 已返回任务面板，继续扫描其它任务");
                paceSleepV415(30L, 90L);
                continue;
            }
            if (ChannelGoodsTask.matches(target.name) && !executionReturned) {
                attemptsByTask.put(targetAttemptKey, maxAttemptsForTaskV46(target.name, target.isClaimReward));
            }

            flow.move(TaskRunStateV411.VERIFYING,
                    "executionReturned=" + executionReturned);

            TaskVerificationResultV411 verification =
                    verifyTaskCompletionV411(
                            suPath,
                            target.name,
                            target.isClaimReward,
                            before,
                            executionReturned
                    );

            long elapsed = SystemClock.elapsedRealtime() - taskStart;

            if (verification.verified) {
                // 只有真正验证完成后才加入 executed。这样点击失败或验证失败的任务
                // 会在后续扫描中继续被发现、滚动到并重试。
                executed.add(target.key());
                flow.move(TaskRunStateV411.VERIFIED, verification.reason);
                completed++;
                TaskProfileStoreV48.recordSuccess(target.name, elapsed);
                TeachingOutcomeStore.setTaskResult(
                        lastContext, TeachingOutcomeStore.SUCCESS, verification.reason);
                sendStatus(
                        target.name,
                        "SUCCESS",
                        (target.isClaimReward ? "领取奖励已验证：" : "任务完成已验证：")
                                + verification.reason
                );
            } else if (!userAborted && executionReturned) {
                flow.move(TaskRunStateV411.UNVERIFIED, verification.reason);
                TaskProfileStoreV48.recordUnverifiedV411(target.name, verification.reason);
                TeachingOutcomeStore.setTaskResult(
                        lastContext, TeachingOutcomeStore.UNKNOWN, verification.reason);
                if (TaskProfileStoreV48.shouldCaptureDiagnosticV415(target.name)) {
                    captureFailureDiagnosticV411(
                            suPath, target.name, "unverified_" + verification.reason);
                } else {
                    diagnostic("[快节奏V4.15] 首次未验证，暂不阻塞保存诊断截图");
                }
                sendStatus(target.name, "FAILED",
                        "已返回，但任务进度未验证：" + verification.reason);
            } else if (!userAborted) {
                flow.move(TaskRunStateV411.FAILED, verification.reason);
                TaskProfileStoreV48.recordFailure(target.name, verification.reason);
                TeachingOutcomeStore.setTaskResult(
                        lastContext, TeachingOutcomeStore.FAILURE, verification.reason);
                if (TaskProfileStoreV48.shouldCaptureDiagnosticV415(target.name)) {
                    captureFailureDiagnosticV411(
                            suPath, target.name, "failed_" + verification.reason);
                } else {
                    diagnostic("[快节奏V4.15] 首次失败，暂不阻塞保存诊断截图");
                }
                sendStatus(target.name, "FAILED", "任务执行失败：" + verification.reason);
            }

            if (userAborted) break;

            if (gameIncompleteHoldV421) {
                diagnostic("[游戏守卫V4.29] 小游戏无法安全退出，保留当前页面并停止本轮扫描");
                break;
            }

            // V4.16: TASK_PANEL itself is the hand-off signal. Once the previous
            // task has returned here, do not wait for a delayed progress animation
            // before selecting the next visible action. Keep only a tiny UI-settle
            // window; the next scan normally consumes the already-confirmed OCR.
            if (verification.verified) {
                paceSleepV415(20L, 60L);
            } else {
                paceSleepV415(30L, 90L);
            }
        }

        if (categoryExhausted) {
            lastCategoryExhaustedV438 = true;
            String finishedMessage = activeCategory.label
                    + "已完成：当前分类没有更多可执行任务，本分类已验证完成 "
                    + completed + " 个任务";
            diagnostic("[任务分类完成] " + finishedMessage);
            sendStatus(activeCategory.label, "INFO", finishedMessage);
            notifyTask(ctx, activeCategory.label, "本分类任务已完成");
        }

        return completed;
    }

    private static String taskCandidateFingerprintV4438(List<TaskCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        StringBuilder viewport = new StringBuilder();
        for (TaskCandidate candidate : candidates) {
            if (candidate != null) viewport.append(candidate.key()).append('|');
        }
        return viewport.toString();
    }

    private static String getAppVersionName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String buildDataPathsLogV435(Context context) {
        if (context == null) return "context=null";
        try {
            Context app = context.getApplicationContext();
            File external = app.getExternalFilesDir(null);
            String externalPath = external == null ? "不可用" : external.getAbsolutePath();
            String logPath = external == null
                    ? "不可用"
                    : new File(external, LOG_FILE_NAME).getAbsolutePath();
            String diagPath = external == null
                    ? DIAGNOSTIC_DIR_V411
                    : new File(external, "xianyu_diagnostics").getAbsolutePath();
            String prefsPath = new File(
                    app.getDataDir(),
                    "shared_prefs/xianyu_records_v427.xml"
            ).getAbsolutePath();
            return "日志=" + logPath
                    + " | 数据目录=" + externalPath
                    + " | 诊断截图=" + diagPath
                    + " | 任务记录=" + prefsPath;
        } catch (Throwable t) {
            return "读取数据路径失败：" + t.getClass().getSimpleName();
        }
    }

    private static int taskPriorityV46(TaskCandidate candidate) {
        if (candidate == null) return 99;
        if (candidate.isClaimReward) return 0;

        String name = candidate.name == null ? "" : candidate.name;
        if (containsAny(name, "视频", "倒计时", "通过首页访问闲鱼币")) {
            return 1;
        }
        if (containsAny(name, INTERNAL_BROWSE_KEYWORDS)) {
            return 2;
        }
        if (isBounceTask(name)) {
            return 3;
        }
        return 2;
    }

    private static boolean isRepeatableTaskV46(String name) {
        return name != null && containsAny(name, REPEATABLE_TASK_KEYWORDS);
    }

    private static int maxAttemptsForTaskV46(String name, boolean claim) {
        if (claim) return 1;
        if (name == null) return 1;
        if (containsAny(name, "指定频道")) return 10;
        if (containsAny(name, "视频")) return 4;
        if (containsAny(name, "浏览")) return 3;
        return 1;
    }

    private static String normalizeTaskAttemptKeyV46(String name) {
        if (name == null) return "未知任务";
        return name
                .replaceAll("\\(\\d+/\\d+\\)", "")
                .replaceAll("\\d+/\\d+", "")
                .replaceAll("\\s+", "")
                .trim();
    }

    private static boolean shouldSkip(
            String n
    ) {

        if (n == null) return false;

        for (String kw :
                SKIP_TASK_KEYWORDS) {

            if (n.contains(kw)) {
                return true;
            }
        }

        return false;
    }

    private static void closePopupIfAny(
            String suPath
    ) {

        String xml =
                dumpUi(suPath);

        if (xml == null) return;

        for (String t :
                new String[]{
                        "开心收下",
                        "收下",
                        "我知道了",
                        "知道啦",
                        "关闭"
                }) {

            if (clickText(
                    suPath,
                    xml,
                    t
            )) {

                SystemClock.sleep(800L);
                return;
            }
        }
    }

    private static List<TaskCandidate>
    findTaskCandidates(
            String xml
    ) {

        List<TaskCandidate> result =
                new ArrayList<>();

        try {

            Document doc =
                    parseXml(xml);

            if (doc == null) return result;

            NodeList nodes =
                    doc.getElementsByTagName(
                            "node"
                    );

            for (int i = 0;
                 i < nodes.getLength();
                 i++) {

                Node n = nodes.item(i);

                String text =
                        getAttr(
                                n,
                                "text"
                        );

                if ("去完成".equals(text)
                        || "领取奖励".equals(text)) {

                    Node nearest =
                            findNearestTaskName(
                                    nodes,
                                    i
                            );

                    String name =
                            nearest == null
                                    ? "未知任务"
                                    : normalizeTaskName(
                                    getAttr(
                                            nearest,
                                            "text"
                                    )
                            );

                    result.add(
                            new TaskCandidate(
                                    name,
                                    n,
                                    "领取奖励".equals(text)
                            )
                    );
                }
            }

        } catch (Throwable t) {

            diagnostic(
                    "findTaskCandidates 异常",
                    t
            );
        }

        return result;
    }

    private static List<TaskCandidate> findTaskCandidatesOcrV45(
            ScreenOcr.Snapshot snapshot
    ) {
        List<TaskCandidate> result = new ArrayList<>();
        if (snapshot == null || snapshot.isEmpty()) return result;

        // 任务候选只允许来自“真正的右侧任务按钮”。
        // 这样广告里的“继续试玩才能领取奖励哦”即使被 OCR 识别，
        // 也不会再被当成领取按钮。
        for (ScreenOcr.Item action : snapshot.items) {
            if (!isValidTaskActionOcrV47(snapshot, action)) continue;

            String actionText = action.text == null ? "" : action.text.trim();
            String compact = actionText.replaceAll("\\s+", "");

            // 已领取/已完成的状态不是可点击的奖励按钮。OCR 经常把“领取成功”
            // 识别成右侧按钮文本；如果继续把它当 CLAIM，会在下一轮重复点击，
            // 甚至把任务带到 Android 外部页。成功状态必须从候选动作中排除。
            if (compact.contains("领取成功")
                    || compact.contains("已领取")
                    || compact.contains("已完成")
                    || compact.contains("已签到")
                    || compact.contains("签到成功")) {
                continue;
            }

            boolean isComplete = compact.contains("去完成");
            boolean isClaim = compact.contains("领取奖励")
                    || compact.contains("领取笑励");
            boolean isSign = "签到".equals(compact) || compact.endsWith("签到");

            String name;
            if (isSign) {
                name = "每日签到";
            } else {
                ScreenOcr.Item title = findNearestTaskTitleOcrV45(snapshot, action);
                name = title == null ? "未知任务" : normalizeTaskName(title.text);
            }

            if (name.isEmpty()) name = "未知任务";

            result.add(new TaskCandidate(
                    name,
                    action.boundsString(),
                    isClaim || isSign
            ));

            diagnostic("[OCR任务匹配] action=" + actionText
                    + " -> title=" + name
                    + " bounds=" + action.boundsString());
        }

        return result;
    }

    private static ScreenOcr.Item findNearestTaskTitleOcrV45(
            ScreenOcr.Snapshot snapshot,
            ScreenOcr.Item action
    ) {
        if (snapshot == null || action == null) return null;

        ScreenOcr.Item best = null;
        double bestScore = Double.MAX_VALUE;
        int actionCx = action.centerX();
        int actionCy = action.centerY();

        for (ScreenOcr.Item item : snapshot.items) {
            if (item == null || item == action) continue;
            String text = normalizeTaskName(item.text);
            if (text.isEmpty()) continue;

            if (containsAny(
                    text,
                    "去完成", "领取奖励", "高额奖励", "收益+10%",
                    "收益 +10%", "签到", "得骰子赚闲鱼币",
                    "闲鱼币", "骰子"
            )) {
                continue;
            }

            if (text.matches("^[+\\-0-9.%/() 次币元]+$")) continue;

            int cx = item.centerX();
            int cy = item.centerY();
            int dy = Math.abs(cy - actionCy);
            int dx = Math.abs(cx - actionCx);

            if (dy > 190) continue;
            if (cx >= actionCx - 60) continue;

            double score = dy * 5.0 + dx * 0.05;

            // 中文任务标题通常比奖励数字更长。
            if (text.length() < 4) score += 260.0;
            if (text.length() >= 6) score -= 80.0;

            if (score < bestScore) {
                bestScore = score;
                best = item;
            }
        }

        return best;
    }

    private static Node findNearestTaskName(
            NodeList nodes,
            int actionIndex
    ) {
        Node action = nodes.item(actionIndex);
        int[] actionBounds = parseBounds(getAttr(action, "bounds"));
        if (actionBounds == null) return null;

        int actionCx = (actionBounds[0] + actionBounds[2]) / 2;
        int actionCy = (actionBounds[1] + actionBounds[3]) / 2;

        Node best = null;
        double bestScore = Double.MAX_VALUE;

        for (int i = 0; i < nodes.getLength(); i++) {
            if (i == actionIndex) continue;

            Node node = nodes.item(i);
            String text = getAttr(node, "text");
            if (text == null) continue;
            text = text.trim();

            if (text.isEmpty()
                    || "去完成".equals(text)
                    || "领取奖励".equals(text)
                    || "已完成".equals(text)) {
                continue;
            }

            int[] bounds = parseBounds(getAttr(node, "bounds"));
            if (bounds == null) continue;

            int cx = (bounds[0] + bounds[2]) / 2;
            int cy = (bounds[1] + bounds[3]) / 2;
            int dy = Math.abs(cy - actionCy);
            int dx = Math.abs(cx - actionCx);

            // Task title and action button should belong to approximately the same row.
            if (dy > 320) continue;

            int overlap = Math.max(0,
                    Math.min(bounds[3], actionBounds[3])
                            - Math.max(bounds[1], actionBounds[1]));
            int minHeight = Math.max(1, Math.min(
                    bounds[3] - bounds[1],
                    actionBounds[3] - actionBounds[1]));
            double overlapRatio = Math.min(1.0, (double) overlap / minHeight);

            // Lower score is better. Vertical alignment matters much more than raw
            // Euclidean distance because task titles are normally left of buttons.
            double score = dy * 4.0 + dx * 0.12;
            score += (1.0 - overlapRatio) * 260.0;

            // Text to the right of the action button is unlikely to be the task title.
            if (cx > actionCx + 80) score += 700.0;

            score += hierarchyPenalty(node, action);

            // Suppress common short counters/labels without hard-rejecting them.
            if (text.length() <= 2) score += 120.0;
            if (text.matches("[0-9+\\-.,% ]+")) score += 220.0;

            if (score < bestScore) {
                bestScore = score;
                best = node;
            }
        }

        if (best != null) {
            diagnostic("[任务匹配] action=" + getAttr(action, "text")
                    + " -> title=" + getAttr(best, "text")
                    + " score=" + String.format(Locale.US, "%.1f", bestScore));
        }
        return best;
    }

    private static double hierarchyPenalty(Node candidate, Node action) {
        try {
            Node cp = candidate.getParentNode();
            Node ap = action.getParentNode();
            if (cp != null && cp == ap) return -320.0;

            Node cgp = cp == null ? null : cp.getParentNode();
            Node agp = ap == null ? null : ap.getParentNode();
            if (cgp != null && cgp == agp) return -180.0;

            if (cp != null && agp != null && cp == agp) return -100.0;
            if (ap != null && cgp != null && ap == cgp) return -100.0;
        } catch (Throwable ignored) {
        }
        return 0.0;
    }


    // =========================
    // V4.11 verification/state/page layer
    // =========================

    private enum TaskRunStateV411 {
        DISCOVERED,
        CLICKING,
        EXECUTING,
        RETURNING,
        VERIFYING,
        VERIFIED,
        UNVERIFIED,
        FAILED,
        COOLDOWN
    }

    private static final class TaskRunContextV411 {
        final String task;
        TaskRunStateV411 state;

        TaskRunContextV411(String task) {
            this.task = task == null ? "未知任务" : task;
        }

        void move(TaskRunStateV411 next, String detail) {
            state = next;
            diagnostic("[状态机V4.11] " + task + " -> " + next
                    + (detail == null || detail.isEmpty() ? "" : " / " + trimForLog(detail, 260)));
            TaskProfileStoreV48.recordStateV411(task, next.name(), detail);
        }
    }

    private enum PageKindV411 {
        TASK_PANEL,
        COIN_HOME,
        MINE,
        XIANYU_HOME,
        AD_OR_INSTALL,
        FRUIT_PAIR_GAME,
        MAHJONG_PAIR_GAME,
        EXTERNAL_APP,
        MODULE_APP,
        UNKNOWN_XIANYU,
        UNKNOWN
    }

    private static final class PageProbeV411 {
        final String fg;
        final ScreenOcr.Snapshot ocr;
        final String text;
        final PageKindV411 kind;
        final String marker;

        PageProbeV411(
                String fg,
                ScreenOcr.Snapshot ocr,
                String text,
                PageKindV411 kind,
                String marker
        ) {
            this.fg = fg == null ? "" : fg;
            this.ocr = ocr == null ? ScreenOcr.Snapshot.empty() : ocr;
            this.text = text == null ? "" : text;
            this.kind = kind == null ? PageKindV411.UNKNOWN : kind;
            this.marker = marker == null ? "" : marker;
        }
    }

    private static PageProbeV411 probePageV411(String suPath, String reason) {
        String fg = getFg(suPath, false);

        if (MODULE_PACKAGE.equals(fg)) {
            PageProbeV411 r = new PageProbeV411(
                    fg, ScreenOcr.Snapshot.empty(), "",
                    PageKindV411.MODULE_APP, "helper");
            TaskProfileStoreV48.observePageV411(r.kind.name(), fg, r.marker);
            return r;
        }

        if (fg != null && !fg.isEmpty() && !TARGET_PACKAGE.equals(fg)) {
            PageProbeV411 r = new PageProbeV411(
                    fg, ScreenOcr.Snapshot.empty(), "",
                    PageKindV411.EXTERNAL_APP, "external");
            TaskProfileStoreV48.observePageV411(r.kind.name(), fg, r.marker);
            return r;
        }

        if (!TARGET_PACKAGE.equals(fg)) {
            PageProbeV411 r = new PageProbeV411(
                    fg, ScreenOcr.Snapshot.empty(), "",
                    PageKindV411.UNKNOWN, "fg_unknown");
            TaskProfileStoreV48.observePageV411(r.kind.name(), fg, r.marker);
            return r;
        }

        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "页面探针V4.11/" + reason);

        // V4.61：闲鱼会在首页/任务入口异步弹出“有新版本可以升级了”。
        // 该弹窗覆盖在原页面上，若不先关闭，后续导航点击会全部落在弹窗上，
        // 表现为“任务助手没有继续执行/一直停在首页”。只接受明确的版本升级
        // 标题 + “暂不升级”按钮组合，禁止把其它升级/广告文案误当成此弹窗。
        if (dismissVersionUpdatePopupV461(suPath, ocr)) {
            ocr = captureOcrV45(suPath, "页面探针V4.61/关闭升级弹窗后");
        }

        String text = combinedTextV45(null, ocr);
        PageKindV411 kind;

        if (looksLikeAdOrInstallPageV47(text)) {
            kind = PageKindV411.AD_OR_INSTALL;
        } else if (isTaskPageV45(null, ocr)) {
            kind = PageKindV411.TASK_PANEL;
        } else if (isCoinPageV45(null, ocr)) {
            // Strong COIN_HOME evidence must win over a mere game-card title
            // such as "点点消不停" shown on the coin homepage.
            kind = PageKindV411.COIN_HOME;
        } else if (isMinePageV45(null, ocr)) {
            kind = PageKindV411.MINE;
        } else if (isHomePageV45(null, ocr)) {
            kind = PageKindV411.XIANYU_HOME;
        } else if (FruitGameSolver.looksLikeFruitGame(text)
                || FruitGameSolver.looksLikeFruitStartScreen(text)) {
            kind = PageKindV411.FRUIT_PAIR_GAME;
        } else if (MahjongGameSolver.looksLikeMahjongPairGame(text)) {
            kind = PageKindV411.MAHJONG_PAIR_GAME;
        } else {
            kind = PageKindV411.UNKNOWN_XIANYU;
        }

        String pageMarker = pageMarkerV411(text);
        if (kind == PageKindV411.TASK_PANEL && ocr != null && !ocr.isEmpty()) {
            lastTaskPanelOcrV415 = ocr;
            lastTaskPanelOcrAtV415 = SystemClock.elapsedRealtime();
        }
        TaskProfileStoreV48.observePageV411(kind.name(), fg, pageMarker);
        diagnostic("[页面特征V4.11] " + reason + " -> " + kind
                + (pageMarker.isEmpty() ? "" : " / " + pageMarker));

        return new PageProbeV411(fg, ocr, text, kind, pageMarker);
    }

    private static String pageMarkerV411(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] markers = {
                "得骰子赚闲鱼币", "继续试玩", "正在跳转", "打开淘宝",
                "闲鱼币", "我的收藏", "历史浏览", "闲鱼", "签到",
                "领取奖励", "去完成", "立即下载", "安装",
                "剩余", "消除", "打乱", "点击麻将对", "麻将对"
        };
        List<String> found = new ArrayList<>();
        for (String m : markers) {
            if (text.contains(m) && !found.contains(m)) found.add(m);
            if (found.size() >= 3) break;
        }
        if (found.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : found) {
            if (sb.length() > 0) sb.append('+');
            sb.append(s);
        }
        return sb.toString();
    }

    private static final class TaskVerificationSnapshotV411 {
        final String task;
        final boolean present;
        final String action;
        final int current;
        final int total;
        final String pageText;

        TaskVerificationSnapshotV411(
                String task,
                boolean present,
                String action,
                int current,
                int total,
                String pageText
        ) {
            this.task = task == null ? "未知任务" : task;
            this.present = present;
            this.action = action == null ? "UNKNOWN" : action;
            this.current = current;
            this.total = total;
            this.pageText = pageText == null ? "" : pageText;
        }

        String describe() {
            return "present=" + present
                    + ",action=" + action
                    + (current >= 0 && total > 0 ? ",progress=" + current + "/" + total : "");
        }
    }

    private static final class TaskVerificationResultV411 {
        final boolean verified;
        final String reason;
        final TaskVerificationSnapshotV411 after;

        TaskVerificationResultV411(
                boolean verified,
                String reason,
                TaskVerificationSnapshotV411 after
        ) {
            this.verified = verified;
            this.reason = reason == null ? "" : reason;
            this.after = after;
        }
    }

    private static TaskVerificationSnapshotV411 buildTaskVerificationSnapshotV411(
            ScreenOcr.Snapshot snapshot,
            String taskName,
            boolean knownClaim
    ) {
        if (snapshot == null || snapshot.isEmpty()) {
            int[] p = extractProgressV411(taskName);
            return new TaskVerificationSnapshotV411(
                    taskName, false, knownClaim ? "CLAIM" : "UNKNOWN",
                    p[0], p[1], "");
        }

        String targetKey = canonicalTaskKeyV411(taskName);
        List<TaskCandidate> candidates = findTaskCandidatesOcrV45(snapshot);
        TaskCandidate matched = null;

        for (TaskCandidate c : candidates) {
            if (c == null) continue;
            String ck = canonicalTaskKeyV411(c.name);
            if (sameTaskKeyV411(targetKey, ck)) {
                matched = c;
                break;
            }
        }

        String action = knownClaim ? "CLAIM" : "UNKNOWN";
        boolean present = false;
        int actionCy = -1;
        int current = -1;
        int total = -1;

        int[] fromName = extractProgressV411(taskName);
        current = fromName[0];
        total = fromName[1];

        if (matched != null) {
            present = true;
            action = matched.isClaimReward ? "CLAIM" : "GO";
            int[] b = parseBounds(matched.bounds());
            if (b != null) actionCy = (b[1] + b[3]) / 2;

            int[] pc = extractProgressV411(matched.name);
            if (pc[0] >= 0) {
                current = pc[0];
                total = pc[1];
            }
        }

        // Locate the task title even when its action button temporarily changed.
        int titleCy = -1;
        for (ScreenOcr.Item item : snapshot.items) {
            if (item == null || item.text == null) continue;
            String ik = canonicalTaskKeyV411(item.text);
            if (!ik.isEmpty() && sameTaskKeyV411(targetKey, ik)) {
                present = true;
                titleCy = item.centerY();
                int[] pc = extractProgressV411(item.text);
                if (pc[0] >= 0) {
                    current = pc[0];
                    total = pc[1];
                }
                break;
            }
        }

        int referenceY = actionCy >= 0 ? actionCy : titleCy;
        if (referenceY >= 0) {
            int bestDy = Integer.MAX_VALUE;
            for (ScreenOcr.Item item : snapshot.items) {
                if (item == null || item.text == null) continue;
                int[] pc = extractProgressV411(item.text);
                if (pc[0] < 0) continue;
                int dy = Math.abs(item.centerY() - referenceY);
                if (dy <= 170 && dy < bestDy) {
                    bestDy = dy;
                    current = pc[0];
                    total = pc[1];
                }
            }
        }

        String full = snapshot.fullText == null ? "" : snapshot.fullText;
        if (containsAny(full, "已签到") && containsAny(taskName, "签到")) {
            action = "DONE";
            present = true;
        }

        return new TaskVerificationSnapshotV411(
                taskName, present, action, current, total, full);
    }

    private static String canonicalTaskKeyV411(String task) {
        String n = normalizeTaskAttemptKeyV46(task == null ? "" : task);
        return n.replaceAll("[\\s\\p{Punct}，。！？；：、（）()【】\\[\\]·]+", "");
    }

    private static boolean sameTaskKeyV411(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        int min = Math.min(a.length(), b.length());
        return min >= 5 && (a.contains(b) || b.contains(a));
    }

    private static int[] extractProgressV411(String text) {
        if (text == null) return new int[]{-1, -1};
        Matcher m = PROGRESS_PATTERN_V411.matcher(text);
        if (!m.find()) return new int[]{-1, -1};
        try {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            return new int[]{a, b};
        } catch (Throwable ignored) {
            return new int[]{-1, -1};
        }
    }

    private static TaskVerificationResultV411 verifyTaskCompletionV411(
            String suPath,
            String taskName,
            boolean claim,
            TaskVerificationSnapshotV411 before,
            boolean executionReturned
    ) {
        if (userAborted) {
            return new TaskVerificationResultV411(false, "manual_takeover", before);
        }

        // 水果恢复耗尽后的保护不能依赖另一帧OCR成功，否则空帧会绕过保护并BACK。
        if (gameIncompleteHoldV421) {
            diagnostic("[游戏守卫V4.42] 保留游戏现场，跳过任务验证返回/导航");
            return new TaskVerificationResultV411(false, "game_incomplete_hold:" + gameIncompleteKindV421, before);
        }

        PageProbeV411 probe = probePageV411(suPath, "任务验证初始");
        if (probe.kind == PageKindV411.MODULE_APP) {
            markUserAbortV48("验证阶段检测到用户切回助手");
            return new TaskVerificationResultV411(false, "manual_takeover", before);
        }

        if ((probe.kind == PageKindV411.FRUIT_PAIR_GAME
                || probe.kind == PageKindV411.MAHJONG_PAIR_GAME)
                && gameIncompleteHoldV421) {
            diagnostic("[游戏守卫V4.29] Solver安全停止后游戏仍在前台；保留现场，不执行普通返回/导航");
            return new TaskVerificationResultV411(
                    false, "game_incomplete_hold:" + gameIncompleteKindV421, before);
        }

        if (probe.kind != PageKindV411.TASK_PANEL) {
            diagnostic("[验证V4.15] 当前不是任务面板：" + probe.kind + "，开始恢复");
            boolean recovered = conditionalBackRecoveryV410(
                    suPath, taskName, "任务完成验证返回");
            if (!recovered) {
                recovered = recoverToXianyuTaskPanelV47(
                        suPath, "任务完成验证导航恢复");
            }
            if (!recovered) {
                return new TaskVerificationResultV411(
                        false,
                        executionReturned
                                ? "returned_but_task_panel_unavailable"
                                : "execution_and_return_failed",
                        before);
            }
        }

        TaskVerificationSnapshotV411 lastAfter = null;
        boolean hasFreshPanelFrameV416 = freshTaskPanelOcrV415() != null
                && !freshTaskPanelOcrV415().isEmpty();
        // V4.16: for ordinary tasks, once return logic has already confirmed the
        // task panel, do a single zero-wait verification pass and move on. Progress
        // text can update later; it must not stall the next visible task. Claims
        // keep a second chance because their row/button often changes in place.
        int maxChecks = claim ? 2 : (hasFreshPanelFrameV416 ? 1 : 2);

        // V4.15/V4.16: the recovery/conditional-back path has just OCR-confirmed the
        // task panel in most runs. Reuse that exact frame as check #1 instead of
        // immediately taking another screenshot. Subsequent checks use short,
        // bounded variable delays so fast UI updates proceed quickly while slow
        // WebView updates still get a second/third chance.
        for (int i = 0; i < maxChecks; i++) {
            if (i > 0) {
                long min = (i == 1) ? 140L : 320L;
                long max = (i == 1) ? 260L : 520L;
                if (!paceSleepV415(min, max)) break;
            }

            ScreenOcr.Snapshot ocr;
            if (i == 0) {
                ocr = freshTaskPanelOcrV415();
                if (ocr != null && !ocr.isEmpty()) {
                    diagnostic("[连贯执行V4.16] 真实完成验证#1复用刚才任务面板OCR");
                } else {
                    ocr = captureOcrV45(suPath, "真实完成验证#1");
                }
            } else {
                ocr = captureOcrV45(suPath, "真实完成验证#" + (i + 1));
            }

            TaskVerificationSnapshotV411 after =
                    buildTaskVerificationSnapshotV411(ocr, taskName, false);
            lastAfter = after;

            diagnostic("[验证V4.15] before=" + before.describe()
                    + " / after=" + after.describe());

            if (after.current >= 0
                    && before.current >= 0
                    && after.current > before.current) {
                return new TaskVerificationResultV411(
                        true,
                        "progress_" + before.current + "_to_" + after.current,
                        after);
            }

            if ("GO".equals(before.action) && "CLAIM".equals(after.action)) {
                return new TaskVerificationResultV411(
                        true, "action_GO_to_CLAIM", after);
            }

            if ("DONE".equals(after.action)) {
                return new TaskVerificationResultV411(
                        true, "page_reports_done", after);
            }

            String text = after.pageText == null ? "" : after.pageText;
            if (claim && containsAny(
                    text, "已领取", "领取成功", "已签到", "签到成功", "开心收下"
            )) {
                return new TaskVerificationResultV411(
                        true, "claim_confirmation_text", after);
            }

            if (claim
                    && before.present
                    && !after.present
                    && isTaskPageV45(null, ocr)) {
                return new TaskVerificationResultV411(
                        true, "claimed_row_disappeared", after);
            }

            // “去浏览福利好物”本身是一个内部浏览任务：点击后留在闲鱼，
            // 按任务时长完成浏览并自动滑动，然后返回任务面板。该类任务的
            // 进度数字经常不会在返回后的首帧 OCR 中立即刷新，所以不能把
            // “仍显示去完成”误判成失败。只在执行流程已经完整返回任务面板
            // 且目标是这个确定的内部浏览任务时认定成功。
            if (executionReturned
                    && !claim
                    && lastAfter != null
                    && lastAfter.present
                    && isDeterministicInternalBrowseTaskV4432(taskName)) {
                return new TaskVerificationResultV411(
                        true, "internal_browse_returned", lastAfter);
            }
        }

        if (executionReturned && hasFreshPanelFrameV416 && !claim) {
            diagnostic("[连贯执行V4.16] 已回任务面板但进度尚未刷新，不等待；立即交给下一任务");
        }
        return new TaskVerificationResultV411(
                false,
                executionReturned ? "no_progress_change" : "execution_not_returned",
                lastAfter == null ? before : lastAfter);
    }

    private static boolean isWelfareBrowseTaskV4433(String taskName) {
        if (taskName == null) return false;
        return taskName.replaceAll("\\s+", "").contains("去浏览福利好物");
    }

    private static boolean containsBrowseCountdownV4433(String text) {
        return text != null
                && Pattern.compile("滑动浏览\\s*\\d+\\s*(?:s|秒)?",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private static String extractBrowseCountdownV4433(String text) {
        if (text == null) return "";
        Matcher m = Pattern.compile("滑动浏览\\s*\\d+\\s*(?:s|秒)?",
                Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group() : "仍在浏览";
    }

    private static boolean isDeterministicInternalBrowseTaskV4432(String taskName) {
        if (taskName == null) return false;
        String n = taskName.replaceAll("\\s+", "");
        return n.contains("去浏览福利好物");
    }

    private static ScreenOcr.Snapshot freshTaskPanelOcrV415() {
        ScreenOcr.Snapshot ocr = lastTaskPanelOcrV415;
        long age = SystemClock.elapsedRealtime() - lastTaskPanelOcrAtV415;
        if (ocr != null && !ocr.isEmpty()
                && age >= 0L && age <= TASK_PANEL_OCR_REUSE_MS_V415) {
            return ocr;
        }
        return ScreenOcr.Snapshot.empty();
    }

    private static ScreenOcr.Snapshot consumeTaskPanelOcrForNextScanV416() {
        ScreenOcr.Snapshot ocr = lastTaskPanelOcrV415;
        long age = SystemClock.elapsedRealtime() - lastTaskPanelOcrAtV415;
        if (ocr != null && !ocr.isEmpty()
                && age >= 0L && age <= TASK_PANEL_CHAIN_REUSE_MS_V416
                && isTaskPageV45(null, ocr)) {
            // Consume once. Any later scan must acquire a fresh frame unless a new
            // page probe/conditional return confirms TASK_PANEL again.
            lastTaskPanelOcrV415 = ScreenOcr.Snapshot.empty();
            lastTaskPanelOcrAtV415 = 0L;
            return ocr;
        }
        if (age > TASK_PANEL_CHAIN_REUSE_MS_V416) {
            lastTaskPanelOcrV415 = ScreenOcr.Snapshot.empty();
            lastTaskPanelOcrAtV415 = 0L;
        }
        return ScreenOcr.Snapshot.empty();
    }

    private static void captureFailureDiagnosticV411(
            String suPath,
            String task,
            String reason
    ) {
        if (suPath == null || suPath.isEmpty() || userAborted) return;

        String safeTask = safe(task)
                .replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5_-]+", "_");
        if (safeTask.length() > 28) safeTask = safeTask.substring(0, 28);
        String safeReason = safe(reason)
                .replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5_-]+", "_");
        if (safeReason.length() > 36) safeReason = safeReason.substring(0, 36);

        long now = System.currentTimeMillis();
        String file = DIAGNOSTIC_DIR_V411 + "/" + now + "_"
                + (safeTask.isEmpty() ? "task" : safeTask) + "_"
                + (safeReason.isEmpty() ? "failure" : safeReason) + ".png";

        String command = "mkdir -p " + DIAGNOSTIC_DIR_V411
                + "; screencap -p " + file
                + "; ls -1t " + DIAGNOSTIC_DIR_V411
                + "/*.png 2>/dev/null | tail -n +"
                + (MAX_DIAGNOSTIC_SCREENSHOTS_V411 + 1)
                + " | while read f; do rm -f \\\"$f\\\"; done";

        RootResult r = rootWithPath(suPath, command);
        String fg = getFg(suPath, false);
        TaskProfileStoreV48.recordDiagnosticV411(
                task, reason, fg, r.exitCode == 0 ? file : "");
        diagnostic("[诊断V4.11] " + (r.exitCode == 0 ? "已保存：" + file : "截图失败"));
    }

    private enum GameDispatchV420 { NONE, FRUIT, MAHJONG }

    private static GameDispatchV420 resolveGameDispatchV420(
            String suPath,
            String taskName
    ) {
        String n = normalizeGameTaskNameV420(taskName);
        GameDispatchV420 titleHint = GameDispatchV420.NONE;

        // Fruit task OCR is known to fluctuate: “消” can become “渭/清/潸”.
        // The stable semantic stem is “消了还想” + one-level play intent.
        if (n.contains("消了还想") || n.contains("还想消玩1关")) {
            titleHint = GameDispatchV420.FRUIT;
        } else if (n.contains("点点消不停")
                || n.contains("点点消不")
                || (n.contains("点点") && n.contains("玩1关"))) {
            titleHint = GameDispatchV420.MAHJONG;
        }

        boolean gameLike = titleHint != GameDispatchV420.NONE
                || n.contains("玩1关")
                || n.contains("小游戏")
                || n.contains("玩游戏");
        if (!gameLike) return GameDispatchV420.NONE;

        // Page truth outranks title OCR. The click already happened before this
        // function is called, so one OCR frame can identify the actual game.
        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "游戏页面分流V4.20");
        String text = combinedTextV45(null, ocr);
        if (FruitGameSolver.looksLikeFruitGame(text)) {
            return GameDispatchV420.FRUIT;
        }
        if (MahjongGameSolver.looksLikeMahjongPairGame(text)) {
            return GameDispatchV420.MAHJONG;
        }

        // If the page is still loading/animation-heavy, retain a strong title
        // hint. The solver performs its own visual validation and will SAFE_STOP
        // rather than issuing generic navigation gestures on a wrong page.
        if (titleHint != GameDispatchV420.NONE) {
            diagnostic("[页面分流V4.20] 页面OCR暂不明确，保留任务标题提示=" + titleHint);
            return titleHint;
        }
        return GameDispatchV420.NONE;
    }

    private static String normalizeGameTaskNameV420(String raw) {
        if (raw == null) return "";
        return raw.replace(" ", "")
                .replace("壹", "1")
                .replace("I关", "1关")
                .replace("l关", "1关")
                .replace("１关", "1关")
                .replace("點點", "点点")
                .replace("還想", "还想");
    }

    private static void beginGameSolverOwnershipV420(String kind, String taskName) {
        gameSolverOwnsPageV420 = true;
        gameSolverKindV420 = kind == null ? "GAME" : kind;
        diagnostic("[游戏独占V4.26] LOCK " + gameSolverKindV420 + " / " + taskName
                + "；普通恢复暂停");
    }

    private static void endGameSolverOwnershipV420(String taskName) {
        String old = gameSolverKindV420;
        gameSolverKindV420 = "";
        gameSolverOwnsPageV420 = false;
        diagnostic("[游戏独占V4.26] UNLOCK " + old + " / " + taskName);
    }

    private static boolean executeChannelGoodsTask(String suPath) {
        return ChannelGoodsTask.run(new ChannelGoodsTask.Host() {
            final Set<String> visited = new HashSet<>();
            ScreenOcr.Snapshot list = ScreenOcr.Snapshot.empty();

            ScreenOcr.Snapshot fresh(String reason) {
                invalidateOcrCacheV411();
                return captureOcrV45(suPath, "好物点击/" + reason);
            }

            @Override public boolean aborted() { return userAborted || physicalTouchDetected; }
            @Override public void log(String message) { diagnostic("[好物点击] " + message); }

            @Override public int remaining() {
                if (aborted() || !ensureFg(suPath)) return -1;
                // Allow the channel/counter to finish loading before acting.
                for (int i = 0; i < 3; i++) {
                    if (!sleepAbortableV48(650L)) return -1;
                    list = fresh("读取剩余数量");
                    int n = ChannelGoodsTask.remaining(list.fullText);
                    if (n >= 0) return n;
                }
                return -1;
            }

            @Override public boolean openNextProduct() {
                for (int page = 0; page < 4 && !aborted(); page++) {
                    if (ChannelGoodsTask.remaining(list.fullText) < 0) return false;
                    for (ScreenOcr.Item item : list.items) {
                        String key = item.text.replaceAll("\\s+", "");
                        if (!ChannelGoodsTask.productTitle(key) || visited.contains(key)) continue;
                        if (item.bounds.left < 0 || item.bounds.right > list.width
                                || item.bounds.top < list.height * 0.22f
                                || item.bounds.bottom > list.height * 0.91f) continue;
                        if (!ensureFg(suPath) || aborted()) return false;
                        visited.add(key);
                        log("打开商品：" + key);
                        RootResult tap = rootWithPath(suPath, "input tap " + item.centerX() + " " + item.centerY());
                        if (tap.exitCode != 0) return false;
                        for (int retry = 0; retry < 3; retry++) {
                            if (!sleepAbortableV48(900L) || !ensureFg(suPath)) return false;
                            ScreenOcr.Snapshot detail = fresh("确认商品详情");
                            if (ChannelGoodsTask.remaining(detail.fullText) < 0
                                    && containsAny(detail.fullText, "我想要", "立即购买", "聊一聊")) {
                                log("已进入商品详情，停留后返回；不操作购买或聊天按钮");
                                return sleepAbortableV48(1800L);
                            }
                        }
                        log("未能确认商品详情，停止本轮");
                        return false;
                    }
                    if (page == 3) break;
                    if (!ensureFg(suPath) || aborted()) return false;
                    int x = list.width / 2;
                    RootResult swipe = rootWithPath(suPath, "input swipe " + x + " "
                            + list.height * 4 / 5 + " " + x + " " + list.height * 2 / 5 + " 420");
                    if (swipe.exitCode != 0 || !sleepAbortableV48(700L)) return false;
                    list = fresh("查找未访问商品");
                }
                log("没有可靠识别到新的商品标题，停止点击");
                return false;
            }

            @Override public boolean returnToList() {
                if (aborted() || !ensureFg(suPath)) return false;
                // Exactly one back from a verified detail page. No blind repeated back.
                return preferredRightBackOnceV410(suPath, "商品详情返回频道")
                        && sleepAbortableV48(800L);
            }
        });
    }

    private static boolean executeSingleTask(
            String suPath,
            String taskName
    ) {

        diagnostic("[执行] " + taskName);

        TeachingOutcomeStore.setScene(lastContext, "TASK_DETAIL");

        if (TaskCategory.classify(taskName) == TaskCategory.VIDEO) {
            TeachingOutcomeStore.setScene(lastContext, "VIDEO_TASK");
            return executeVideoTaskPolling(suPath, taskName);
        }
        if (ChannelGoodsTask.matches(taskName)) {
            TeachingOutcomeStore.setScene(lastContext, "CHANNEL_GOODS");
            return executeChannelGoodsTask(suPath);
        }
        GameDispatchV420 gameDispatch = resolveGameDispatchV420(suPath, taskName);
        if (gameDispatch == GameDispatchV420.FRUIT) {
            TeachingOutcomeStore.setScene(lastContext, "FRUIT_PAIR_GAME");
            diagnostic("[页面分流V4.20] " + taskName + " → FRUIT_PAIR_GAME");
            return executeFruitPairGameV418(suPath, taskName);
        }
        if (gameDispatch == GameDispatchV420.MAHJONG) {
            diagnostic("[页面分流V4.20] " + taskName + " → MAHJONG_PAIR_GAME");
            TeachingOutcomeStore.setScene(lastContext, "MAHJONG_PAIR_GAME");
            return executeMahjongPairGameV419(suPath, taskName);
        }

        if (activeCategory == TaskCategory.GAME) {
            diagnostic("[小游戏] 无法确认受支持的游戏页面，停止本轮并保留现场");
            gameIncompleteHoldV421 = true;
            gameIncompleteKindV421 = "UNKNOWN_GAME";
            gameIncompleteTaskV421 = taskName;
            return false;
        }
        boolean isSearch = containsAny(taskName, "搜一搜", "搜索", "搜商品");
        boolean isBounce = isBounceTask(taskName);
        boolean isInternalBrowse = !isBounce
                && containsAny(taskName, INTERNAL_BROWSE_KEYWORDS);


        long defaultWaitMs = defaultTaskWaitV415(
                taskName, isSearch, isBounce, isInternalBrowse);

        TaskProfileStoreV48.StrategyV49 strategy =
                TaskProfileStoreV48.chooseStrategyV49(taskName, defaultWaitMs, isBounce, false);
        long explicitRequired = explicitSecondsRequirementV415(taskName);
        long minSafeWait = minimumTaskWaitV415(taskName, isBounce, isInternalBrowse);
        long waitMs;
        if (explicitRequired > 0L) {
            long explicitBase = explicitRequired + 950L;
            waitMs = fixedDuration(
                    explicitBase,
                    explicitRequired + 650L,
                    Math.min(45000L, explicitRequired + 1700L));
        } else {
            waitMs = fixedDuration(strategy.waitMs, minSafeWait, 15000L);
        }
        diagnostic("[策略V4.15] " + strategy.describe()
                + " / adaptive=" + waitMs + "ms"
                + (explicitRequired > 0L ? " / required=" + explicitRequired + "ms" : ""));

        if (isBounce) {
            inBounceTask = true;
            diagnostic("[执行] 允许预期外部 App 跳转：" + taskName);
        }

        long started = SystemClock.elapsedRealtime();

        try {
            // 15 秒内部浏览期间约每 2.5 秒滑动一次。
            long nextBrowseSwipe = fixedDuration(2500L, 2300L, 2700L);
            long nextFgCheck = 0L;
            long systemTransitSince = 0L;
            long nextBrowseCompletionProbe = 15000L;
            int browseCompletionMisses = 0;

            // 闲鱼“滑动浏览15s”页面实际存在一个独立倒计时。
            // 15 秒是最低要求，不等于我们的自动化可以在 15 秒整立即退出。
            // 日志已证明 15 秒结束时页面仍显示“滑动浏览8s/7s”，所以继续等待
            // 直到倒计时消失；最多给 45 秒保护上限，避免页面异常时无限等待。
            boolean welfareBrowse = isWelfareBrowseTaskV4433(taskName);
            long effectiveWaitMs = welfareBrowse ? Math.max(waitMs, 45000L) : waitMs;

            while (SystemClock.elapsedRealtime() - started < effectiveWaitMs) {
                if (!paceSleepV415(170L, 290L)) return false;

                long elapsed = SystemClock.elapsedRealtime() - started;

                if (elapsed >= nextFgCheck) {
                    String fg = getFg(suPath, false);
                    nextFgCheck = elapsed + 700L;

                    if (MODULE_PACKAGE.equals(fg)) {
                        markUserAbortV48("检测到用户切回闲鱼定时助手");
                        return false;
                    }

                    // If a bounce task is stuck on Android's system transition
                    // surface for a sustained period, waiting the full task timer
                    // does not help. Move to return/verification early. Brief
                    // transition flashes are ignored.
                    if (isBounce && isSystemTransitFgV415(fg)) {
                        if (systemTransitSince == 0L) systemTransitSince = elapsed;
                        if (elapsed >= 2600L && elapsed - systemTransitSince >= 1600L) {
                            diagnostic("[快节奏V4.15] 系统中转页持续 "
                                    + (elapsed - systemTransitSince)
                                    + "ms，提前进入返回验证");
                            break;
                        }
                    } else {
                        systemTransitSince = 0L;
                    }
                }

                if (isInternalBrowse && elapsed >= nextBrowseSwipe
                        && (!welfareBrowse || elapsed < 42000L)) {
                    String fg = getFg(suPath, false);
                    if (MODULE_PACKAGE.equals(fg)) {
                        markUserAbortV48("浏览任务期间用户接管");
                        return false;
                    }

                    // 福利浏览接近结束时不要再发无意义滑动，避免已经完成的页面
                    // 被继续拖动；先让倒计时自然归零，再立即进入返回流程。
                    boolean browseNearCompletion = welfareBrowse
                            && elapsed >= 15000L
                            && browseCompletionMisses >= 0;
                    if (TARGET_PACKAGE.equals(fg) && !browseNearCompletion) {
                        rootWithPath(
                                suPath,
                                "input swipe 720 2250 720 1050 420"
                        );
                        diagnostic("[执行] 内部浏览滑动，elapsed=" + elapsed + "ms");
                    } else if (welfareBrowse && browseNearCompletion) {
                        diagnostic("[福利浏览V4.43.5] 已进入完成确认阶段，暂停继续滑动");
                    }
                    nextBrowseSwipe += 2500L;
                }

                // 15 秒后开始确认闲鱼自己的“滑动浏览N秒”倒计时。
                // 一旦确认倒计时已经消失，立即返回；只有 OCR 偶发漏识别时才需要
                // 第二次确认。这样完成后不会额外等待几十秒。
                if (welfareBrowse && elapsed >= nextBrowseCompletionProbe) {
                    ScreenOcr.Snapshot browseProbe =
                            captureOcrV45(suPath, "福利浏览倒计时确认");
                    String browseText = combinedTextV45(null, browseProbe);

                    // V4.43.7: 完成态以右下角“小黄鱼”任务浮层消失为准。
                    // OCR 可能把商品正文中的“滑动浏览9s”误识别为倒计时，即使真正
                    // 的任务浮层已经消失；此时继续等待会把已完成任务卡死到保护上限。
                    if (elapsed >= 15000L && !browseProbe.welfareFishVisible) {
                        diagnostic("[福利浏览V4.43.7] ✅ 小黄鱼浮层已消失，确认浏览完成；忽略残留OCR倒计时："
                                + extractBrowseCountdownV4433(browseText));
                        break;
                    }

                    if (containsBrowseCountdownV4433(browseText)) {
                        browseCompletionMisses = 0;
                        diagnostic("[福利浏览V4.43.7] 任务倒计时仍存在，继续等待："
                                + extractBrowseCountdownV4433(browseText)
                                + " / 小黄鱼=" + (browseProbe.welfareFishVisible ? "显示" : "未显示"));
                    } else {
                        browseCompletionMisses++;
                        diagnostic("[福利浏览V4.43.7] 未识别到倒计时，确认次数="
                                + browseCompletionMisses + "/2"
                                + " / 小黄鱼=" + (browseProbe.welfareFishVisible ? "显示" : "未显示"));
                        // 15 秒后倒计时消失 + 连续两次确认即可返回。
                        if (browseCompletionMisses >= 2 && elapsed >= 15000L) {
                            diagnostic("[福利浏览V4.43.7] ✅ 已确认浏览完成，立即进入返回任务面板");
                            break;
                        }
                    }
                    nextBrowseCompletionProbe += 1000L;
                }
            }

            if (userAborted) return false;

            String fg = getFg(suPath, false);
            diagnostic("[执行] 前台=" + printableFg(fg));

            // Critical V4.8 fix: never relaunch Xianyu after the user has
            // explicitly switched to the helper app.
            if (MODULE_PACKAGE.equals(fg)) {
                markUserAbortV48("任务等待结束时检测到用户切回助手");
                return false;
            }

            boolean recoveredTaskPanelV415 = false;
            if (!TARGET_PACKAGE.equals(fg)) {
                diagnostic("[执行V4.20] 外部页结束，使用固定返回流程并验证任务面板");
                TaskProfileStoreV48.recordRecovery(taskName, "return_from:" + printableFg(fg));
                if (!recoverToXianyuTaskPanelV47(suPath, "任务执行结束快速返回")) {
                    TaskProfileStoreV48.recordFailure(taskName, "return_to_xianyu_failed");
                    return false;
                }
                recoveredTaskPanelV415 = true;
                paceSleepV415(20L, 70L);
            }

            if (recoveredTaskPanelV415 && !isSearch) {
                diagnostic("[连贯执行V4.16] 已验证回到任务面板，立即交给完成验证/下一任务");
                return true;
            }

            if (isSearch) {
                rootWithPath(suPath, "input keyevent 4");
                if (!paceSleepV415(260L, 480L)) return false;
            }

            // OCR-first completion check. uiautomator is only fallback now.
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "任务完成快速确认");
            String combined = combinedTextV45(null, ocr);

            if (closePopupFromSnapshotV48(suPath, ocr)) {
                return true;
            }

            if (containsAny(
                    combined,
                    "已完成", "任务完成", "完成任务", "已领取"
            )) {
                return true;
            }

            if (isTaskPageV45(null, ocr)) {
                return true;
            }

            String xml = dumpUi(suPath);
            if (xml != null && containsAny(
                    xml,
                    "已完成", "任务完成", "完成任务", "已领取",
                    "得骰子赚闲鱼币"
            )) {
                return true;
            }

            // Returning to Xianyu only means this execution stage finished.
            // V4.11 does NOT count it as task success here; the outer verifier
            // must confirm progress/reward state changed before recordSuccess().
            return TARGET_PACKAGE.equals(getFg(suPath, false));

        } finally {
            if (isBounce) inBounceTask = false;
        }
    }

    private static boolean executeFruitPairGameV418(
            String suPath,
            String taskName
    ) {
        diagnostic("[水果V4.42.0] 启动可恢复栈式二消求解器：" + taskName);
        if (!paceSleepV415(260L, 420L)) return false;

        gameIncompleteHoldV421 = false;
        gameIncompleteKindV421 = "";
        gameIncompleteTaskV421 = "";
        beginGameSolverOwnershipV420("FRUIT_PAIR_GAME", taskName);

        FruitGameSolver.Result result = FruitGameSolver.Result.SAFE_STOP_CLEAN;
        PageKindV411 finalPage = PageKindV411.UNKNOWN;
        try {
            // Solver内部已经包含截图、OCR和槽位恢复。外层不再重复启动第二段，
            // 避免稳定无动作后再次耗费约20~30秒；功能按钮仍禁止点击。
            for (int segment = 1; segment <= 1; segment++) {
                diagnostic("[水果V4.44.1] 求解段 " + segment + "/1");
                try {
                    result = FruitGameSolver.solveOneRound(
                            lastContext,
                            suPath,
                            new FruitGameSolver.Host() {
                                private int observedWidth;
                                private int observedHeight;

                                @Override
                                public void onFrameSize(int width, int height) {
                                    observedWidth = width;
                                    observedHeight = height;
                                }

                                @Override
                                public boolean tap(int x, int y, String reason) {
                                    if (userAborted || physicalTouchDetected) return false;
                                    if (!GameTapPolicy.allows(x, y, observedWidth, observedHeight, reason)) {
                                        diagnostic("[水果V4.42.0] 拒绝越界/非白名单点击：" + reason
                                                + " @" + x + "," + y + " / " + observedWidth + "x" + observedHeight);
                                        return false;
                                    }
                                    // Use the detected center exactly; jitter can cross narrow sprite boundaries.
                                    RootResult r = rootWithPath(suPath, "input tap " + x + " " + y);
                                    diagnostic("[水果V4.42.0] 点击 " + reason + " → " + x + "," + y);
                                    return r.exitCode == 0 && !userAborted;
                                }

                                @Override
                                public boolean swipe(
                                        int x1, int y1,
                                        int x2, int y2,
                                        long durationMs,
                                        String reason
                                ) {
                                    if (userAborted || physicalTouchDetected) return false;
                                    if (!GameTapPolicy.allows(x1, y1, observedWidth, observedHeight, reason)
                                            || !GameTapPolicy.allows(
                                            x2, y2, observedWidth, observedHeight, reason)) {
                                        diagnostic("[水果V4.45.5] 拒绝越界/非白名单滑动："
                                                + reason + " "
                                                + x1 + "," + y1 + "→" + x2 + "," + y2);
                                        return false;
                                    }
                                    long duration = Math.max(120L, Math.min(420L, durationMs));
                                    RootResult r = rootWithPath(
                                            suPath,
                                            "input swipe " + x1 + " " + y1 + " "
                                                    + x2 + " " + y2 + " " + duration
                                    );
                                    diagnostic("[水果V4.45.5] 滑动 " + reason + " → "
                                            + x1 + "," + y1 + "→" + x2 + "," + y2
                                            + " / " + duration + "ms");
                                    return r.exitCode == 0 && !userAborted;
                                }

                                @Override
                                public boolean sleep(long minMs, long maxMs) {
                                    return paceSleepV415(minMs, maxMs);
                                }

                                @Override
                                public boolean aborted() {
                                    return userAborted || physicalTouchDetected;
                                }

                                @Override
                                public void log(String message) {
                                    diagnostic(message);
                                }

                                @Override
                                public ScreenOcr.Snapshot ocr(String reason) {
                                    invalidateOcrCacheV411();
                                    ScreenOcr.Snapshot snapshot = captureOcrV45(suPath, reason);
                                    if (snapshot != null && snapshot.width > 0 && snapshot.height > 0) {
                                        observedWidth = snapshot.width;
                                        observedHeight = snapshot.height;
                                    }
                                    return snapshot;
                                }
                            }
                    );

                } catch (RuntimeException e) {
                    if (userAborted || physicalTouchDetected) return false;
                    diagnostic("[水果V4.42] Solver异常，先确认页面: " + e.getClass().getSimpleName());
                    result = FruitGameSolver.Result.SAFE_STOP_DIRTY;
                }
                if (result == FruitGameSolver.Result.ABORTED
                        || result == FruitGameSolver.Result.COMPLETED) break;

                // 停止后只确认实际页面，不再启动重复Solver。
                finalPage = inspectFruitPageV442(suPath, "安全停止#" + segment);
                if (userAborted || physicalTouchDetected) return false;
                if (finalPage != PageKindV411.FRUIT_PAIR_GAME) break;
            }
        } finally {
            endGameSolverOwnershipV420(taskName);
        }

        if (result == FruitGameSolver.Result.ABORTED) return false;

        if (result == FruitGameSolver.Result.COMPLETED) {
            TeachingOutcomeStore.setGameResult(
                    lastContext, TeachingOutcomeStore.SUCCESS, "fruit_game_completed");
            diagnostic("[水果V4.36] ✅ 水果第1关完成，执行受控返回到任务面板");
            TaskProfileStoreV48.recordRecovery(taskName, "fruit_game_completed");
            return conditionalBackRecoveryV410(suPath, taskName, "水果游戏完成返回");
        }

        if (result == FruitGameSolver.Result.GAME_FAILED) {
            TeachingOutcomeStore.setGameResult(
                    lastContext, TeachingOutcomeStore.FAILURE, "fruit_game_failed");
            boolean returned = exitFailedFruitGameV460(suPath, taskName);
            if (returned) {
                lastTaskAbandonedV460 = true;
                lastTaskAbandonedReasonV460 = "fruit_game_failed_page";
                gameIncompleteHoldV421 = false;
                gameIncompleteKindV421 = "";
                gameIncompleteTaskV421 = "";
                diagnostic("[水果V4.60] ✅ 失败页已受控返回主页，当前小游戏本轮淘汰");
                return true;
            }
            diagnostic("[水果V4.60] ❌ 失败页存在但无法确认返回主页，保留现场而不是盲退");
        }

        if (userAborted || physicalTouchDetected) {
            TeachingOutcomeStore.setGameResult(
                    lastContext, TeachingOutcomeStore.UNKNOWN, "physical_touch_or_abort");
            return false;
        }
        String stopReason = result == FruitGameSolver.Result.SAFE_STOP_DIRTY
                ? "fruit_game_safe_stop_dirty"
                : result == FruitGameSolver.Result.NOT_FRUIT_GAME
                ? "fruit_game_not_detected" : "fruit_game_safe_stop_clean";
        TeachingOutcomeStore.setGameResult(
                lastContext, TeachingOutcomeStore.UNKNOWN, stopReason);
        TaskProfileStoreV48.recordUnverifiedV411(taskName, stopReason);

        if (finalPage == PageKindV411.FRUIT_PAIR_GAME
                || finalPage == PageKindV411.UNKNOWN
                || finalPage == PageKindV411.UNKNOWN_XIANYU) {
            gameIncompleteHoldV421 = true;
            gameIncompleteKindV421 = "FRUIT_PAIR_GAME";
            gameIncompleteTaskV421 = taskName;
            diagnostic("[游戏守卫V4.42] 恢复次数已用尽，水果页仍在或页面未确认；"
                    + "保留现场，禁止BACK/重启/普通导航");
            return false;
        }
        if (finalPage == PageKindV411.MODULE_APP) {
            markUserAbortV48("水果恢复时用户已切回助手");
            return false;
        }
        if (finalPage == PageKindV411.TASK_PANEL) return true;
        diagnostic("[水果V4.42] 已确认离开水果页，允许受控返回任务面板");
        return conditionalBackRecoveryV410(suPath, taskName, "水果安全停止后非游戏页返回");
    }

    /**
     * V4.60: Exit only from an explicitly recognized fruit-game failure page.
     * Never uses generic BACK. It waits briefly for the game-rendered "返回主页"
     * control, clicks only that OCR-located control, verifies departure, then
     * restores the Xianyu task panel.
     */
    private static boolean exitFailedFruitGameV460(String suPath, String taskName) {
        for (int attempt = 1; attempt <= 8 && !userAborted && !physicalTouchDetected; attempt++) {
            ScreenOcr.Snapshot snapshot;
            try {
                invalidateOcrCacheV411();
                snapshot = captureOcrV45(suPath, "水果V4.60/失败页退出#" + attempt);
            } catch (Throwable t) {
                diagnostic("[水果V4.60] 失败页退出OCR异常：" + t.getClass().getSimpleName());
                snapshot = null;
            }

            String text = combinedTextV45(null, snapshot);
            if (snapshot != null && !snapshot.isEmpty()) {
                diagnostic("[水果V4.60] 失败页退出确认#" + attempt + "：" + trimForLog(text, 220));
            }

            if (isTaskPageV45(null, snapshot)) {
                diagnostic("[水果V4.60] ✅ 已直接回到任务面板");
                return true;
            }
            if (FruitGameSolver.looksLikeFailedRound(text)) {
                ScreenOcr.Item home = snapshot == null ? null : snapshot.findBest("返回主页");
                if (home != null
                        && GameTapPolicy.allows(
                        home.centerX(), home.centerY(), snapshot.width, snapshot.height,
                        "水果游戏-失败页返回主页")) {
                    RootResult r = rootWithPath(
                            suPath,
                            "input tap " + home.centerX() + " " + home.centerY());
                    diagnostic("[水果V4.60] 点击失败页‘返回主页’ → "
                            + home.centerX() + "," + home.centerY()
                            + " / exit=" + r.exitCode);
                    if (r.exitCode == 0 && !userAborted) {
                        if (!paceSleepV415(650L, 950L)) return false;
                    } else if (userAborted || physicalTouchDetected) {
                        return false;
                    }
                } else {
                    diagnostic("[水果V4.60] 失败页已确认，但‘返回主页’按钮尚未取得合规OCR坐标，等待渲染");
                }
            }

            if (attempt < 8 && !paceSleepV415(320L, 520L)) return false;
        }

        PageKindV411 finalPage = inspectFruitPageV442(suPath, "失败页退出最终确认");
        if (finalPage == PageKindV411.TASK_PANEL) return true;
        if (finalPage == PageKindV411.MODULE_APP) return false;

        // Unknown/unfinished game pages are never fed into generic recovery.
        // Only a positively identified non-game Xianyu page may use the normal
        // task-panel restoration path.
        if (finalPage == PageKindV411.FRUIT_PAIR_GAME
                || finalPage == PageKindV411.UNKNOWN
                || finalPage == PageKindV411.UNKNOWN_XIANYU) {
            diagnostic("[水果V4.60] ❌ 失败页退出最终确认仍不明确，禁止普通导航/返回，保留现场");
            return false;
        }

        boolean recovered = recoverToXianyuTaskPanelV47(suPath, "水果失败页返回后任务面板恢复");
        if (recovered) {
            diagnostic("[水果V4.60] ✅ 失败页已离开，任务面板恢复成功：" + taskName);
            return true;
        }
        return false;
    }

    private static PageKindV411 inspectFruitPageV442(String suPath, String reason) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (userAborted || physicalTouchDetected) return PageKindV411.UNKNOWN;
            try {
                invalidateOcrCacheV411();
                PageProbeV411 page = probePageV411(suPath, "水果V4.42/" + reason + "#" + attempt);
                if (FruitGameSolver.looksLikeFruitGame(page.text)
                        || FruitGameSolver.looksLikeFruitStartScreen(page.text)) {
                    return PageKindV411.FRUIT_PAIR_GAME;
                }
                if (page.kind != PageKindV411.UNKNOWN && page.kind != PageKindV411.UNKNOWN_XIANYU) {
                    return page.kind;
                }
            } catch (RuntimeException e) {
                diagnostic("[恢复V4.42] 页面复查异常: " + e.getClass().getSimpleName());
            }
            if (attempt < 3 && !paceSleepV415(300L, 500L)) return PageKindV411.UNKNOWN;
        }
        // OCR空白/异常不等于已离开水果页。
        return PageKindV411.UNKNOWN;
    }

    private static boolean executeMahjongPairGameV419(
            String suPath,
            String taskName
    ) {
        diagnostic("[麻将V4.26] 启动‘点点消不停’视觉求解器：" + taskName);
        if (!paceSleepV415(320L, 560L)) return false;

        beginGameSolverOwnershipV420("MAHJONG_PAIR_GAME", taskName);
        MahjongGameSolver.Result result;
        try {
            result = MahjongGameSolver.solveOneRound(
                lastContext,
                suPath,
                new MahjongGameSolver.Host() {
                    @Override
                    public boolean tap(int x, int y, String reason) {
                        if (userAborted || physicalTouchDetected) return false;
                        if (reason == null || !reason.startsWith("点击相邻麻将")) {
                            diagnostic("[游戏限制V4.29] 拒绝非麻将对象点击：" + reason);
                            return false;
                        }
                        int jx = x;
                        int jy = y;
                        RootResult r = rootWithPath(
                                suPath,
                                "input tap " + Math.max(1, jx) + " " + Math.max(1, jy)
                        );
                        diagnostic("[麻将V4.26] " + reason + " → " + jx + "," + jy);
                        return r.exitCode == 0 && !userAborted;
                    }

                    @Override
                    public boolean swipe(
                            int sx, int sy, int ex, int ey, int durationMs, String reason
                    ) {
                        if (userAborted || physicalTouchDetected) return false;
                        if (reason == null || !(reason.startsWith("滑动麻将") || reason.startsWith("拖动麻将"))) {
                            diagnostic("[游戏限制V4.29] 拒绝非麻将对象滑动：" + reason);
                            return false;
                        }
                        RootResult r = rootWithPath(
                                suPath,
                                "input swipe " + Math.max(1, sx) + " " + Math.max(1, sy)
                                        + " " + Math.max(1, ex) + " " + Math.max(1, ey)
                                        + " " + Math.max(160, durationMs)
                        );
                        diagnostic("[麻将V4.26] " + reason
                                + " → " + sx + "," + sy
                                + " -> " + ex + "," + ey
                                + " / " + durationMs + "ms");
                        return r.exitCode == 0 && !userAborted;
                    }

                    @Override
                    public boolean sleep(long minMs, long maxMs) {
                        return paceSleepV415(minMs, maxMs);
                    }

                    @Override
                    public boolean aborted() {
                        return userAborted || physicalTouchDetected;
                    }

                    @Override
                    public void log(String message) {
                        diagnostic(message);
                    }

                    @Override
                    public ScreenOcr.Snapshot ocr(String reason) {
                        return captureOcrV45(suPath, reason);
                    }
                }
            );
        } finally {
            endGameSolverOwnershipV420(taskName);
        }

        if (result == MahjongGameSolver.Result.ABORTED) return false;

        if (result == MahjongGameSolver.Result.COMPLETED) {
            TeachingOutcomeStore.setGameResult(
                    lastContext, TeachingOutcomeStore.SUCCESS, "mahjong_game_completed");
            diagnostic("[麻将V4.26] ✅ 第1关完成，返回任务面板");
            TaskProfileStoreV48.recordRecovery(taskName, "mahjong_game_completed");
            return conditionalBackRecoveryV410(suPath, taskName, "麻将游戏完成返回");
        }

        if (result == MahjongGameSolver.Result.NOT_MAHJONG_GAME) {
            TeachingOutcomeStore.setGameResult(
                    lastContext, TeachingOutcomeStore.FAILURE, "mahjong_game_not_detected");
            diagnostic("[麻将V4.26] 点击任务后没有进入预期麻将页；不执行盲目返回");
            TaskProfileStoreV48.recordFailure(taskName, "mahjong_game_not_detected");
            return false;
        }

        ScreenOcr.Snapshot hold = captureOcrV45(suPath, "麻将V4.26/最终安全停止确认");
        String holdText = combinedTextV45(null, hold);
        TaskProfileStoreV48.recordUnverifiedV411(taskName, "mahjong_game_safe_stop");
        if (MahjongGameSolver.looksLikeMahjongPairGame(holdText)) {
            diagnostic("[游戏守卫V4.29] 麻将Solver未完成；不点游戏功能按钮，受控退出后继续其它任务");
            boolean recovered = conditionalBackRecoveryV410(
                    suPath, taskName, "麻将游戏安全停止退出");
            if (!recovered) {
                recovered = recoverToXianyuTaskPanelV47(
                        suPath, "麻将游戏安全停止导航恢复");
            }
            if (recovered) {
                gameIncompleteHoldV421 = false;
                return true;
            }

            gameIncompleteHoldV421 = true;
            gameIncompleteKindV421 = "MAHJONG_PAIR_GAME";
            gameIncompleteTaskV421 = taskName;
            diagnostic("[游戏守卫V4.29] 无法安全退出麻将页，才保留现场并停止继续扫描");
        }
        diagnostic("[麻将V4.26] 麻将游戏安全停止，未把任务标记为完成");
        return false;
    }

    private static boolean executeVideoTaskPolling(
            String suPath,
            String taskName
    ) {

        TaskProfileStoreV48.StrategyV49 videoStrategy =
                TaskProfileStoreV48.chooseStrategyV49(taskName, 22000L, true, true);
        long videoTimeout = Math.max(35000L, Math.min(55000L, videoStrategy.waitMs + 22000L));
        diagnostic("[视频策略V4.11] " + videoStrategy.describe()
                + " / timeout=" + videoTimeout + "ms");

        long start = SystemClock.elapsedRealtime();
        boolean sawAd = false;
        boolean attemptedReturn = false;
        boolean doubleSwipeDone = false;
        boolean taskPanelSeenAfterWatchV420 = false;
        int loop = 0;

        while (SystemClock.elapsedRealtime() - start < videoTimeout) {

            if (!paceSleepV415(950L, 1450L)) return false;
            loop++;

            String fg = getFg(suPath, false);

            if (MODULE_PACKAGE.equals(fg)) {
                markUserAbortV48("视频任务期间用户切回助手");
                return false;
            }

            if (!TARGET_PACKAGE.equals(fg)) {
                diagnostic("[视频] 当前离开闲鱼：" + printableFg(fg));
                long elapsed = SystemClock.elapsedRealtime() - start;

                if (elapsed >= 15000L) {
                    TaskProfileStoreV48.recordRecovery(taskName, "video_external:" + printableFg(fg));
                    attemptedReturn = true;
                    if (!doubleSwipeDone) {
                        doubleSwipeDone = fastDoubleRightBackV420(
                                suPath, taskName, "视频完成后的外部页快速退出");
                    }
                    if (doubleSwipeDone) return true;
                    if (recoverToXianyuTaskPanelV47(suPath, "视频外部跳转兜底恢复")) return true;
                }
                continue;
            }

            // OCR-only on most polls; this removes the old dumpUi + OCR pair.
            ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "视频快速轮询");
            String combined = combinedTextV45(null, ocr);

            if (looksLikeAdOrInstallPageV47(combined)) {
                sawAd = true;
                long elapsed = SystemClock.elapsedRealtime() - start;
                diagnostic("[视频] 检测到广告/试玩页，elapsed=" + elapsed + "ms");

                if (elapsed >= 15000L && !doubleSwipeDone) {
                    attemptedReturn = true;
                    TaskProfileStoreV48.recordRecovery(taskName, "video_ad_fast_double_back");
                    diagnostic("[视频广告恢复V4.42.3] 已达到15秒，立即快速连续双右滑返回");
                    doubleSwipeDone = fastDoubleRightBackV420(
                            suPath, taskName, "视频广告页达到最低观看时间后的快速双滑");
                    if (doubleSwipeDone) return true;
                    diagnostic("[视频广告恢复V4.42.3] 双滑未确认任务面板，交给后续受控恢复");
                }
                continue;
            }

            if (isTaskPageV45(null, ocr)) {
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (elapsed >= 15000L) {
                    taskPanelSeenAfterWatchV420 = true;
                    diagnostic("[视频] ✅ 已观看至少15秒并回到真实任务面板，停止继续返回");
                    return true;
                }
                diagnostic("[视频] 已回任务面板但观看时间不足：" + elapsed
                        + "/15000ms；继续等待，禁止提前判定完成");
            }

            // 视频任务必须满足“至少观看15秒 + 真实任务面板确认”两个条件。
            // 旧逻辑的 XML 兜底没有检查15秒，导致刚进入任务面板就可能被提前判定完成。
            if (loop % 4 == 0) {
                String xml = dumpUi(suPath);
                long elapsed = SystemClock.elapsedRealtime() - start;
                if (elapsed >= 15000L && isTaskPageV45(xml, ocr)) {
                    taskPanelSeenAfterWatchV420 = true;
                    diagnostic("[视频] ✅ XML确认已观看至少15秒并回到真实任务面板，停止继续返回");
                    return true;
                }
                if (isTaskPageV45(xml, ocr)) {
                    diagnostic("[视频] XML确认已回任务面板，但观看时间不足："
                            + elapsed + "/15000ms；禁止提前判定完成");
                }
            }

            if (sawAd
                    && SystemClock.elapsedRealtime() - start >= 24000L
                    && !doubleSwipeDone
                    && !taskPanelSeenAfterWatchV420) {
                attemptedReturn = true;
                doubleSwipeDone = fastDoubleRightBackV420(
                        suPath, taskName, "视频广告超时快速双滑");
                if (doubleSwipeDone) return true;
                if (recoverToXianyuTaskPanelV47(suPath, "视频广告超时导航兜底")) return true;
            }
        }

        if (!taskPanelSeenAfterWatchV420 && !doubleSwipeDone) {
            doubleSwipeDone = fastDoubleRightBackV420(
                    suPath, taskName, "视频最终恢复快速双滑");
        }
        if (doubleSwipeDone) {
            // fastDoubleRightBackV420() 只有在连续两次右滑后再次确认真实任务面板
            // 才返回 true，因此这里可以把它作为视频返回完成条件。
            taskPanelSeenAfterWatchV420 = true;
            return true;
        }

        // V4.42.2: 如果整个过程包含广告页，不能把广告跳转误判为任务失败。
        if (sawAd) {
            TaskProfileStoreV48.recordRecovery(taskName, "video_ad_timeout_recovered_failed");
            diagnostic("[视频V4.42.2] 广告干扰导致超时，记录恢复失败，不计任务失败");
        } else {
            TaskProfileStoreV48.recordFailure(taskName, "video_timeout");
        }
        diagnostic("[视频] 超时 " + videoTimeout + "ms，未确认完成");
        return false;
    }

    private static boolean recoverToXianyuTaskPanelV47(
            String suPath,
            String reason
    ) {
        if (userAborted) return false;
        if (gameSolverOwnsPageV420) {
            diagnostic("[游戏独占V4.26] 拦截普通恢复：" + reason
                    + " / owner=" + gameSolverKindV420);
            return false;
        }
        diagnostic("[恢复] " + reason);

        String fg = getFg(suPath, false);
        if (MODULE_PACKAGE.equals(fg)) {
            markUserAbortV48("恢复过程中检测到用户切回助手");
            return false;
        }

        if (userAborted) return false;

        if (!TARGET_PACKAGE.equals(fg)) {
            rootWithPath(suPath, "am start -n " + TARGET_MAIN_ACTIVITY);
            if (!waitFg(suPath, 4500L)) {
                diagnostic("[恢复] 无法把闲鱼拉回前台");
                return false;
            }
            sleepAbortableV48(350L);
        }

        // Fast OCR check first.
        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "恢复快速检查");
        if (isTaskPageV45(null, ocr)) return true;

        // If we are on a system jump/open-app dialog, Back is faster than a
        // full navigation rebuild.
        if (containsAny(ocr.fullText, "正在跳转", "打开淘宝", "打开支付宝", "取消")) {
            rootWithPath(suPath, "input keyevent 4");
            sleepAbortableV48(450L);
            ScreenOcr.Snapshot retry = captureOcrV45(suPath, "跳转弹窗返回后");
            if (isTaskPageV45(null, retry)) return true;
        }

        String xml = dumpUi(suPath);
        if (isTaskPageV45(xml, ocr)) return true;

        TaskProfileStoreV48.recordRecovery("__NAV__", reason);
        return enterViaMineCoin(suPath);
    }

    private static boolean isBounceTask(
            String name
    ) {

        if (name == null) return false;

        return containsAny(
                name,
                "支付宝",
                "农场",
                "头条",
                "点点消",
                "消不停",
                "百亿补贴",
                "玩游戏",
                "淘宝",
                "飞猪",
                "高德",
                "饿了么",
                "点淘",
                "试玩",
                "淘特",
                "百度",
                "大众点评",
                "美团",
                "快手",
                "一淘",
                "逛逛",
                "闪购",
                "领积分",
                "刷视频",
                "赚零花"
        );
    }

    private static boolean swipeUp(
            String suPath
    ) {

        if (!ensureFg(suPath)) {
            return false;
        }

        RootResult r =
                rootWithPath(
                        suPath,
                        "input swipe 540 2200 540 800 600"
                );

        return r.exitCode == 0;
    }

    private static String getFg(
            String suPath,
            boolean allowCache
    ) {

        String result = "";

        String[] commands = {
                "dumpsys window displays 2>/dev/null",
                "dumpsys activity activities 2>/dev/null"
        };

        for (String command :
                commands) {

            RootResult r =
                    rootWithPath(
                            suPath,
                            command
                    );

            String raw =
                    (
                            r.stdout
                                    + "\n"
                                    + r.stderr
                    ).trim();

            if (raw.isEmpty()) continue;

            String parsed =
                    parseForegroundFromDumpsys(
                            raw
                    );

            if (!parsed.isEmpty()) {

                result = parsed;

                if (TARGET_PACKAGE.equals(parsed)
                        || MODULE_PACKAGE.equals(parsed)) {
                    break;
                }
            }
        }

        diagnostic(
                "[前台检测] 最终结果="
                        + printableFg(result)
        );

        return result;
    }

    private static String parseForegroundFromDumpsys(
            String raw
    ) {

        if (raw == null
                || raw.isEmpty()) {
            return "";
        }

        String[] lines =
                raw.split("\\r?\\n");

        String[] keys = {
                "mCurrentFocus=",
                "mFocusedApp=",
                "mResumedActivity=",
                "topResumedActivity="
        };

        for (String line : lines) {

            if (!containsAny(line, keys)) {
                continue;
            }

            if (line.contains(TARGET_PACKAGE)) {
                return TARGET_PACKAGE;
            }

            if (line.contains(MODULE_PACKAGE)) {
                return MODULE_PACKAGE;
            }
        }

        for (String line : lines) {

            if (!containsAny(line, keys)) {
                continue;
            }

            Matcher matcher =
                    COMPONENT_PATTERN.matcher(
                            line
                    );

            if (matcher.find()) {

                String pkg =
                        matcher.group(1);

                if (pkg != null
                        && !pkg.isEmpty()) {
                    return pkg;
                }
            }
        }

        return "";
    }

    /**
     * Polls UI state instead of blindly sleeping for a fixed page-load delay.
     * Returns as soon as the task page or one of the expected tokens appears.
     */
    private static boolean waitForUiAny(
            String suPath,
            long timeoutMs,
            String... tokens
    ) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (userAborted) return false;
            String xml = dumpUi(suPath);
            if (xml != null) {
                if (isTaskPage(xml)) return true;
                if (tokens != null && containsAny(xml, tokens)) return true;
            }
            SystemClock.sleep(350L);
        }
        return false;
    }

    private static boolean waitForTaskPage(
            String suPath,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (userAborted) return false;
            String xml = dumpUi(suPath);
            if (xml != null && isTaskPage(xml)) return true;
            SystemClock.sleep(350L);
        }
        return false;
    }

    private static boolean waitFg(
            String suPath,
            long timeout
    ) {

        long start =
                SystemClock.elapsedRealtime();

        while (
                SystemClock.elapsedRealtime()
                        - start < timeout
        ) {

            if (userAborted) return false;

            String fg =
                    getFg(
                            suPath,
                            false
                    );

            if (TARGET_PACKAGE.equals(fg)) {
                return true;
            }

            SystemClock.sleep(1000L);
        }

        return false;
    }

    private static boolean ensureFg(
            String suPath
    ) {

        if (userAborted || physicalTouchDetected) {
            return false;
        }

        String fg =
                getFg(
                        suPath,
                        false
                );

        if (TARGET_PACKAGE.equals(fg)) {
            return true;
        }

        if (MODULE_PACKAGE.equals(fg)) {
            markUserAbortV48("明确检测到用户切回模块 App");
            return false;
        }

        if (inBounceTask) {

            rootWithPath(
                    suPath,
                    "am start -n "
                            + TARGET_MAIN_ACTIVITY
            );

            long deadline =
                    SystemClock.elapsedRealtime()
                            + 6000L;

            while (
                    SystemClock.elapsedRealtime()
                            < deadline
            ) {

                if (userAborted) return false;

                SystemClock.sleep(600L);

                String retry =
                        getFg(
                                suPath,
                                false
                        );

                if (TARGET_PACKAGE.equals(
                        retry
                )) {
                    return true;
                }

                if (MODULE_PACKAGE.equals(
                        retry
                )) {

                    userAborted = true;
                    return false;
                }
            }

            return false;
        }

        /*
         * V10.8 核心修复：
         * “未知”绝不能等同于模块 App。
         */
        if (fg == null
                || fg.isEmpty()) {

            diagnostic(
                    "⚠️ 前台暂时无法解析，不判定为用户中止"
            );

            for (int i = 1; i <= 2; i++) {

                SystemClock.sleep(400L);

                String retry =
                        getFg(
                                suPath,
                                false
                        );

                if (TARGET_PACKAGE.equals(retry)) {
                    return true;
                }

                if (MODULE_PACKAGE.equals(retry)) {

                    userAborted = true;

                    diagnostic(
                            "🛑 重试确认用户切回模块 App"
                    );

                    return false;
                }
            }

            return true;
        }

        if (isTransientForegroundV46(fg)) {
            diagnostic("⚠️ 检测到系统/桌面瞬时前台，短暂重试：" + fg);
            for (int i = 0; i < 4; i++) {
                SystemClock.sleep(350L);
                String retry = getFg(suPath, false);
                if (TARGET_PACKAGE.equals(retry)) return true;
                if (MODULE_PACKAGE.equals(retry)) {
                    userAborted = true;
                    diagnostic("🛑 重试确认用户切回模块 App");
                    return false;
                }
                if (!isTransientForegroundV46(retry)
                        && retry != null
                        && !retry.isEmpty()) {
                    fg = retry;
                    break;
                }
            }
        }

        // V4.6：停止手势统一为“切回闲鱼定时助手”。
        // 其它 App 可能是任务要求的跳转，或者系统短暂切换；不再直接把整个任务标记为用户中止。
        diagnostic(
                "⚠️ 当前不是闲鱼前台，不判定为用户中止："
                        + fg
        );

        return false;
    }

    private static boolean isTransientForegroundV46(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        if ("android".equals(pkg)) {
            return true;
        }

        return containsAny(
                pkg,
                "launcher",
                "systemui",
                "permissioncontroller",
                "packageinstaller",
                "resolver",
                "chooser"
        );
    }

    private static String dumpUi(
            String suPath
    ) {

        if (userAborted || physicalTouchDetected) {
            return null;
        }

        if (!ensureFg(suPath)) {
            return null;
        }

        for (int attempt = 1;
             attempt <= UI_DUMP_ATTEMPTS_V417;
             attempt++) {

            if (userAborted) return null;

            String xml =
                    dumpUiOnce(suPath);

            if (xml != null
                    && !xml.isEmpty()) {

                diagnostic(
                        "uiautomator(第"
                                + attempt
                                + "次) exit=0"
                );

                return xml;
            }

            if (!sleepAbortableV48(120L)) return null;
        }

        diagnostic(
                "⚠️ UIAutomator 快速兜底失败，跳过XML"
        );

        return null;
    }

    private static String dumpUiOnce(
            String suPath
    ) {

        String file =
                UI_DUMP_PREFIX
                        + android.os.Process.myPid()
                        + "_"
                        + System.currentTimeMillis()
                        + ".xml";

        String command =
                "mkdir -p /data/local/tmp 2>/dev/null; "
                        + "rm -f "
                        + file
                        + " 2>/dev/null; "
                        + "uiautomator dump --compressed "
                        + file
                        + " >/dev/null 2>&1; "
                        + "if [ -s "
                        + file
                        + " ]; then cat "
                        + file
                        + "; fi; "
                        + "rm -f "
                        + file
                        + " 2>/dev/null";

        RootResult r =
                rootWithPathTimedV417(
                        suPath,
                        command,
                        UI_DUMP_TIMEOUT_MS_V417
                );

        if (r.exitCode != 0) {
            return null;
        }

        String xml =
                r.stdout;

        if (xml == null
                || xml.trim().isEmpty()) {
            return null;
        }

        int start =
                xml.indexOf("<?xml");

        if (start >= 0) {

            xml =
                    xml.substring(start);

        } else {

            start =
                    xml.indexOf("<hierarchy");

            if (start >= 0) {
                xml =
                        xml.substring(start);
            }
        }

        if (!xml.contains("<hierarchy")) {
            return null;
        }

        return xml.trim();
    }

    private static boolean isTaskPage(
            String xml
    ) {

        return isRealTaskPage(
                xml
        );
    }

    private static boolean clickText(
            String suPath,
            String xml,
            String text
    ) {
        return clickText(
                suPath,
                xml,
                text,
                false
        );
    }

    private static boolean clickText(
            String suPath,
            String xml,
            String text,
            boolean allowBottomGestureZone
    ) {

        if (text == null
                || text.isEmpty()
                || xml == null) {
            return false;
        }

        try {

            Document doc =
                    parseXml(xml);

            if (doc == null) return false;

            NodeList nodes =
                    doc.getElementsByTagName(
                            "node"
                    );

            for (int i = 0;
                 i < nodes.getLength();
                 i++) {

                Node node =
                        nodes.item(i);

                String nodeText =
                        getAttr(
                                node,
                                "text"
                        );

                String desc =
                        getAttr(
                                node,
                                "content-desc"
                        );

                if (!text.equals(nodeText)
                        && !text.equals(desc)) {
                    continue;
                }

                String bounds =
                        getAttr(
                                node,
                                "bounds"
                        );

                if (bounds == null
                        || bounds.isEmpty()) {
                    continue;
                }

                return clickBounds(
                        suPath,
                        xml,
                        bounds,
                        allowBottomGestureZone
                );
            }

        } catch (Throwable t) {

            diagnostic(
                    "clickText 异常",
                    t
            );
        }

        return false;
    }

    private static boolean clickBounds(
            String suPath,
            String xml,
            String bounds
    ) {
        return clickBounds(suPath, xml, bounds, false);
    }

    private static boolean clickBounds(
            String suPath,
            String xml,
            String bounds,
            boolean allowBottomGestureZone
    ) {

        int[] rect = parseBounds(bounds);
        if (rect == null) return false;

        int left = rect[0];
        int top = rect[1];
        int right = rect[2];
        int bottom = rect[3];

        int x = (left + right) / 2;
        int y = (top + bottom) / 2;

        x = Math.max(left + 1, Math.min(right - 1, x));
        y = Math.max(top + 1, Math.min(bottom - 1, y));

        int height = getScreenHeight(suPath);
        int gestureZone = height > 0
                ? Math.max(60, Math.round(height * 0.03f))
                : 0;

        if (height > 0 && y > height - gestureZone) {
            if (!allowBottomGestureZone) {
                diagnostic("[点击] 位于底部手势区域，取消");
                return false;
            }
            diagnostic("[点击] 底部导航项，允许点击：y=" + y + "/" + height);
        }

        if (x < 1 || y < 1 || x > 2000 || y > 4000) return false;
        if (!ensureFg(suPath)) return false;
        if (userAborted) return false;

        diagnostic("[点击] 目标中心坐标=" + x + "," + y);
        RootResult r = rootWithPath(suPath, "input tap " + x + " " + y);
        if (r.exitCode != 0) return false;

        sleepAbortableV48(320L);
        return !userAborted;
    }

    private static int getScreenHeight(
            String suPath
    ) {

        RootResult r =
                rootWithPath(
                        suPath,
                        "wm size 2>/dev/null"
                );

        Matcher m =
                Pattern.compile(
                        "(\\d+)x(\\d+)"
                ).matcher(
                        r.stdout == null
                                ? ""
                                : r.stdout
                );

        if (!m.find()) return 0;

        try {

            int width =
                    Integer.parseInt(
                            m.group(1)
                    );

            int height =
                    Integer.parseInt(
                            m.group(2)
                    );

            return Math.max(
                    width,
                    height
            );

        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Document parseXml(
            String xml
    ) {

        if (xml == null
                || xml.trim().isEmpty()) {
            return null;
        }

        try {

            DocumentBuilderFactory factory =
                    DocumentBuilderFactory
                            .newInstance();

            factory.setNamespaceAware(false);

            try {
                factory.setFeature(
                        "http://xml.org/sax/features/external-general-entities",
                        false
                );
            } catch (Throwable ignored) {
            }

            try {
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities",
                        false
                );
            } catch (Throwable ignored) {
            }

            try {
                factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true
                );
            } catch (Throwable ignored) {
            }

            DocumentBuilder builder =
                    factory.newDocumentBuilder();

            return builder.parse(
                    new ByteArrayInputStream(
                            xml.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );

        } catch (Throwable t) {

            diagnostic(
                    "parseXml 异常",
                    t
            );

            return null;
        }
    }

    private static String getAttr(
            Node node,
            String name
    ) {

        if (node == null
                || node.getAttributes() == null) {
            return "";
        }

        Node attr =
                node.getAttributes()
                        .getNamedItem(name);

        return attr == null
                ? ""
                : attr.getNodeValue();
    }

    private static int[] parseBounds(String bounds) {
        if (bounds == null || bounds.isEmpty()) return null;
        Matcher matcher = BOUNDS_PATTERN.matcher(bounds);
        if (!matcher.find()) return null;
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)),
                    Integer.parseInt(matcher.group(4))
            };
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int[] parseCenter(
            String bounds
    ) {

        if (bounds == null
                || bounds.isEmpty()) {
            return null;
        }

        Matcher m =
                BOUNDS_PATTERN.matcher(
                        bounds
                );

        if (!m.find()) return null;

        try {

            int left =
                    Integer.parseInt(
                            m.group(1)
                    );

            int top =
                    Integer.parseInt(
                            m.group(2)
                    );

            int right =
                    Integer.parseInt(
                            m.group(3)
                    );

            int bottom =
                    Integer.parseInt(
                            m.group(4)
                    );

            return new int[]{
                    (left + right) / 2,
                    (top + bottom) / 2
            };

        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean containsAny(
            String value,
            String... words
    ) {

        if (value == null
                || words == null) {
            return false;
        }

        for (String word : words) {

            if (word != null
                    && value.contains(word)) {
                return true;
            }
        }

        return false;
    }

    private static boolean closePopupFromSnapshotV48(
            String suPath,
            ScreenOcr.Snapshot snapshot
    ) {
        if (snapshot == null || snapshot.isEmpty()) return false;

        String[] popupTexts = {
                "我知道了", "知道啦", "关闭"
        };
        for (String t : popupTexts) {
            ScreenOcr.Item acknowledgement = snapshot.findBest(t);
            if (acknowledgement == null || !t.equals(acknowledgement.text.trim())) continue;
            if (clickOcrTextAnyV45(suPath, snapshot, false, t)) {
                diagnostic("[弹窗] OCR快速关闭：" + t);
                return true;
            }
        }
        return false;
    }

    /** One edge-back gesture; callers verify the resulting page before continuing. */
    private static boolean preferredRightBackOnceV410(String suPath, String reason) {
        if (userAborted) return false;
        int[] screen = getScreenSizeV43(suPath);
        if (screen == null) return false;
        int width = screen[0], height = screen[1];
        int y = Math.round(height * 0.75f);
        int startX = Math.max(1, width - 2);
        int endX = Math.round(width * 0.76f);
        diagnostic("[右侧返回V4.11] " + reason + "：最右边缘 x=" + startX
                + " → " + endX + "，y=" + y + "(~75%H)");
        RootResult r = rootWithPath(suPath, "input swipe " + startX + " " + y
                + " " + endX + " " + y + " 260");
        return r.exitCode == 0;
    }

    /**
     * 视频任务完成后的专用快速退出：连续执行两次右侧边缘返回手势，
     * 两次之间只留极短的事件间隔，不在第一次返回后等待 OCR/页面动画。
     * 目的：视频/试玩页通常叠了两层页面，一次返回后等待会让第二层返回时机丢失。
     */
    private static boolean fastDoubleRightBackV420(String suPath, String taskName, String reason) {
        if (userAborted || gameSolverOwnsPageV420 || gameIncompleteHoldV421) return false;

        int[] screen = getScreenSizeV43(suPath);
        if (screen == null || screen.length < 2) return false;

        int width = screen[0];
        int height = screen[1];
        int y = Math.round(height * 0.75f);
        int startX = Math.max(1, width - 2);
        int endX = Math.round(width * 0.76f);

        String gesture = "input swipe " + startX + " " + y + " " + endX + " " + y + " 220";
        diagnostic("[视频快速双返回V4.20] " + reason
                + "：#1 " + startX + "," + y + " → " + endX + "," + y);

        RootResult first = rootWithPath(suPath, gesture);
        if (first.exitCode != 0 || userAborted) {
            diagnostic("[视频快速双返回V4.20] #1失败");
            return false;
        }

        // 不做 screenshot / OCR / 650ms 等待；只给 Android 输入队列一个极短间隔。
        SystemClock.sleep(90L);

        if (userAborted) return false;

        diagnostic("[视频快速双返回V4.20] #2 " + startX + "," + y + " → " + endX + "," + y);
        RootResult second = rootWithPath(suPath, gesture);
        if (second.exitCode != 0 || userAborted) {
            diagnostic("[视频快速双返回V4.20] #2失败");
            return false;
        }

        TaskProfileStoreV48.setReturnSwipes(taskName, 2);

        // 双滑完成后只做一次短确认；失败时交给原有恢复逻辑，不在这里连续 BACK。
        SystemClock.sleep(280L);
        ScreenOcr.Snapshot ocr = captureOcrV45(suPath, "视频双返回后任务面板确认");
        if (isTaskPageV45(null, ocr)) {
            diagnostic("[视频快速双返回V4.20] ✅ 连续双滑后已确认任务面板");
            return true;
        }

        String fg = getFg(suPath, false);
        if (MODULE_PACKAGE.equals(fg)) {
            markUserAbortV48("视频双返回后检测到用户切回助手");
            return false;
        }

        diagnostic("[视频快速双返回V4.20] 双滑已执行，但暂未确认任务面板："
                + printableFg(fg));
        return false;
    }

    private static boolean conditionalBackRecoveryV410(
            String suPath, String taskName, String reason
    ) {
        if (userAborted || gameSolverOwnsPageV420 || gameIncompleteHoldV421) return false;
        for (int i = 0; i <= 3; i++) {
            if (userAborted) return false;
            invalidateOcrCacheV411();
            PageProbeV411 page = probePageV411(suPath, "返回检查/" + reason);
            if (userAborted || page.kind == PageKindV411.MODULE_APP) return false;
            if (page.kind == PageKindV411.TASK_PANEL) return true;
            if (page.kind == PageKindV411.MINE || page.kind == PageKindV411.XIANYU_HOME
                    || page.kind == PageKindV411.COIN_HOME) {
                diagnostic("[条件返回] 已到 " + page.kind + "，停止后退，直接导航到任务面板");
                return enterViaMineCoin(suPath);
            }
            if (i == 3 || !preferredRightBackOnceV410(suPath, reason)
                    || !sleepAbortableV48(650L)) break;
        }
        TaskProfileStoreV48.recordFailure(taskName, "conditional_back_not_recovered");
        return false;
    }

    private static boolean isTaskPanelNowV410(String suPath) {
        if (userAborted) return false;
        PageProbeV411 probe = probePageV411(suPath, "条件返回页面确认");
        if (probe.kind == PageKindV411.MODULE_APP) {
            markUserAbortV48("返回检查时检测到用户切回助手");
            return false;
        }
        return probe.kind == PageKindV411.TASK_PANEL;
    }

    private static boolean sleepAbortableV48(long millis) {
        long end = SystemClock.elapsedRealtime() + Math.max(0L, millis);
        while (SystemClock.elapsedRealtime() < end) {
            if (userAborted || physicalTouchDetected) return false;
            long remain = end - SystemClock.elapsedRealtime();
            SystemClock.sleep(Math.min(120L, Math.max(1L, remain)));
        }
        return !userAborted && !physicalTouchDetected;
    }

    private static boolean paceSleepV415(long minMs, long maxMs) {
        long lo = Math.max(0L, Math.min(minMs, maxMs));
        long hi = Math.max(lo, Math.max(minMs, maxMs));
        long wait = lo + (hi - lo) / 2L;
        return sleepAbortableV48(wait);
    }

    private static long fixedDuration(long baseMs, long minMs, long maxMs) {
        return Math.max(minMs, Math.min(Math.max(minMs, maxMs), baseMs));
    }

    private static long explicitSecondsRequirementV415(String taskName) {
        if (taskName == null) return 0L;
        Matcher m = Pattern.compile("(\\d{1,3})\\s*(?:秒|s|S)").matcher(taskName);
        if (!m.find()) return 0L;
        try {
            int seconds = Integer.parseInt(m.group(1));
            if (seconds <= 0 || seconds > 120) return 0L;
            return seconds * 1000L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long defaultTaskWaitV415(
            String taskName, boolean isSearch, boolean isBounce, boolean isInternalBrowse
    ) {
        long explicit = explicitSecondsRequirementV415(taskName);
        if (explicit > 0L) return Math.min(45000L, explicit + 900L);
        if (isSearch) return 5200L;
        // “去浏览福利好物”需要完整浏览约 15 秒；旧版 8200ms 只够滑动两次。
        if (isInternalBrowse && taskName != null
                && taskName.replaceAll("\s+", "").contains("去浏览福利好物")) return 15000L;
        if (isInternalBrowse) return 8200L;
        if (isBounce) {
            if (containsAny(taskName, "逛逛", "浏览", "农场", "果园", "玩1关", "玩一玩")) {
                return 7800L;
            }
            if (containsAny(taskName, "签到", "领", "抽", "积分", "红包", "免单", "淘金币")) {
                return 5200L;
            }
            return 6300L;
        }
        return 5200L;
    }

    private static long minimumTaskWaitV415(
            String taskName, boolean isBounce, boolean isInternalBrowse
    ) {
        long explicit = explicitSecondsRequirementV415(taskName);
        if (explicit > 0L) return Math.min(45000L, explicit + 500L);
        if (isInternalBrowse && taskName != null
                && taskName.replaceAll("\s+", "").contains("去浏览福利好物")) return 15000L;
        if (isInternalBrowse) return 6200L;
        if (isBounce && containsAny(taskName, "逛逛", "浏览", "农场", "果园")) return 6000L;
        if (isBounce) return 3800L;
        return 3000L;
    }

    private static boolean isSystemTransitFgV415(String fg) {
        if (fg == null) return false;
        return "android".equals(fg)
                || fg.contains("permissioncontroller")
                || fg.contains("resolver")
                || fg.contains("packageinstaller");
    }

    private static void markUserAbortV48(String reason) {
        if (!userAborted) {
            userAborted = true;
            diagnostic("🛑 人工接管，立即停止：" + reason);
            TaskProfileStoreV48.recordFailure("__GLOBAL__", "manual_takeover:" + reason);
            // Human-learning/replay collection is intentionally disabled.
            // A physical touch is only a hard takeover signal: stop automation,
            // do not start a teaching window and do not persist the user's gesture.
        }
    }

    /**
     * V4.65: once a human takes over a fruit game, automation stops immediately.
     *
     * Teaching is passive and conservative:
     * - it never generates a tap/swipe;
     * - a transition is only admitted after the resulting structural state remains
     *   stable for a second observation;
     * - impossible/no-progress observations are audit-only and never reinforce memory;
     * - the observer is generation-scoped so it cannot close ScreenOcr for a later run.
     */
    /**
     * V4.67: generic 90s passive human-operation window for every task category.
     * The observer itself does not touch the screen. Physical touch events are
     * captured by the existing touchscreen monitor and stored as compact statistics.
     */
    private static synchronized void startHumanOperationTeachingV467() {
        Thread old = humanOperationTeachingThreadV467;
        if (old != null && old.isAlive()) return;

        final Context context = lastContext;
        if (context == null) return;

        final long generation = ++humanOperationTeachingGenerationV467;
        Thread thread = new Thread(() -> {
            long deadline = SystemClock.elapsedRealtime() + HUMAN_TEACHING_WINDOW_MS_V464;
            try {
                HumanOperationExperienceStore.compact(context);
                diagnostic("[真人经验V4.67] 开启90秒通用真人操作学习：点击偏差/滑动距离/角度/速度/节奏；不回放真人操作");
                while (SystemClock.elapsedRealtime() < deadline
                        && generation == humanOperationTeachingGenerationV467
                        && !Thread.currentThread().isInterrupted()) {
                    SystemClock.sleep(Math.min(1000L,
                            Math.max(50L, deadline - SystemClock.elapsedRealtime())));
                }
            } catch (Throwable t) {
                diagnostic("[真人经验V4.67] 通用学习线程退出", t);
            } finally {
                if (generation == humanOperationTeachingGenerationV467) {
                    humanOperationTeachingThreadV467 = null;
                    finalizeHumanTeachingOutcomeV480(context, cachedSuPath);
                    diagnostic("[真人经验V4.67] 通用真人操作学习窗口结束；"
                            + TeachingOutcomeStore.summary());
                }
            }
        }, "XianyuHumanOperationTeaching-V467");
        thread.setDaemon(true);
        humanOperationTeachingThreadV467 = thread;
        thread.start();
    }

    /**
     * V4.80: after the passive 90s window, verify the task row once more.
     * The window ending itself never implies success.
     */
    private static void finalizeHumanTeachingOutcomeV480(
            Context context,
            String suPath
    ) {
        if (context == null || suPath == null || suPath.isEmpty()) return;
        if (!TeachingOutcomeStore.hasSession()) return;

        String task = TeachingOutcomeStore.currentTask();
        TaskVerificationSnapshotV411 before = lastTeachingBeforeV480;
        if (task == null || task.isEmpty() || before == null) {
            TeachingOutcomeStore.setTaskResult(
                    context, TeachingOutcomeStore.UNKNOWN, "teaching_baseline_missing");
            diagnostic("[真人经验V4.80] 教学会话终局无法绑定：baseline missing；保持 UNKNOWN");
            return;
        }

        try {
            TaskVerificationResultV411 verification =
                    verifyTaskCompletionV411(suPath, task, false, before, true);
            if (verification.verified) {
                TeachingOutcomeStore.setTaskResult(
                        context, TeachingOutcomeStore.SUCCESS, verification.reason);
                FruitHumanExperienceStore.promoteCurrentSession(context);
                HumanOperationExperienceStore.finalizeCurrentSessionOutcome(context);
                diagnostic("[真人经验V4.80] 教学会话任务终局=SUCCESS："
                        + TeachingOutcomeStore.summary());
            } else {
                TeachingOutcomeStore.setTaskResult(
                        context, TeachingOutcomeStore.UNKNOWN, verification.reason);
                HumanOperationExperienceStore.finalizeCurrentSessionOutcome(context);
                diagnostic("[真人经验V4.80] 教学会话无法确认任务完成，终局=UNKNOWN："
                        + verification.reason);
            }
        } catch (Throwable t) {
            TeachingOutcomeStore.setTaskResult(
                    context, TeachingOutcomeStore.UNKNOWN, "verification_exception");
            HumanOperationExperienceStore.finalizeCurrentSessionOutcome(context);
            diagnostic("[真人经验V4.80] 教学会话终局验证异常，保持 UNKNOWN", t);
        }
    }

    private static synchronized void stopHumanOperationTeachingV467() {
        ++humanOperationTeachingGenerationV467;
        Thread thread = humanOperationTeachingThreadV467;
        humanOperationTeachingThreadV467 = null;
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try { thread.join(350L); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static synchronized void startHumanTeachingCaptureV464() {
        Thread old = humanTeachingThreadV464;
        if (old != null && old.isAlive()) return;

        String task = currentExecutingTaskV464 == null ? "" : currentExecutingTaskV464;
        String normalized = task.replaceAll("\\s+", "");
        if (!(normalized.contains("消了还想") || normalized.contains("还想消玩1关"))) {
            return;
        }

        final Context context = lastContext;
        final String suPath = cachedSuPath;
        if (context == null || suPath == null || suPath.isEmpty()) return;

        final long generation = ++humanTeachingGenerationV464;
        humanTeachingStartedV464 = true;
        diagnostic("[真人经验V4.65] 自动操作已停止，开启90秒被动学习窗口；仅记录经验证的结构经验，不回放真人点击");

        Thread thread = new Thread(() -> {
            FruitGameSolver.TeachingStateV464 previous = null;
            FruitGameSolver.TeachingStateV464 pendingBefore = null;
            FruitGameSolver.TeachingStateV464 pendingAfter = null;
            int learned = 0;
            int unchangedSamples = 0;
            boolean noProgressLogged = false;
            long deadline = SystemClock.elapsedRealtime() + HUMAN_TEACHING_WINDOW_MS_V464;
            try {
                while (SystemClock.elapsedRealtime() < deadline
                        && generation == humanTeachingGenerationV464) {
                    FruitGameSolver.TeachingStateV464 current =
                            FruitGameSolver.captureTeachingStateV464(context, suPath);
                    if (current != null
                            && generation == humanTeachingGenerationV464) {

                        String rejected = previous == null
                                ? null
                                : FruitHumanExperienceStore.rejectReason(previous, current);
                        if (rejected != null) {
                            FruitHumanExperienceStore.recordRejectedObservation(
                                    context, task, current, rejected);
                            pendingBefore = null;
                            pendingAfter = null;
                            unchangedSamples = 0;
                            noProgressLogged = false;
                        } else {
                            if (pendingAfter != null) {
                                if (FruitHumanExperienceStore.sameStructuralState(pendingAfter, current)) {
                                    FruitHumanExperienceStore.Transition transition =
                                            FruitHumanExperienceStore.classify(
                                                    pendingBefore.remaining, pendingAfter.remaining,
                                                    pendingBefore.trayCount, pendingAfter.trayCount,
                                                    pendingBefore.objects, pendingAfter.objects,
                                                    pendingBefore.blocked, pendingAfter.blocked,
                                                    pendingBefore.droppable, pendingAfter.droppable,
                                                    pendingBefore.directPairs, pendingAfter.directPairs);
                                    if (transition != null) {
                                        // Always retain the structural transition as audit data.
                                        // Promotion is deferred until the task outcome is SUCCESS.
                                        FruitHumanExperienceStore.record(
                                                context, task, pendingBefore, pendingAfter, transition);
                                        if (TeachingOutcomeStore.taskReplayEligible()) {
                                            learned++;
                                            diagnostic("[真人经验V4.80] 已验证任务结果后收录水果经验："
                                                    + transition.strategy);
                                        } else {
                                            diagnostic("[真人经验V4.80] 结构进展仅进入待定审计，未收录成功策略："
                                                    + transition.strategy);
                                        }
                                    }
                                    pendingBefore = null;
                                    pendingAfter = null;
                                } else {
                                    // The board moved again before the candidate after-state
                                    // was confirmed. Discard it instead of guessing which human
                                    // action caused the later state.
                                    pendingBefore = null;
                                    pendingAfter = null;
                                }
                            }

                            if (previous != null && pendingAfter == null) {
                                FruitGameSolver.TeachingStateV464 currentBefore = previous;
                                FruitHumanExperienceStore.Transition transition =
                                        FruitHumanExperienceStore.classify(
                                                currentBefore.remaining, current.remaining,
                                                currentBefore.trayCount, current.trayCount,
                                                currentBefore.objects, current.objects,
                                                currentBefore.blocked, current.blocked,
                                                currentBefore.droppable, current.droppable,
                                                currentBefore.directPairs, current.directPairs);
                                if (transition != null) {
                                    // Keep the candidate pending even while TASK_RESULT is UNKNOWN.
                                    // Final promotion is performed only after the 90s window verifies
                                    // the task result as SUCCESS.
                                    pendingBefore = currentBefore;
                                    pendingAfter = current;
                                } else if (FruitHumanExperienceStore.sameStructuralState(previous, current)) {
                                    unchangedSamples++;
                                    if (unchangedSamples >= 3 && !noProgressLogged) {
                                        FruitHumanExperienceStore.recordRejectedObservation(
                                                context, task, current,
                                                "three_consecutive_samples_without_verified_progress");
                                        noProgressLogged = true;
                                    }
                                } else {
                                    unchangedSamples = 0;
                                    noProgressLogged = false;
                                }
                            }
                        }

                        previous = current;
                    }

                    try {
                        Thread.sleep(HUMAN_TEACHING_SAMPLE_MS_V464);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Throwable t) {
                diagnostic("[真人经验V4.65] 被动学习异常", t);
            } finally {
                synchronized (TaskExecutor.class) {
                    if (generation == humanTeachingGenerationV464
                            && humanTeachingThreadV464 == Thread.currentThread()) {
                        humanTeachingThreadV464 = null;
                        humanTeachingStartedV464 = false;
                        if (!running) {
                            ScreenOcr.close();
                        }
                        FruitHumanExperienceStore.promoteCurrentSession(context);
                        diagnostic("[真人经验V4.80] 学习窗口结束："
                                + learned + " 个已验证任务经验；其余结构观察保持审计态，未直接回放");
                    }
                }
            }
        }, "XianyuHumanTeaching-V465");
        thread.setDaemon(true);
        humanTeachingThreadV464 = thread;
        thread.start();
    }

    private static synchronized void stopHumanTeachingCaptureV464() {
        humanTeachingGenerationV464++;
        Thread thread = humanTeachingThreadV464;
        humanTeachingThreadV464 = null;
        humanTeachingStartedV464 = false;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(350L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean isHumanTeachingThreadAliveV464() {
        Thread thread = humanTeachingThreadV464;
        return thread != null && thread.isAlive();
    }

    private static void startPhysicalTouchMonitorV48(String suPath) {
        stopPhysicalTouchMonitorV48();
        physicalTouchDetected = false;
        physicalTouchAt = 0L;

        String device = findTouchscreenDeviceV48(suPath);
        if (device == null || device.isEmpty()) {
            diagnostic("[人工检测] 未识别到物理触摸设备，继续使用前台/状态检测");
            return;
        }

        final int[] screenSize = getScreenSizeV43(suPath);
        final int screenW = screenSize != null && screenSize.length >= 2 ? screenSize[0] : 0;
        final int screenH = screenSize != null && screenSize.length >= 2 ? screenSize[1] : 0;
        int[] touchMax = getTouchscreenAxisMaxV469(suPath, device);
        final int touchMaxX = touchMax[0];
        final int touchMaxY = touchMax[1];
        physicalTouchMaxXV469 = touchMaxX;
        physicalTouchMaxYV469 = touchMaxY;
        diagnostic("[人工检测V4.69] 触摸坐标范围：X=" + touchMaxX + " Y=" + touchMaxY
                + "，屏幕=" + screenW + "x" + screenH);
        physicalTouchDevice = device;
        diagnostic("[人工检测] 监听物理触摸设备：" + device);

        // The tap that starts a task can still be completing while getevent is
        // being attached. Arm the takeover detector after a short grace window
        // so that the launch finger-up/down tail is not misclassified as a new
        // manual takeover.
        final long monitorArmedAtV453 = SystemClock.elapsedRealtime() + 1200L;

        Thread thread = new Thread(() -> {
            Process process = null;
            int startX = -1, startY = -1, lastX = -1, lastY = -1;
            float pathDistance = 0f;
            ArrayList<int[]> trajectory = new ArrayList<>();
            long downAt = 0L;
            long lastGestureEndAt = 0L;
            long pendingWaitMs = 0L;
            boolean gestureActive = false;
            try {
                process = Runtime.getRuntime().exec(new String[]{
                        suPath,
                        "-c",
                        "getevent -lt " + device + " 2>/dev/null"
                });
                touchMonitorProcess = process;
                diagnostic("[人工检测V4.70] 已启动独立真人触摸监听：" + device);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                );

                String line;
                while ((line = reader.readLine()) != null) {

                    String u = line.toUpperCase(Locale.US);
                    boolean touchDown =
                            (u.contains("BTN_TOUCH")
                                    && (u.contains("DOWN") || u.endsWith("00000001")))
                                    || (u.contains("ABS_MT_TRACKING_ID")
                                    && !u.endsWith("FFFFFFFF")
                                    && !u.endsWith("-1"));
                    boolean touchUp =
                            (u.contains("BTN_TOUCH")
                                    && (u.contains("UP") || u.endsWith("00000000")))
                                    || (u.contains("ABS_MT_TRACKING_ID")
                                    && (u.endsWith("FFFFFFFF") || u.endsWith("-1")));

                    Integer rawX = parseTouchCoordinateV467(u, "ABS_MT_POSITION_X");
                    Integer rawY = parseTouchCoordinateV467(u, "ABS_MT_POSITION_Y");
                    Integer x = normalizeTouchCoordinateV469(rawX, touchMaxX, screenW);
                    Integer y = normalizeTouchCoordinateV469(rawY, touchMaxY, screenH);
                    if (x != null) {
                        lastX = x;
                        // BTN_TOUCH/TRACKING_ID may arrive before the first ABS position
                        // events. Bind the gesture start to the first valid coordinates
                        // instead of permanently keeping startX/startY at -1.
                        if (gestureActive && startX < 0) startX = x;
                    }
                    if (y != null) {
                        lastY = y;
                        if (gestureActive && startY < 0) startY = y;
                    }
                    if (gestureActive && lastX >= 0 && lastY >= 0
                            && (x != null || y != null)) {
                        appendGesturePointV472(trajectory, lastX, lastY);
                    }

                    long now = SystemClock.elapsedRealtime();

                    if (touchDown) {
                        // BTN_TOUCH and ABS_MT_TRACKING_ID can both announce the same
                        // finger-down. Once a gesture is active, the second down is a
                        // duplicate event, not a new gesture.
                        if (gestureActive) continue;
                        if (now < monitorArmedAtV453) {
                            diagnostic("[人工检测V4.53] 忽略任务启动后触摸尾事件，armIn="
                                    + Math.max(0L, monitorArmedAtV453 - now) + "ms");
                            continue;
                        }
                        if (lastGestureEndAt > 0L && now - lastGestureEndAt < 150L) {
                            diagnostic("[人工检测V4.69] 忽略结束后的重复DOWN，delta="
                                    + Math.max(0L, now - lastGestureEndAt) + "ms");
                            continue;
                        }
                        if (now <= syntheticInputIgnoreUntilV411
                                && now - lastSyntheticInputAtV411 <= 500L) {
                            diagnostic("[人工检测V4.47.2] 忽略程序 input tap/swipe 的触摸尾事件，delta="
                                    + Math.max(0L, now - lastSyntheticInputAtV411) + "ms");
                            continue;
                        }

                        physicalTouchDetected = true;
                        physicalTouchAt = now;
                        if (!userAborted) {
                            markUserAbortV48("检测到真实手指触摸屏幕");
                            diagnostic("[人工检测] 已停止自动化；不记录、不学习、不回放真人手势");
                        }

                        // Takeover is terminal for this monitor session. Do not wait
                        // for TOUCH_UP and do not feed the gesture into any experience
                        // store. The service/finally path will dispose the reader.
                        break;
                    }

                    if (gestureActive && touchUp) {
                        int endX = x == null ? lastX : x;
                        int endY = y == null ? lastY : y;
                        if (startX >= 0 && startY >= 0 && endX >= 0 && endY >= 0
                                && endX < screenW && endY < screenH
                                && startX < screenW && startY < screenH) {
                            if (pendingWaitMs > 0L) {
                                try {
                                    HumanOperationExperienceStore.recordWait(
                                            lastContext,
                                            TeachingOutcomeStore.currentTask(),
                                            TeachingOutcomeStore.currentScene(),
                                            pendingWaitMs);
                                    diagnostic("[真人经验V4.69] 已记录 WAIT：" + pendingWaitMs + "ms");
                                } catch (Throwable t) {
                                    diagnostic("[真人经验V4.69] WAIT记录失败，但继续监听：" + t);
                                }
                            }
                            int duration = (int) Math.max(0L, now - downAt);
                            double directDistance = Math.hypot(endX - startX, endY - startY);
                            double angle = Math.toDegrees(Math.atan2(endY - startY, endX - startX));
                            try {
                                if (directDistance < 30.0 && duration < 450) {
                                    HumanOperationExperienceStore.recordRawTap(
                                            lastContext,
                                            TeachingOutcomeStore.currentTask(),
                                            TeachingOutcomeStore.currentScene(),
                                            endX, endY, screenW, screenH);
                                    diagnostic("[真人经验V4.68] 已记录 TAP：" + endX + "," + endY
                                            + " duration=" + duration + "ms");
                                } else {
                                    appendGesturePointV472(trajectory, endX, endY);
                                    SwipeCurveMetricsV472 curve =
                                            calculateSwipeCurveMetricsV472(
                                                    startX, startY, endX, endY, trajectory);
                                    float effectivePath = Math.max(
                                            curve.pathDistance, (float) directDistance);
                                    float speed = duration <= 0 ? 0f
                                            : effectivePath * 1000f / duration;
                                    HumanOperationExperienceStore.recordSwipe(
                                            lastContext,
                                            TeachingOutcomeStore.currentTask(),
                                            TeachingOutcomeStore.currentScene(),
                                            startX, startY, endX, endY,
                                            duration, effectivePath, (float) angle, speed,
                                            curve.curvatureRad, curve.maxDeviation,
                                            curve.pathRatio, trajectory,
                                            screenW, screenH);
                                    diagnostic("[真人经验V4.72] 已记录 SWIPE："
                                            + startX + "," + startY + "→" + endX + "," + endY
                                            + " duration=" + duration + "ms path=" + effectivePath
                                            + " angle=" + angle + " curveRad=" + curve.curvatureRad
                                            + " maxDev=" + curve.maxDeviation
                                            + " pathRatio=" + curve.pathRatio
                                            + " points=" + trajectory.size());
                                }
                            } catch (Throwable t) {
                                diagnostic("[真人经验V4.68] 手势记录失败，但继续监听：" + t);
                            }
                            lastGestureEndAt = now;
                        } else {
                            diagnostic("[人工检测V4.70] 收到抬起事件，但坐标未形成有效屏幕范围，丢弃本次手势");
                        }
                        gestureActive = false;
                        pendingWaitMs = 0L;
                        startX = startY = lastX = lastY = -1;
                        pathDistance = 0f;
                        trajectory.clear();
                    }

                    // Before takeover the old behavior remains immediate. After takeover
                    // we intentionally keep this reader alive for the teaching window so
                    // subsequent human gestures can be recorded.
                    if (!userAborted && !running) break;
                    if (userAborted && !isHumanTeachingActiveV466()) {
                        diagnostic("[人工检测V4.70] 真人教学已结束，退出触摸监听");
                        break;
                    }
                }
            } catch (Throwable t) {
                if (running || userAborted) {
                    diagnostic("[人工检测V4.67] 触摸经验监听退出：" + t);
                }
            } finally {
                if (process != null) {
                    try { process.destroy(); } catch (Throwable ignored) { }
                }
            }
        }, "XianyuTouchGuard-V411");

        thread.setDaemon(true);
        touchMonitorThread = thread;
        thread.start();
    }

    private static void appendGesturePointV472(List<int[]> points, int x, int y) {
        if (points == null || x < 0 || y < 0) return;
        if (!points.isEmpty()) {
            int[] last = points.get(points.size() - 1);
            if (last != null && last.length >= 2 && last[0] == x && last[1] == y) return;
        }
        final int maxPoints = 32;
        if (points.size() < maxPoints) {
            points.add(new int[]{x, y});
        } else {
            int index = 1 + ((points.size() * 7) % (maxPoints - 2));
            points.set(index, new int[]{x, y});
        }
    }

    private static final class SwipeCurveMetricsV472 {
        final float pathDistance;
        final float curvatureRad;
        final float maxDeviation;
        final float pathRatio;

        SwipeCurveMetricsV472(float pathDistance, float curvatureRad,
                              float maxDeviation, float pathRatio) {
            this.pathDistance = pathDistance;
            this.curvatureRad = curvatureRad;
            this.maxDeviation = maxDeviation;
            this.pathRatio = pathRatio;
        }
    }

    private static SwipeCurveMetricsV472 calculateSwipeCurveMetricsV472(
            int startX, int startY, int endX, int endY, List<int[]> points) {
        double direct = Math.hypot(endX - startX, endY - startY);
        if (points == null || points.size() < 2) {
            return new SwipeCurveMetricsV472((float) direct, 0f, 0f, 1f);
        }
        double path = 0d;
        double totalTurn = 0d;
        double maxDeviation = 0d;
        double baseDx = endX - startX;
        double baseDy = endY - startY;
        double baseLen = Math.hypot(baseDx, baseDy);
        for (int i = 1; i < points.size(); i++) {
            int[] a = points.get(i - 1);
            int[] b = points.get(i);
            if (a == null || b == null || a.length < 2 || b.length < 2) continue;
            path += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        for (int i = 1; i < points.size() - 1; i++) {
            int[] prev = points.get(i - 1);
            int[] cur = points.get(i);
            int[] next = points.get(i + 1);
            if (prev == null || cur == null || next == null
                    || prev.length < 2 || cur.length < 2 || next.length < 2) continue;
            double v1x = cur[0] - prev[0], v1y = cur[1] - prev[1];
            double v2x = next[0] - cur[0], v2y = next[1] - cur[1];
            double l1 = Math.hypot(v1x, v1y), l2 = Math.hypot(v2x, v2y);
            if (l1 > 0.01 && l2 > 0.01) {
                double dot = (v1x * v2x + v1y * v2y) / (l1 * l2);
                dot = Math.max(-1d, Math.min(1d, dot));
                totalTurn += Math.acos(dot);
            }
            if (baseLen > 0.01) {
                double cross = Math.abs(
                        baseDx * (cur[1] - startY) - baseDy * (cur[0] - startX));
                maxDeviation = Math.max(maxDeviation, cross / baseLen);
            }
        }
        float ratio = (float) (baseLen > 0.01 ? Math.max(1d, path / baseLen) : 1d);
        return new SwipeCurveMetricsV472(
                (float) Math.max(path, direct), (float) totalTurn,
                (float) maxDeviation, ratio);
    }

    private static int[] getTouchscreenAxisMaxV469(String suPath, String device) {
        int maxX = 0;
        int maxY = 0;
        try {
            RootResult r = rootWithPath(suPath, "getevent -pl " + device + " 2>/dev/null");
            String text = r == null || r.stdout == null ? "" : r.stdout;
            String[] lines = text.split("\\r?\\n");
            String currentAxis = "";
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.contains("abs_mt_position_x") || lower.contains("0035")) {
                    currentAxis = "x";
                } else if (lower.contains("abs_mt_position_y") || lower.contains("0036")) {
                    currentAxis = "y";
                }
                Matcher m = Pattern.compile("max\\s+(\\d+)").matcher(lower);
                if (m.find()) {
                    int value = Integer.parseInt(m.group(1));
                    if ("x".equals(currentAxis)) maxX = Math.max(maxX, value);
                    if ("y".equals(currentAxis)) maxY = Math.max(maxY, value);
                }
            }
        } catch (Throwable t) {
            diagnostic("[人工检测V4.69] 无法读取触摸ABS范围：" + t);
        }
        return new int[]{maxX, maxY};
    }

    private static Integer normalizeTouchCoordinateV469(Integer raw, int rawMax, int screenMax) {
        if (raw == null || screenMax <= 0) return raw;
        if (rawMax > 0) {
            long scaled = Math.round((double) raw * screenMax / rawMax);
            return (int) Math.max(0L, Math.min((long) screenMax, scaled));
        }
        // Conservative fallback for devices that omit ABS metadata: only scale
        // when the raw value is clearly outside the physical pixel range.
        if (raw > Math.round(screenMax * 1.25f)) {
            return (int) Math.max(0L, Math.min((long) screenMax,
                    Math.round((double) raw * screenMax / 4095.0)));
        }
        return Math.max(0, Math.min(screenMax, raw));
    }

    private static Integer parseTouchCoordinateV467(String line, String axis) {
        if (line == null || axis == null || !line.contains(axis)) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0) return null;
        String token = parts[parts.length - 1];
        try {
            // getevent prints ABS values as hexadecimal even when the token contains
            // only digits. Parsing digit-only values as decimal distorts coordinates.
            if (token.startsWith("0X") || token.startsWith("0x")) token = token.substring(2);
            return Integer.parseInt(token, 16);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void stopPhysicalTouchMonitorV48() {
        Process p = touchMonitorProcess;
        touchMonitorProcess = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) { }
            try { p.destroyForcibly(); } catch (Throwable ignored) { }
        }
        touchMonitorThread = null;
    }

    private static String findTouchscreenDeviceV48(String suPath) {
        RootResult r = rootWithPath(suPath, "getevent -pl 2>/dev/null");
        if (r.exitCode != 0 || r.stdout == null || r.stdout.isEmpty()) return null;

        String[] lines = r.stdout.split("\\r?\\n");
        String currentDevice = null;
        StringBuilder section = new StringBuilder();
        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (int i = 0; i <= lines.length; i++) {
            String line = i < lines.length ? lines[i] : "add device END";
            if (line.startsWith("add device")) {
                if (currentDevice != null) {
                    int score = touchscreenSectionScoreV48(section.toString());
                    if (score > bestScore) {
                        bestScore = score;
                        best = currentDevice;
                    }
                }
                currentDevice = null;
                section.setLength(0);
                Matcher m = Pattern.compile("(/dev/input/event\\d+)").matcher(line);
                if (m.find()) currentDevice = m.group(1);
            }
            if (currentDevice != null) section.append(line).append('\n');
        }

        return bestScore >= 4 ? best : null;
    }

    private static int touchscreenSectionScoreV48(String section) {
        if (section == null) return -100;
        String s = section.toLowerCase(Locale.US);
        int score = 0;
        if (s.contains("touchscreen")) score += 6;
        if (s.contains("sec_touch")) score += 6;
        if (s.contains("tsp")) score += 4;
        if (s.contains("touch")) score += 3;
        if (s.contains("abs_mt_position_x") || s.contains("0035")) score += 2;
        if (s.contains("abs_mt_position_y") || s.contains("0036")) score += 2;
        if (s.contains("btn_touch") || s.contains("014a")) score += 1;
        if (s.contains("fingerprint")) score -= 5;
        if (s.contains("volume") || s.contains("gpio_keys")) score -= 5;
        return score;
    }

    private static final class TaskProfileStoreV48 {

        /*
         * V4.11 unique-case index（继续使用 v483 key 以保留已有经验数据）.
         *
         * A "case" is not every occurrence. It is the canonical combination:
         *   normalized task name + event type + event detail
         *
         * Therefore the same failure/recovery/success pattern is represented by
         * one record only. Re-occurrence only updates count/last_at.
         */
        private static final String CASE_INDEX_KEY = "__case_index_v483";
        private static final String CASE_PREFIX = "__case_v483_";
        private static final int MAX_UNIQUE_CASES = 256;

        private static SharedPreferences prefs() {
            Context ctx = lastContext;
            return ctx == null
                    ? null
                    : ctx.getSharedPreferences(PROFILE_PREFS_V48, Context.MODE_PRIVATE);
        }

        static void prepareV411() {
            SharedPreferences p = prefs();
            if (p == null) return;
            int old = p.getInt("__schema_version", 0);
            p.edit()
                    .putInt("__schema_version", PROFILE_SCHEMA_V411)
                    .putLong("__last_prepare_at", System.currentTimeMillis())
                    .apply();
            diagnostic("[特征库V4.11] schema=" + PROFILE_SCHEMA_V411
                    + (old > 0 && old != PROFILE_SCHEMA_V411 ? "（由" + old + "升级）" : ""));
        }

        private static String appendRecentV411(String old, char result) {
            String s = old == null ? "" : old.replaceAll("[^SFU]", "");
            s += result;
            if (s.length() > RECENT_HISTORY_MAX_V411) {
                s = s.substring(s.length() - RECENT_HISTORY_MAX_V411);
            }
            return s;
        }

        private static int recentCountV411(String history, char result) {
            if (history == null || history.isEmpty()) return 0;
            int n = 0;
            for (int i = 0; i < history.length(); i++) {
                if (history.charAt(i) == result) n++;
            }
            return n;
        }

        static long cooldownRemainingMsV411(String task) {
            SharedPreferences p = prefs();
            if (p == null) return 0L;
            long until = p.getLong(base(task) + "cooldown_until", 0L);
            return Math.max(0L, until - System.currentTimeMillis());
        }

        static void recordStateV411(String task, String state, String detail) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit()
                    .putString(b + "last_state", safe(state))
                    .putString(b + "last_state_detail", trimForLog(safe(detail), 300))
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();
        }

        static void observePageV411(String kind, String fg, String marker) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String pageKey = "page_" + Integer.toHexString(
                    (safe(kind) + "|" + safe(fg) + "|" + safe(marker)).hashCode()) + "_";
            int count = p.getInt(pageKey + "count", 0) + 1;
            p.edit()
                    .putString(pageKey + "kind", safe(kind))
                    .putString(pageKey + "fg", safe(fg))
                    .putString(pageKey + "marker", safe(marker))
                    .putInt(pageKey + "count", count)
                    .putLong(pageKey + "last_at", System.currentTimeMillis())
                    .apply();
            recordUniqueCase("__PAGE__", "page:" + safe(kind),
                    safe(fg) + "|" + safe(marker));
        }

        static void recordDiagnosticV411(
                String task, String reason, String fg, String screenshotPath
        ) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit()
                    .putString(b + "last_diag_reason", safe(reason))
                    .putString(b + "last_diag_fg", safe(fg))
                    .putString(b + "last_diag_file", safe(screenshotPath))
                    .putLong(b + "last_diag_at", System.currentTimeMillis())
                    .apply();
        }

        private static String canonicalTask(String task) {
            String n = normalizeTaskAttemptKeyV46(task == null ? "" : task);
            // Collapse harmless OCR/format differences without merging genuinely
            // different tasks. This mainly removes whitespace and punctuation.
            n = n.replaceAll("[\\s\\p{Punct}，。！？；：、（）()【】\\[\\]·]+", "");
            return n.isEmpty() ? "未知任务" : n;
        }

        private static String base(String task) {
            String n = canonicalTask(task);
            return "p_" + Integer.toHexString(n.hashCode()) + "_";
        }

        private static String normalizeCaseDetail(String value) {
            if (value == null) return "";
            return value.trim().replaceAll("\\s+", " ");
        }

        private static String caseSignature(String task, String type, String detail) {
            return canonicalTask(task)
                    + "|" + normalizeCaseDetail(type)
                    + "|" + normalizeCaseDetail(detail);
        }

        private static String caseId(String signature) {
            String reverse = new StringBuilder(signature).reverse().toString();
            return Integer.toHexString(signature.hashCode())
                    + "_" + Integer.toHexString(reverse.hashCode())
                    + "_" + signature.length();
        }

        private static List<String> parseCaseIndex(String raw) {
            List<String> out = new ArrayList<>();
            if (raw == null || raw.isEmpty()) return out;

            Set<String> seen = new HashSet<>();
            String[] parts = raw.split("\\|");
            for (String part : parts) {
                String id = part == null ? "" : part.trim();
                if (id.isEmpty() || !seen.add(id)) continue;
                out.add(id);
            }
            return out;
        }

        private static String encodeCaseIndex(List<String> ids) {
            if (ids == null || ids.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            Set<String> seen = new HashSet<>();
            for (String id : ids) {
                if (id == null || id.isEmpty() || !seen.add(id)) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(id);
            }
            return sb.toString();
        }

        private static void recordUniqueCase(String task, String type, String detail) {
            SharedPreferences p = prefs();
            if (p == null) return;

            String signature = caseSignature(task, type, detail);
            String id = caseId(signature);
            String cp = CASE_PREFIX + id + "_";
            String storedSignature = p.getString(cp + "signature", "");
            long now = System.currentTimeMillis();

            if (signature.equals(storedSignature)) {
                // Exact duplicate: keep the original case record and only update
                // occurrence metadata. No second case is inserted into the library.
                int count = p.getInt(cp + "count", 1) + 1;
                p.edit()
                        .putInt(cp + "count", count)
                        .putLong(cp + "last_at", now)
                        .apply();
                diagnostic("[运行特征库去重V4.20] 已存在相同案例，仅更新次数："
                        + canonicalTask(task) + " / " + type
                        + " / count=" + count);
                return;
            }

            List<String> ids = parseCaseIndex(p.getString(CASE_INDEX_KEY, ""));

            // In the extremely unlikely event of a hash-id collision, derive a
            // deterministic alternate id instead of overwriting another case.
            if (!storedSignature.isEmpty() && !signature.equals(storedSignature)) {
                id = id + "_" + Integer.toHexString((signature + "#2").hashCode());
                cp = CASE_PREFIX + id + "_";
                storedSignature = p.getString(cp + "signature", "");
                if (signature.equals(storedSignature)) {
                    int count = p.getInt(cp + "count", 1) + 1;
                    p.edit().putInt(cp + "count", count).putLong(cp + "last_at", now).apply();
                    return;
                }
            }

            // Keep the library bounded. Remove the oldest indexed unique case.
            while (ids.size() >= MAX_UNIQUE_CASES) {
                String oldest = ids.remove(0);
                removeCaseFields(p, oldest);
            }

            if (!ids.contains(id)) ids.add(id);

            p.edit()
                    .putString(CASE_INDEX_KEY, encodeCaseIndex(ids))
                    .putString(cp + "signature", signature)
                    .putString(cp + "task", safe(task))
                    .putString(cp + "type", safe(type))
                    .putString(cp + "detail", safe(detail))
                    .putInt(cp + "count", 1)
                    .putLong(cp + "first_at", now)
                    .putLong(cp + "last_at", now)
                    .apply();

            diagnostic("[运行特征库V4.20] 新增唯一案例："
                    + canonicalTask(task) + " / " + type
                    + (detail == null || detail.isEmpty() ? "" : " / " + detail));
        }

        private static void removeCaseFields(SharedPreferences p, String id) {
            if (p == null || id == null || id.isEmpty()) return;
            String cp = CASE_PREFIX + id + "_";
            // The app's real SharedPreferences.Editor supports remove(). To stay
            // compatible with the current project/stub surface, clear values by
            // overwriting them; compactUniqueCases() also drops the index entry.
            p.edit()
                    .putString(cp + "signature", "")
                    .putString(cp + "task", "")
                    .putString(cp + "type", "")
                    .putString(cp + "detail", "")
                    .putInt(cp + "count", 0)
                    .putLong(cp + "first_at", 0L)
                    .putLong(cp + "last_at", 0L)
                    .apply();
        }

        static void compactUniqueCases() {
            SharedPreferences p = prefs();
            if (p == null) return;

            List<String> rawIds = parseCaseIndex(p.getString(CASE_INDEX_KEY, ""));
            List<String> kept = new ArrayList<>();
            Set<String> signatures = new HashSet<>();
            int removed = 0;

            long now = System.currentTimeMillis();

            for (String id : rawIds) {
                String cp = CASE_PREFIX + id + "_";
                String sig = p.getString(cp + "signature", "");
                long lastAt = p.getLong(cp + "last_at", 0L);

                if (sig == null || sig.isEmpty()) {
                    removed++;
                    continue;
                }

                if (lastAt > 0L && now - lastAt > FEATURE_TTL_MS_V411) {
                    removeCaseFields(p, id);
                    removed++;
                    continue;
                }

                if (!signatures.add(sig)) {
                    // Duplicate legacy/index entry: keep the first occurrence only.
                    removeCaseFields(p, id);
                    removed++;
                    continue;
                }
                kept.add(id);
            }

            if (kept.size() > MAX_UNIQUE_CASES) {
                int extra = kept.size() - MAX_UNIQUE_CASES;
                for (int i = 0; i < extra; i++) {
                    removeCaseFields(p, kept.get(i));
                }
                kept = new ArrayList<>(kept.subList(extra, kept.size()));
                removed += extra;
            }

            String compacted = encodeCaseIndex(kept);
            String old = p.getString(CASE_INDEX_KEY, "");
            if (!compacted.equals(old)) {
                p.edit().putString(CASE_INDEX_KEY, compacted).apply();
            }

            diagnostic("[特征库V4.11清理] 当前唯一案例=" + kept.size()
                    + (removed > 0 ? "，清理重复/无效=" + removed : ""));
        }

        static void recordAttempt(String task) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit()
                    .putString(b + "name", safe(task))
                    .putInt(b + "attempts", p.getInt(b + "attempts", 0) + 1)
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();
        }

        static void recordSuccess(String task, long elapsedMs) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            int success = p.getInt(b + "success", 0) + 1;
            long oldAvg = p.getLong(b + "avg_success_ms", 0L);
            long newAvg = oldAvg <= 0L
                    ? elapsedMs
                    : Math.round(oldAvg * 0.70 + elapsedMs * 0.30);
            String recent = appendRecentV411(p.getString(b + "recent", ""), 'S');

            p.edit()
                    .putString(b + "name", safe(task))
                    .putInt(b + "success", success)
                    .putLong(b + "avg_success_ms", newAvg)
                    .putString(b + "recent", recent)
                    .putInt(b + "consecutive_fail", 0)
                    .putLong(b + "cooldown_until", 0L)
                    .putString(b + "last_error", "")
                    .putLong(b + "last_success_at", System.currentTimeMillis())
                    .putLong(b + "last_recent_outcome_at", System.currentTimeMillis())
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();

            String durationBucket = elapsedMs < 7000L ? "fast"
                    : elapsedMs < 15000L ? "normal" : "slow";
            recordUniqueCase(task, "success", durationBucket);

            diagnostic("[运行特征库V4.20] 成功：" + safe(task)
                    + " success=" + success + " avg=" + newAvg + "ms"
                    + " recent=" + recent);
        }

        static void recordFailure(String task, String reason) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            int fail = p.getInt(b + "fail", 0) + 1;
            long now = System.currentTimeMillis();
            long lastOutcomeAt = p.getLong(b + "last_recent_outcome_at", 0L);
            boolean newOutcome = lastOutcomeAt <= 0L || now - lastOutcomeAt >= 5000L;

            int consecutive = p.getInt(b + "consecutive_fail", 0);
            String recent = p.getString(b + "recent", "");
            long cooldownUntil = p.getLong(b + "cooldown_until", 0L);

            if (newOutcome) {
                consecutive++;
                recent = appendRecentV411(recent, 'F');
                if (!safe(task).startsWith("__")
                        && consecutive >= TASK_COOLDOWN_FAILS_V411) {
                    cooldownUntil = Math.max(
                            cooldownUntil,
                            now + TASK_COOLDOWN_MS_V411);
                }
            }

            SharedPreferences.Editor editor = p.edit()
                    .putString(b + "name", safe(task))
                    .putInt(b + "fail", fail)
                    .putInt(b + "consecutive_fail", consecutive)
                    .putString(b + "recent", recent)
                    .putLong(b + "cooldown_until", cooldownUntil)
                    .putString(b + "last_error", safe(reason))
                    .putLong(b + "last_failure_at", now)
                    .putLong(b + "last_at", now);
            if (newOutcome) editor.putLong(b + "last_recent_outcome_at", now);
            editor.apply();

            recordUniqueCase(task, "failure", reason);

            diagnostic("[运行特征库V4.20] 失败：" + safe(task)
                    + " fail=" + fail
                    + " consecutive=" + consecutive
                    + " recent=" + recent
                    + (cooldownUntil > System.currentTimeMillis() ? " / 已进入冷却" : "")
                    + " reason=" + safe(reason));
        }

        static void recordUnverifiedV411(String task, String reason) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            int unverified = p.getInt(b + "unverified", 0) + 1;
            long now = System.currentTimeMillis();
            long lastOutcomeAt = p.getLong(b + "last_recent_outcome_at", 0L);
            boolean newOutcome = lastOutcomeAt <= 0L || now - lastOutcomeAt >= 5000L;

            int consecutive = p.getInt(b + "consecutive_fail", 0);
            String recent = p.getString(b + "recent", "");
            long cooldownUntil = p.getLong(b + "cooldown_until", 0L);

            if (newOutcome) {
                consecutive++;
                recent = appendRecentV411(recent, 'U');
                if (!safe(task).startsWith("__")
                        && consecutive >= TASK_COOLDOWN_FAILS_V411) {
                    cooldownUntil = Math.max(
                            cooldownUntil,
                            now + TASK_COOLDOWN_MS_V411);
                }
            }

            SharedPreferences.Editor editor = p.edit()
                    .putString(b + "name", safe(task))
                    .putInt(b + "unverified", unverified)
                    .putInt(b + "consecutive_fail", consecutive)
                    .putString(b + "recent", recent)
                    .putLong(b + "cooldown_until", cooldownUntil)
                    .putString(b + "last_error", "unverified:" + safe(reason))
                    .putLong(b + "last_at", now);
            if (newOutcome) editor.putLong(b + "last_recent_outcome_at", now);
            editor.apply();

            recordUniqueCase(task, "unverified", reason);
            diagnostic("[运行特征库V4.20] 未验证：" + safe(task)
                    + " unverified=" + unverified
                    + " consecutive=" + consecutive
                    + " recent=" + recent
                    + (cooldownUntil > System.currentTimeMillis() ? " / 已进入冷却" : "")
                    + " reason=" + safe(reason));
        }

        static void recordRecovery(String task, String recovery) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit()
                    .putString(b + "name", safe(task))
                    .putString(b + "last_recovery", safe(recovery))
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();

            recordUniqueCase(task, "recovery", recovery);
        }

        static boolean shouldCaptureDiagnosticV415(String task) {
            SharedPreferences p = prefs();
            if (p == null) return true;
            String b = base(task);
            int fail = p.getInt(b + "fail", 0);
            int unverified = p.getInt(b + "unverified", 0);
            return fail + unverified >= 2;
        }

        static long suggestedWaitMs(String task, long defaultMs) {
            SharedPreferences p = prefs();
            if (p == null) return defaultMs;
            String b = base(task);
            long avg = p.getLong(b + "avg_success_ms", 0L);
            if (avg <= 0L) return defaultMs;

            // V4.15: successful-run duration also contains verification/recovery
            // overhead, so use it as a light hint instead of letting it dominate
            // the actual dwell time.
            long learned = Math.round(avg * 0.52);
            long blended = Math.round(defaultMs * 0.72 + learned * 0.28);
            long min = Math.max(2800L, Math.round(defaultMs * 0.68));
            long max = Math.min(15000L, Math.max(min + 800L, Math.round(defaultMs * 1.55)));
            return Math.max(min, Math.min(max, blended));
        }

        static final class StrategyV49 {
            final String task;
            final long waitMs;
            final int returnSwipes;
            final boolean cautious;
            final int success;
            final int fail;

            StrategyV49(String task, long waitMs, int returnSwipes,
                        boolean cautious, int success, int fail) {
                this.task = task;
                this.waitMs = waitMs;
                this.returnSwipes = returnSwipes;
                this.cautious = cautious;
                this.success = success;
                this.fail = fail;
            }

            String describe() {
                return safe(task) + " wait=" + waitMs + "ms"
                        + " returnSwipes=" + returnSwipes
                        + " mode=" + (cautious ? "cautious" : "normal")
                        + " history=S" + success + "/F" + fail;
            }
        }

        static StrategyV49 chooseStrategyV49(
                String task, long defaultMs, boolean external, boolean video
        ) {
            SharedPreferences p = prefs();
            if (p == null) {
                return new StrategyV49(task, defaultMs, video ? 2 : 0, false, 0, 0);
            }

            String b = base(task);
            int success = p.getInt(b + "success", 0);
            int fail = p.getInt(b + "fail", 0);
            long learned = suggestedWaitMs(task, defaultMs);

            String recent = p.getString(b + "recent", "");
            int recentSuccess = recentCountV411(recent, 'S');
            int recentBad = recentCountV411(recent, 'F') + recentCountV411(recent, 'U');

            // V4.11: recent behavior is more important than very old totals.
            boolean cautious = (recentBad >= 3 && recentBad > recentSuccess)
                    || (fail >= 2 && fail > success);
            if (cautious) learned = Math.min(15000L, learned + 1300L);
            if (recentSuccess >= 4 && recentBad == 0) {
                learned = Math.max(3000L, learned - 650L);
            }

            int learnedSwipes = p.getInt(b + "return_swipes", 0);
            int swipes;
            if (video) {
                // 用户实机已验证广告返回需要连续两次边缘返回。
                swipes = Math.max(2, learnedSwipes);
            } else if (external) {
                swipes = Math.max(0, learnedSwipes);
            } else {
                swipes = 0;
            }

            return new StrategyV49(task, learned, swipes, cautious, success, fail);
        }

        static int learnedReturnSwipesV49(String task, int fallback) {
            SharedPreferences p = prefs();
            if (p == null) return fallback;
            int n = p.getInt(base(task) + "return_swipes", fallback);
            return Math.max(0, Math.min(3, n));
        }

        static void setReturnSwipes(String task, int count) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit().putInt(b + "return_swipes", count).apply();
        }

        static void setReturnStrategyV410(String task, String strategy, int count, float yRatio) {
            SharedPreferences p = prefs();
            if (p == null) return;
            String b = base(task);
            p.edit()
                    .putString(b + "return_strategy", safe(strategy))
                    .putInt(b + "return_swipes", Math.max(0, Math.min(4, count)))
                    .putString(b + "return_y_ratio", String.valueOf(yRatio))
                    .putLong(b + "last_at", System.currentTimeMillis())
                    .apply();
            recordUniqueCase(task, "return_strategy", safe(strategy) + "@" + yRatio);
        }

        static String summary(String task) {
            SharedPreferences p = prefs();
            if (p == null) return "new";
            String b = base(task);
            int s = p.getInt(b + "success", 0);
            int f = p.getInt(b + "fail", 0);
            int u = p.getInt(b + "unverified", 0);
            long avg = p.getLong(b + "avg_success_ms", 0L);
            String err = p.getString(b + "last_error", "");
            String recent = p.getString(b + "recent", "");
            int rs = p.getInt(b + "return_swipes", 0);
            String rstrategy = p.getString(b + "return_strategy", "");
            long cooldown = cooldownRemainingMsV411(task);
            return "S" + s + "/F" + f + "/U" + u
                    + (avg > 0 ? "/avg" + avg + "ms" : "")
                    + (recent == null || recent.isEmpty() ? "" : "/recent=" + recent)
                    + (cooldown > 0 ? "/cooldown=" + Math.max(1L, cooldown / 1000L) + "s" : "")
                    + (rs > 0 ? "/back×" + rs : "")
                    + (rstrategy == null || rstrategy.isEmpty() ? "" : "/" + rstrategy)
                    + (err == null || err.isEmpty() ? "" : "/err=" + err);
        }
    }

    private static String findSuPathWithRetry() {
        for (int attempt = 1; attempt <= ROOT_PROBE_ATTEMPTS; attempt++) {
            String path = findSuPath();
            if (path != null) {
                return path;
            }

            if (attempt < ROOT_PROBE_ATTEMPTS) {
                diagnostic(
                        "⚠️ 尚未获得 Root：即将进行一次快速复检（"
                                + (attempt + 1)
                                + "/"
                                + ROOT_PROBE_ATTEMPTS
                                + "）；若仍失败请到 KernelSU → 超级用户授权"
                );
                long retryDelay = 350L;
                SystemClock.sleep(retryDelay);
            }
        }
        return null;
    }

    private static String findSuPath() {

        if (cachedSuPath != null
                && !cachedSuPath.isEmpty()) {
            return cachedSuPath;
        }

        String[] paths = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/magisk/su",
                "su"
        };

        for (String path : paths) {

            try {

                RootResult r =
                        rootRaw(
                                path,
                                "id"
                        );

                if (r.exitCode == 0
                        && r.stdout.contains("uid=0")) {

                    cachedSuPath = path;

                    diagnostic(
                            "✅ Root 可用："
                                    + path
                    );

                    return path;
                }

            } catch (Throwable t) {

                diagnostic(
                        "检测 su 失败："
                                + path
                );
            }
        }

        return null;
    }


    private static boolean isUiMutationCommandV412(String command) {
        if (command == null) return false;
        String c = command.trim().toLowerCase(Locale.US);
        return c.startsWith("input tap ")
                || c.startsWith("input swipe ")
                || c.startsWith("input keyevent ")
                || c.startsWith("am start ")
                || c.startsWith("am force-stop ")
                || c.contains(" force-stop ");
    }

    private static RootResult rootWithPath(
            String suPath,
            String command
    ) {

        // V4.17 hard abort applies to read-only root probes too. Otherwise a
        // blocking dumpsys/uiautomator may keep the executor alive after touch.
        if (userAborted || physicalTouchDetected) {
            return new RootResult(-4, "", "manual_takeover_hard_stop");
        }

        if (isUiMutationCommandV412(command)) {

            if (userAborted || physicalTouchDetected) {
                diagnostic("[硬停止V4.13] 人工接管后拦截 UI 操作：" + command);
                return new RootResult(-4, "", "manual_takeover_hard_stop");
            }
        }

        if (command != null) {
            String c = command.trim().toLowerCase(Locale.US);
            if (c.startsWith("input tap ") || c.startsWith("input swipe ")) {
                long now = SystemClock.elapsedRealtime();
                lastSyntheticInputAtV411 = now;
                syntheticInputIgnoreUntilV411 = now + 500L;
                invalidateOcrCacheV411();
            } else if (c.startsWith("input keyevent ")
                    || c.startsWith("am start ")
                    || c.contains(" force-stop ")) {
                invalidateOcrCacheV411();
            }
        }

        if (suPath == null
                || suPath.isEmpty()) {

            return new RootResult(
                    -1,
                    "",
                    "su path empty"
            );
        }

        diagnostic(
                "[ROOT] su -c "
                        + command
        );

        return rootRaw(
                suPath,
                command
        );
    }

    private static RootResult rootRaw(
            String suPath,
            String command
    ) {
        return rootRawTimedV417(suPath, command, ROOT_TIMEOUT_MS);
    }

    private static RootResult rootWithPathTimedV417(
            String suPath,
            String command,
            long timeoutMs
    ) {
        if (userAborted || physicalTouchDetected) {
            return new RootResult(-4, "", "manual_takeover_hard_stop");
        }
        if (suPath == null || suPath.isEmpty()) {
            return new RootResult(-1, "", "su path empty");
        }
        diagnostic("[ROOT] su -c " + command);
        return rootRawTimedV417(suPath, command, timeoutMs);
    }

    private static RootResult rootRawTimedV417(
            String suPath,
            String command,
            long timeoutMs
    ) {
        Process process = null;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        try {
            process = Runtime.getRuntime().exec(new String[]{suPath, "-c", command});

            Thread outThread = new Thread(new StreamReader(process.getInputStream(), stdout));
            Thread errThread = new Thread(new StreamReader(process.getErrorStream(), stderr));
            outThread.start();
            errThread.start();

            long deadline = SystemClock.elapsedRealtime() + Math.max(200L, timeoutMs);
            boolean finished = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (userAborted || physicalTouchDetected) {
                    process.destroy();
                    try { process.destroyForcibly(); } catch (Throwable ignored) {}
                    return new RootResult(-4, snapshotOutput(stdout), "manual_takeover_hard_stop");
                }
                if (process.waitFor(100L, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }

            if (!finished) {
                process.destroy();
                try { process.destroyForcibly(); } catch (Throwable ignored) {}
                return new RootResult(-2, snapshotOutput(stdout), "timeout");
            }

            outThread.join(300L);
            errThread.join(300L);
            return new RootResult(process.exitValue(), snapshotOutput(stdout), snapshotOutput(stderr));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RootResult(-4, snapshotOutput(stdout), "interrupted");
        } catch (Throwable t) {
            return new RootResult(-1, snapshotOutput(stdout), t.toString());
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (Throwable ignored) {}
                try { process.getErrorStream().close(); } catch (Throwable ignored) {}
                try { process.getOutputStream().close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String snapshotOutput(StringBuilder output) {
        synchronized (output) { return output.toString(); }
    }

    private static final class StreamReader
            implements Runnable {

        private final InputStream input;
        private final StringBuilder output;

        StreamReader(
                InputStream input,
                StringBuilder output
        ) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {

            try {

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        input,
                                        StandardCharsets.UTF_8
                                )
                        );

                String line;

                while (
                        (line = reader.readLine())
                                != null
                ) {

                    synchronized (output) {

                        if (output.length()
                                < 1024 * 1024) {

                            output.append(
                                    line
                            ).append('\n');
                        }
                    }
                }

            } catch (Throwable ignored) {
            }
        }
    }

    private static void diagnostic(
            String message
    ) {
        if (message == null) message = "";
        Log.i(TAG, message);
        TaskStatusReceiver.writeLog(lastContext, "INFO", "系统日志", message);
    }

    private static void diagnostic(
            String message,
            Throwable throwable
    ) {
        diagnostic(
                message
                        + " : "
                        + (throwable == null ? "" : throwable.toString())
        );
    }

    private static void sendStatus(
            String taskName,
            String status,
            String detail
    ) {
        TaskStatusReceiver.writeLog(
                lastContext,
                safe(status),
                safe(taskName),
                safe(detail)
        );
    }

    private static void notifyTask(
            Context ctx,
            String title,
            String text
    ) {

        if (ctx == null) return;

        try {

            NotificationManager manager =
                    (NotificationManager)
                            ctx.getSystemService(
                                    Context.NOTIFICATION_SERVICE
                            );

            if (manager == null) return;

            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.O) {

                NotificationChannel channel =
                        new NotificationChannel(
                                NOTIFICATION_CHANNEL,
                                "闲鱼自动任务",
                                NotificationManager
                                        .IMPORTANCE_LOW
                        );

                manager.createNotificationChannel(
                        channel
                );
            }

            Intent launch =
                    ctx.getPackageManager()
                            .getLaunchIntentForPackage(
                                    MODULE_PACKAGE
                            );

            PendingIntent pendingIntent =
                    null;

            if (launch != null) {

                int flags =
                        PendingIntent.FLAG_UPDATE_CURRENT;

                if (Build.VERSION.SDK_INT
                        >= Build.VERSION_CODES.M) {

                    flags |=
                            PendingIntent.FLAG_IMMUTABLE;
                }

                pendingIntent =
                        PendingIntent.getActivity(
                                ctx,
                                1001,
                                launch,
                                flags
                        );
            }

            android.app.Notification.Builder builder;

            if (Build.VERSION.SDK_INT
                    >= Build.VERSION_CODES.O) {

                builder =
                        new android.app.Notification.Builder(
                                ctx,
                                NOTIFICATION_CHANNEL
                        );

            } else {

                builder =
                        new android.app.Notification.Builder(
                                ctx
                        );
            }

            builder
                    .setSmallIcon(
                            android.R.drawable.ic_popup_sync
                    )
                    .setContentTitle(
                            safe(title)
                    )
                    .setContentText(
                            safe(text)
                    )
                    .setAutoCancel(true)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .setDefaults(android.app.Notification.DEFAULT_ALL);

            if (pendingIntent != null) {
                builder.setContentIntent(
                        pendingIntent
                );
            }

            if (Build.VERSION.SDK_INT >= 33
                    && ctx.checkSelfPermission(
                    Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            manager.notify(
                    NOTIFICATION_ID,
                    builder.build()
            );

        } catch (SecurityException ignored) {

            diagnostic(
                    "通知权限不足，跳过通知"
            );

        } catch (Throwable t) {

            diagnostic(
                    "通知异常",
                    t
            );
        }
    }

    private static String safe(
            String value
    ) {

        return value == null
                ? ""
                : value;
    }

    private static String normalizeTaskName(
            String name
    ) {

        if (name == null) return "";

        return name
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private static String trimForLog(
            String value,
            int max
    ) {

        if (value == null) return "";

        if (value.length() <= max) {
            return value;
        }

        return value.substring(
                0,
                max
        ) + "...";
    }

    private static String printableFg(
            String fg
    ) {

        return fg == null
                || fg.isEmpty()
                ? "<未知>"
                : fg;
    }

    private static String shortCommand(
            String command
    ) {

        if (command == null) return "";

        return command.length() <= 45
                ? command
                : command.substring(
                        0,
                        45
                ) + "...";
    }

    private static final class TaskCandidate {

        final String name;
        final Node actionNode;
        final String actionBounds;
        final boolean isClaimReward;

        TaskCandidate(
                String name,
                Node actionNode,
                boolean isClaimReward
        ) {
            this.name = name == null ? "未知任务" : name;
            this.actionNode = actionNode;
            this.actionBounds = actionNode == null
                    ? ""
                    : getAttr(actionNode, "bounds");
            this.isClaimReward = isClaimReward;
        }

        TaskCandidate(
                String name,
                String actionBounds,
                boolean isClaimReward
        ) {
            this.name = name == null ? "未知任务" : name;
            this.actionNode = null;
            this.actionBounds = actionBounds == null ? "" : actionBounds;
            this.isClaimReward = isClaimReward;
        }

        String bounds() {
            return actionBounds == null ? "" : actionBounds;
        }

        String key() {
            return name
                    + "|"
                    + bounds()
                    + "|"
                    + isClaimReward;
        }
    }

    private static final class RootResult {

        final int exitCode;
        final String stdout;
        final String stderr;

        RootResult(
                int exitCode,
                String stdout,
                String stderr
        ) {

            this.exitCode =
                    exitCode;

            this.stdout =
                    stdout == null
                            ? ""
                            : stdout;

            this.stderr =
                    stderr == null
                            ? ""
                            : stderr;
        }
    }
}