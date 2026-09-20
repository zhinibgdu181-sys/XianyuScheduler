package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Clean fruit-game solver.
 *
 * Pipeline:
 *   screenshot -> full-board vision -> immutable state -> search -> one action
 *   -> screenshot verification -> replan
 *
 * No legacy local-decision strategy or long "no action" sleep is used here.
 */
public final class FruitGameSolver {
    public enum Result {
        ABORTED,
        COMPLETED,
        GAME_FAILED,
        SAFE_STOP_DIRTY,
        SAFE_STOP_CLEAN,
        NOT_FRUIT_GAME
    }

    public interface Host {
        void onFrameSize(int width, int height);
        boolean tap(int x, int y, String reason);
        boolean swipe(
                int x1, int y1,
                int x2, int y2,
                long durationMs,
                String reason
        );
        boolean sleep(long minMs, long maxMs);
        boolean aborted();
        void log(String message);
        ScreenOcr.Snapshot ocr(String reason);
    }

    /**
     * Compatibility state consumed by the existing human-learning store.
     * The values are structural measurements of the observed board, not executable
     * coordinates or stale strategy commands.
     */
    public static final class TeachingStateV464 {
        public final int remaining;
        public final int trayCount;
        public final int objects;
        public final int droppable;
        public final int blocked;
        public final int directPairs;
        public final int unlockGain;
        public final int continuationPairs;

        public TeachingStateV464(
                int remaining,
                int trayCount,
                int objects,
                int droppable,
                int blocked,
                int directPairs,
                int unlockGain,
                int continuationPairs
        ) {
            this.remaining = remaining;
            this.trayCount = trayCount;
            this.objects = objects;
            this.droppable = droppable;
            this.blocked = blocked;
            this.directPairs = directPairs;
            this.unlockGain = unlockGain;
            this.continuationPairs = continuationPairs;
        }
    }

    private static final long MAX_ROUND_MS = 10L * 60_000L;
    private static final long UI_PROBE_INTERVAL_MS = 2_200L;
    /**
     * The game shows its own idle ad/reward layer after a period without input.
     * This is a watchdog threshold, not a reason to synthesize arbitrary taps.
     * The solver should re-observe/replan instead of deliberately waiting.
     */
    private static final long MAX_IDLE_BETWEEN_ACTIONS_MS = 2_200L;
    private static final long DEADLOCK_WAIT_MS = 2_000L;
    private static final int MAX_NO_PROGRESS = 8;

    private FruitGameSolver() {}

    public static Result solveOneRound(Context context, String suPath, Host host) {
        if (context == null || suPath == null || suPath.trim().isEmpty() || host == null) {
            return Result.SAFE_STOP_CLEAN;
        }

        long deadline = System.currentTimeMillis() + MAX_ROUND_MS;
        long nextUiProbe = 0L;
        long lastActionAt = System.currentTimeMillis();
        int noProgress = 0;
        int replanCount = 0;
        long trayRiskSince = 0L;
        String deadlockTraySignature = "";
        long stalledSince = 0L;
        FruitBoardState current = null;
        Set<String> failedActionKeys = new HashSet<>();

        host.log("[水果新求解器] 开始：当前帧识别→选一对同类→快速双击→验证→重看");
        host.log("[水果新求解器] 不继承旧 FruitGameSolver 的局部决策链");

        while (!host.aborted() && System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();

            // Never intentionally leave the game idle. If recognition/planning
            // has taken too long without an input, immediately re-observe rather
            // than adding another sleep that could trigger the game's idle ad.
            if (now - lastActionAt > MAX_IDLE_BETWEEN_ACTIONS_MS) {
                host.log("[水果防空闲] 已超过" + MAX_IDLE_BETWEEN_ACTIONS_MS
                        + "ms没有有效操作；跳过等待，立即重新截图/规划");
                nextUiProbe = 0L;
            }

            if (now >= nextUiProbe) {
                UiDecision ui = probeUi(host);
                nextUiProbe = now + UI_PROBE_INTERVAL_MS;
                if (ui == UiDecision.COMPLETED) return Result.COMPLETED;
                if (ui == UiDecision.FAILED) return Result.GAME_FAILED;
                if (ui == UiDecision.LEFT_GAME) return Result.NOT_FRUIT_GAME;
                if (ui == UiDecision.PAGE_CHANGED || ui == UiDecision.POPUP_CLOSED) {
                    // Modal/start/revive actions change the frame. Never run
                    // fruit vision against the stale modal image.
                    replanCount++;
                    noProgress = 0;
                    failedActionKeys.clear();
                    trayRiskSince = 0L;
                    deadlockTraySignature = "";
                    stalledSince = 0L;
                    host.sleep(220L, 360L);
                    continue;
                }
            }

            Bitmap frame = ScreenOcr.captureBitmap(
                    context,
                    suPath,
                    () -> host.aborted()
            );
            if (frame == null) {
                host.log("[水果新求解器] 截图失败，立即重试而不是进入长时间无动作等待");
                if (++noProgress >= MAX_NO_PROGRESS) return Result.SAFE_STOP_DIRTY;
                host.sleep(100L, 170L);
                continue;
            }

            try {
                FruitBoardState observed = FruitVisionEngine.observe(frame);
                if (observed.width > 0 && observed.height > 0) {
                    host.onFrameSize(observed.width, observed.height);
                }

                if (looksLikeRoundComplete(observed)) {
                    ScreenOcr.Snapshot finalOcr = host.ocr("水果完成确认");
                    if (finalOcr != null && !finalOcr.isEmpty()
                            && looksLikeFailedRound(finalOcr.fullText)) {
                        return Result.GAME_FAILED;
                    }
                    if (finalOcr != null && !finalOcr.isEmpty()
                            && looksLikeCompletedRoundText(finalOcr.fullText)) {
                        return Result.COMPLETED;
                    }
                }

                if (observed.boardFruits.isEmpty()) {
                    ScreenOcr.Snapshot emptyCheck = host.ocr("水果空板确认");
                    if (emptyCheck != null && !emptyCheck.isEmpty()) {
                        if (looksLikeFailedRound(emptyCheck.fullText)) return Result.GAME_FAILED;
                        if (looksLikeCompletedRoundText(emptyCheck.fullText)) {
                            return Result.COMPLETED;
                        }
                    }
                    if (++noProgress >= MAX_NO_PROGRESS) {
                        host.log("[水果新求解器] 完整状态没有可识别对象，停止并保留现场");
                        return Result.SAFE_STOP_DIRTY;
                    }
                    host.sleep(120L, 180L);
                    continue;
                }

                /*
                 * Vision sanity gate:
                 * The real board observed in the earlier validated run contained
                 * 54 fruit objects. The new detector returning R=1/T=2 while OCR
                 * still says "剩余 202" is an invalid frame, not a legal game
                 * state. Never turn a bad vision result into an arbitrary unlock
                 * click.
                 */
                if (observed.boardFruits.size() < 6) {
                    ScreenOcr.Snapshot consistency = host.ocr("水果视觉一致性检查");
                    int remaining = extractRemainingCount(
                            consistency == null ? "" : consistency.fullText
                    );
                    if (remaining >= 20) {
                        host.log("[水果视觉保护] 识别结果不可信："
                                + "检测水果=" + observed.boardFruits.size()
                                + " / 槽位=" + observed.trayCount()
                                + " / OCR剩余=" + remaining
                                + "；禁止UNLOCK，重新截图识别");
                        noProgress++;
                        if (noProgress >= MAX_NO_PROGRESS) {
                            host.log("[水果视觉保护] 连续异常棋盘，停止本轮而不是误点");
                            return Result.SAFE_STOP_DIRTY;
                        }
                        host.sleep(120L, 180L);
                        continue;
                    }
                }

                current = observed;
            } finally {
                if (!frame.isRecycled()) frame.recycle();
            }

            host.log("[水果识别诊断] " + FruitPlanner.diagnosticSummary(current));

            int trayDistinct = FruitPlanner.distinctTrayTypes(current);
            FruitPlanner.Plan aggressiveTrayPlan =
                    FruitPlanner.planAggressiveTrayMatch(current, failedActionKeys);
            boolean hasAggressiveTrayMatch = !aggressiveTrayPlan.isEmpty();
            String traySignature = traySignature(current);
            long decisionNow = System.currentTimeMillis();

            boolean trayRisk = trayDistinct >= 2 && !hasAggressiveTrayMatch;
            if (trayRisk) {
                if (trayRiskSince == 0L
                        || !traySignature.equals(deadlockTraySignature)) {
                    trayRiskSince = decisionNow;
                    deadlockTraySignature = traySignature;
                    host.log("[水果死局计时] 槽内不同水果=" + trayDistinct
                            + "，当前无可补齐同类；开始2秒忍耐计时");
                }
            } else {
                trayRiskSince = 0L;
                deadlockTraySignature = "";
            }

            boolean criticalTray = trayDistinct >= 3 && !hasAggressiveTrayMatch;
            boolean trayTimedOut = trayRisk
                    && decisionNow - trayRiskSince >= DEADLOCK_WAIT_MS;

            FruitPlanner.Plan plan;
            if (hasAggressiveTrayMatch && trayDistinct >= 2) {
                plan = aggressiveTrayPlan;
                host.log("[水果破局] 槽内已有" + trayDistinct
                        + "种水果，优先强制补齐槽内同类");
            } else {
                plan = FruitPlanner.plan(current, failedActionKeys);
            }

            host.log(String.format(
                    Locale.US,
                    "[水果搜索] R=%d T=%d trayTypes=%d 路线点击=%d score=%.3f reason=%s",
                    current.boardFruits.size(),
                    current.trayCount(),
                    trayDistinct,
                    plan.clicks.size(),
                    plan.score,
                    plan.reason
            ));

            boolean generalStallTimedOut =
                    stalledSince > 0L
                            && decisionNow - stalledSince >= DEADLOCK_WAIT_MS;

            if (criticalTray || trayTimedOut || generalStallTimedOut) {
                String why = criticalTray
                        ? "槽内已有3种不同水果"
                        : (trayTimedOut
                        ? "槽内2种不同水果等待同类超过2秒"
                        : "连续候选无效/无Pair超过2秒");
                host.log("[水果死局] " + why + "，禁止继续被动扫描");

                // First priority is always a strict identity match to something
                // already occupying the collector. Physics restrictions are
                // relaxed, fruit identity is not.
                if (!aggressiveTrayPlan.isEmpty()) {
                    plan = aggressiveTrayPlan;
                    host.log("[水果死局] 找到槽内严格同类，强制补齐而不是打乱");
                } else if (tryDeadlockShuffle(host, current, why)) {
                    lastActionAt = System.currentTimeMillis();
                    failedActionKeys.clear();
                    noProgress = 0;
                    trayRiskSince = 0L;
                    deadlockTraySignature = "";
                    stalledSince = 0L;
                    host.sleep(650L, 900L);
                    continue;
                }
            }

            if (plan.isEmpty()) {
                ScreenOcr.Snapshot stuck = host.ocr("水果无安全动作确认");
                if (stuck != null && !stuck.isEmpty()) {
                    if (looksLikeFailedRound(stuck.fullText)) return Result.GAME_FAILED;
                    if (looksLikeTaskPanelText(stuck.fullText)) {
                        host.log("[水果页面守卫] 已离开游戏回到任务面板，停止水果视觉求解");
                        return Result.NOT_FRUIT_GAME;
                    }
                    if (looksLikeCompletedRoundText(stuck.fullText)) {
                        return Result.COMPLETED;
                    }
                }

                if (stalledSince == 0L) {
                    stalledSince = decisionNow;
                    host.log("[水果死局计时] 当前没有可靠Pair，开始2秒破局计时");
                }

                if (decisionNow - stalledSince >= DEADLOCK_WAIT_MS) {
                    if (tryDeadlockShuffle(host, current, "连续2秒没有可靠Pair")) {
                        lastActionAt = System.currentTimeMillis();
                        failedActionKeys.clear();
                        noProgress = 0;
                        trayRiskSince = 0L;
                        deadlockTraySignature = "";
                        stalledSince = 0L;
                        host.sleep(650L, 900L);
                        continue;
                    }
                }

                noProgress++;
                host.log("[水果死局计时] 尚未到2秒极限，立即重看棋盘，不执行空白点击/滑动");
                host.sleep(100L, 160L);
                continue;
            }

            /*
             * A plan now contains multiple complete pairs. We execute one pair,
             * verify the resulting frame, then continue with the already-searched
             * route only if the board transition is consistent. This removes the
             * expensive search between every pair without blindly replaying stale
             * coordinates.
             */
            boolean routeBroken = false;
            ScreenOcr.Snapshot beforeActionOcr = host.ocr("水果配对前剩余数");
            if (beforeActionOcr != null && !beforeActionOcr.isEmpty()) {
                String beforeText = beforeActionOcr.fullText;
                if (looksLikeTaskPanelText(beforeText)) {
                    host.log("[水果页面守卫] 执行动作前已检测到任务面板，禁止继续点击");
                    return Result.NOT_FRUIT_GAME;
                }
                if (looksLikeLeaveConfirmation(beforeText)
                        || looksLikeRevivePopup(beforeText)
                        || looksLikeFruitStartScreen(beforeText)
                        || looksLikeBlockingFunctionPopupText(beforeText)) {
                    host.log("[水果页面守卫] 执行动作前出现非棋盘UI，重新交给UI状态机处理");
                    noProgress = 0;
                    nextUiProbe = 0L;
                    continue;
                }
            }
            int remainingBeforeAction = extractRemainingCount(
                    beforeActionOcr == null ? "" : beforeActionOcr.fullText
            );

            for (int routeIndex = 0;
                    routeIndex < plan.clicks.size();
                    routeIndex++) {

                FruitPlanner.Click click = plan.clicks.get(routeIndex);
                if (!FruitPlanner.matchesClickTarget(current, click)) {
                    host.log("[水果执行] 路线坐标已失效，"
                            + "当前画面与预测状态不一致；废弃剩余路线");
                    routeBroken = true;
                    break;
                }
                if (!GameTapPolicy.allows(
                        click.x,
                        click.y,
                        current.width,
                        current.height,
                        click.reason
                )) {
                    host.log("[水果新求解器] GameTapPolicy 拒绝路线："
                            + click.x + "," + click.y);
                    routeBroken = true;
                    break;
                }

                host.log("[水果执行] 路线 "
                        + (routeIndex + 1) + "/" + plan.clicks.size()
                        + " tap " + click.reason
                        + " @" + click.x + "," + click.y);

                if (!host.tap(click.x, click.y, click.reason)) {
                    host.log("[水果执行] 路线点击失败，立即废弃剩余路线");
                    routeBroken = true;
                    break;
                }

                lastActionAt = System.currentTimeMillis();

                if (click.reason.equals("PAIR_FIRST")) {
                    // Give the first fruit time to leave its original cell. The
                    // real recording shows a visible gravity/roof-roll phase;
                    // the old 90ms gap often tapped the second fruit before the
                    // first had opened its fall corridor.
                    if (!host.sleep(180L, 280L)) return Result.ABORTED;
                    continue;
                }

                if (!host.sleep(320L, 480L)) return Result.ABORTED;

                // UNLOCK is intentionally a one-click route. It must be
                // re-observed before another action is allowed.
                if (click.reason.equals("UNLOCK")) {
                    routeBroken = true;
                    break;
                }

                /*
                 * Let OCR run before the structural screenshot. OCR itself costs
                 * enough time for the clicked fruit to finish falling/rolling.
                 * The old code captured the "after" frame ~150ms after the tap,
                 * while the real physics animation was still in progress.
                 */
                ScreenOcr.Snapshot afterActionOcr = host.ocr("水果配对后剩余数");
                if (afterActionOcr != null && !afterActionOcr.isEmpty()) {
                    String afterText = afterActionOcr.fullText;
                    if (looksLikeTaskPanelText(afterText)) {
                        host.log("[水果页面守卫] 点击后页面已回任务面板，停止把任务面板识别成水果");
                        return Result.NOT_FRUIT_GAME;
                    }
                    if (looksLikeLeaveConfirmation(afterText)
                            || looksLikeRevivePopup(afterText)
                            || looksLikeFruitStartScreen(afterText)
                            || looksLikeBlockingFunctionPopupText(afterText)) {
                        host.log("[水果页面守卫] 点击后出现弹窗/开始页，废弃视觉结果并重新处理UI");
                        routeBroken = true;
                        noProgress = 0;
                        failedActionKeys.clear();
                        nextUiProbe = 0L;
                        break;
                    }
                }

                int remainingAfterAction = extractRemainingCount(
                        afterActionOcr == null ? "" : afterActionOcr.fullText
                );
                boolean remainingReadable =
                        remainingBeforeAction >= 0 && remainingAfterAction >= 0;
                boolean remainingDropped =
                        remainingReadable && remainingAfterAction < remainingBeforeAction;

                /*
                 * Remaining-count OCR is authoritative when both readings are
                 * available. The 4.45.2 log exposed a false success where an
                 * "解锁所有槽位" modal reduced the number of visible objects from
                 * 33 to 24 while the counter stayed 202. Never let structural
                 * change override an unchanged readable counter.
                 */
                if (remainingDropped) {
                    host.log("[水果验证] 计数器确认消除成功："
                            + click.reason
                            + "，剩余=" + remainingBeforeAction + "->"
                            + remainingAfterAction);
                    failedActionKeys.clear();
                    if ("TRAY_MATCH".equals(click.reason)
                            || "DEADLOCK_TRAY_MATCH".equals(click.reason)) {
                        trayRiskSince = 0L;
                        deadlockTraySignature = "";
                    }
                    stalledSince = 0L;
                    noProgress = 0;
                    routeBroken = true;
                    current = null;
                    break;
                }

                Bitmap afterFrame = ScreenOcr.captureBitmap(
                        context,
                        suPath,
                        () -> host.aborted()
                );
                if (afterFrame == null) {
                    host.log("[水果验证] 稳定后截图失败，立即重新识别");
                    routeBroken = true;
                    break;
                }

                FruitBoardState after;
                try {
                    after = FruitVisionEngine.observe(afterFrame);
                    if (after.width > 0 && after.height > 0) {
                        host.onFrameSize(after.width, after.height);
                    }
                } finally {
                    if (!afterFrame.isRecycled()) afterFrame.recycle();
                }

                int beforeCount = current.boardFruits.size();
                int afterCount = after.boardFruits.size();
                int structuralChange = current.fingerprintChangesAgainst(after);
                int objectDelta = afterCount - beforeCount;
                boolean plausibleStructuralDelta = Math.abs(objectDelta) <= 4;

                // Structural pair completion is only a fallback when the OCR
                // counter is unavailable. Exact -2..-4 reductions are accepted;
                // large collapses are treated as overlays/modals, not gameplay.
                boolean pairBoardReduction =
                        !remainingReadable
                                && "PAIR_SECOND".equals(click.reason)
                                && plausibleStructuralDelta
                                && afterCount <= beforeCount - 2;
                boolean trayMatchStructural =
                        !remainingReadable
                                && ("TRAY_MATCH".equals(click.reason)
                                || "DEADLOCK_TRAY_MATCH".equals(click.reason))
                                && plausibleStructuralDelta
                                && current.trayCount() > 0
                                && after.trayCount() < current.trayCount()
                                && afterCount <= beforeCount - 1;

                if (pairBoardReduction || trayMatchStructural) {
                    host.log("[水果验证] OCR不可用，结构确认动作成功："
                            + click.reason
                            + "，识别对象=" + beforeCount + "->" + afterCount
                            + "，槽位=" + current.trayCount() + "->" + after.trayCount()
                            + "，结构变化=" + structuralChange);
                    current = after;
                    failedActionKeys.clear();
                    if (trayMatchStructural) {
                        trayRiskSince = 0L;
                        deadlockTraySignature = "";
                    }
                    stalledSince = 0L;
                    noProgress = 0;
                    continue;
                }

                boolean oneFruitEnteredCollector =
                        plausibleStructuralDelta
                                && ("PAIR_SECOND".equals(click.reason)
                                || "TRAY_MATCH".equals(click.reason)
                                || "DEADLOCK_TRAY_MATCH".equals(click.reason))
                                && (afterCount == beforeCount - 1
                                || after.trayCount() == current.trayCount() + 1);
                if (oneFruitEnteredCollector) {
                    host.log("[水果验证] 只确认1个水果进入槽位；"
                            + "不判失败，下一轮优先寻找槽内同类。"
                            + " 对象=" + beforeCount + "->" + afterCount
                            + " 槽位=" + current.trayCount() + "->" + after.trayCount());
                    current = after;
                    failedActionKeys.clear();
                    trayRiskSince = 0L;
                    deadlockTraySignature = "";
                    stalledSince = 0L;
                    routeBroken = true;
                    noProgress = 0;
                    break;
                }

                String failedKey = plan.actionKey();
                if (!failedKey.isEmpty()) {
                    failedActionKeys.add(failedKey);
                    host.log("[水果候选黑名单] 当前局面暂时排除无效动作：" + failedKey);
                }
                host.log("[水果验证] 点击后没有证据证明消除/入槽："
                        + "剩余=" + remainingBeforeAction + "->"
                        + remainingAfterAction
                        + "，对象=" + beforeCount + "->" + afterCount
                        + "，槽位=" + current.trayCount() + "->" + after.trayCount()
                        + "；废弃本候选并改选其他动作");
                routeBroken = true;
                noProgress++;

                if (stalledSince == 0L) {
                    stalledSince = System.currentTimeMillis();
                }
                host.log("[水果死局] 当前候选无效，加入黑名单并立即换候选；"
                        + "连续无进展达到2秒后改用打乱破局");
                break;
            }

            if (!routeBroken && !plan.isEmpty()) {
                // The complete predicted route was consumed. Re-observe at the
                // top of the loop for completion/task verification.
                noProgress = 0;
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[水果新求解器] 达到本轮最大运行时间，安全停止并保留当前页面");
        return Result.SAFE_STOP_DIRTY;
    }

    private static boolean tryDeadlockShuffle(
            Host host,
            FruitBoardState state,
            String reason
    ) {
        if (host == null || state == null) return false;

        ScreenOcr.Snapshot snapshot = host.ocr("水果死局破局-打乱");
        if (snapshot == null || snapshot.isEmpty()) return false;

        if (looksLikeTaskPanelText(snapshot.fullText)
                || looksLikeFailedRound(snapshot.fullText)
                || looksLikeCompletedRoundText(snapshot.fullText)
                || looksLikeBlockingFunctionPopupText(snapshot.fullText)) {
            return false;
        }

        ScreenOcr.Item shuffle = findTextCandidate(snapshot, "打乱");
        int x;
        int y;
        if (shuffle != null) {
            x = shuffle.centerX();
            y = shuffle.centerY();
        } else if (containsAny(snapshot.fullText, "剩余", "剩小", "还剩")
                && snapshot.fullText.contains("第1关")) {
            // Stable fallback measured from the game layout. Only used after
            // OCR confirms we are still on the live board.
            x = Math.round(snapshot.width * 0.78f);
            y = Math.round(snapshot.height * 0.935f);
        } else {
            return false;
        }

        if (!GameTapPolicy.allows(
                x, y, snapshot.width, snapshot.height,
                "FRUIT_DEADLOCK_SHUFFLE")) {
            host.log("[水果死局] 找到打乱但坐标未通过安全白名单："
                    + x + "," + y);
            return false;
        }

        boolean ok = host.tap(x, y, "FRUIT_DEADLOCK_SHUFFLE");
        if (ok) {
            host.log("[水果死局] " + reason
                    + " → 主动点击‘打乱’破局 @"
                    + x + "," + y);
        }
        return ok;
    }

    private static String traySignature(FruitBoardState state) {
        if (state == null || state.trayFruits.isEmpty()) return "EMPTY";
        StringBuilder sb = new StringBuilder();
        for (FruitBoardState.Fruit fruit : state.trayFruits) {
            sb.append(Math.round(fruit.meanR / 16.0f)).append(',')
                    .append(Math.round(fruit.meanG / 16.0f)).append(',')
                    .append(Math.round(fruit.meanB / 16.0f)).append(';');
        }
        return sb.toString();
    }

    public static boolean looksLikeFruitGame(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        boolean explicit = normalized.contains("水果")
                || normalized.contains("二消")
                || normalized.contains("去消了还想消")
                || normalized.contains("果盘")
                || normalized.contains("槽位")
                || normalized.contains("消除水果");
        boolean versionedBoard = normalized.contains("VERSION")
                && containsAny(normalized, "剩余", "剩小")
                && normalized.contains("消除")
                && normalized.contains("打乱");
        return explicit || versionedBoard;
    }

    public static boolean looksLikeFruitStartScreen(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        boolean fruitContext = containsAny(normalized,
                "水果", "二消", "果盘", "槽位", "消除水果", "去消了还想消");
        boolean versionedGameScreen = normalized.contains("VERSION")
                && normalized.contains("第1关");
        return (fruitContext || versionedGameScreen)
                && containsAny(normalized, "开始游戏", "进入游戏", "再来一局");
    }

    private static boolean looksLikeLeaveConfirmation(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        return normalized.contains("再玩1关游戏")
                && normalized.contains("继续玩")
                && containsAny(normalized, "残忍离开", "现在离开任务");
    }

    private static boolean looksLikeTaskPanelText(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        return normalized.contains("去完成")
                && normalized.contains("看15秒视频")
                && (normalized.contains("妈蚁庄园")
                || normalized.contains("芭芭农场")
                || normalized.contains("收益+10%"));
    }

    private static boolean looksLikeCompletedRoundText(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        if (normalized.contains("去完成") || normalized.contains("即可完成任务")) {
            return false;
        }
        return containsAny(normalized,
                "任务完成", "已完成", "恭喜过关", "过关成功", "下一关");
    }

    public static boolean looksLikeFailedRound(String text) {
        return text != null && containsAny(text,
                "失败", "再试一次", "再来一次", "重新开始", "挑战失败");
    }

    public static boolean looksLikeRevivePopup(String text) {
        return text != null && containsAny(text,
                "复活", "继续游戏", "再试", "免费观看");
    }

    public static boolean looksLikeBlockingFunctionPopupText(String text) {
        if (text == null) return false;
        if (looksLikeRewardToolPopup(text)) return true;
        // Normal board controls such as "打乱" and "消除" are not popup evidence.
        // Require explicit modal/confirmation language before attempting a close.
        return containsAny(text,
                "弹窗", "弹出", "复活", "继续游戏", "免费观看",
                "购买道具", "道具已获得", "确定", "取消", "关闭", "知道了",
                "广告", "广告加载", "跳过广告", "激励视频", "道具弹窗",
                "再试一次", "再来一次");
    }

    private static boolean looksLikeRewardToolPopup(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        boolean unlock = containsAny(normalized,
                "解锁所有槽位", "解锁所有檀位", "解锁所有糟位",
                "解锁所有槽", "解锁所有檀", "解锁所有糟")
                || (normalized.contains("解锁")
                && normalized.contains("所有")
                && containsAny(normalized, "位", "槽", "檀", "糟"));
        boolean eliminate = containsAny(normalized,
                "开局消除多组水果", "开局消除多組水果");
        return (unlock || eliminate)
                && containsAny(normalized, "使用", "视频", "廣告", "广告");
    }

    public static boolean isBlockedPosition(int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return true;
        return x < Math.round(width * 0.04f)
                || x > Math.round(width * 0.96f)
                || y < Math.round(height * 0.14f)
                || y > Math.round(height * 0.84f);
    }

    public static boolean allowsBridgePush(int x, int y, int width, int height) {
        return !isBlockedPosition(x, y, width, height);
    }

    public static boolean allowsThirdSlotCascade(int x, int y, int width, int height) {
        return !isBlockedPosition(x, y, width, height);
    }

    public static boolean allowsCascadeOccupancy(int x, int y, int width, int height) {
        return !isBlockedPosition(x, y, width, height);
    }

    public static boolean allowsLastSlotPush(int x, int y, int width, int height) {
        return !isBlockedPosition(x, y, width, height);
    }

    public static boolean isBridgeMateSimilarity(double score) {
        return score <= 0.34;
    }

    public static TeachingStateV464 captureTeachingStateV464(Context context, String suPath) {
        if (context == null || suPath == null || suPath.trim().isEmpty()) {
            return new TeachingStateV464(-1, -1, 0, 0, 0, 0, 0, 0);
        }
        Bitmap frame = ScreenOcr.captureBitmap(context, suPath, null);
        if (frame == null) {
            return new TeachingStateV464(-1, -1, 0, 0, 0, 0, 0, 0);
        }
        try {
            FruitBoardState state = FruitVisionEngine.observe(frame);
            int objects = state.boardFruits.size();
            int directPairs = 0;
            for (int i = 0; i < state.boardFruits.size(); i++) {
                for (int j = i + 1; j < state.boardFruits.size(); j++) {
                    if (state.boardFruits.get(i).similarityDistance(state.boardFruits.get(j)) < 0.29) {
                        directPairs++;
                    }
                }
            }
            int droppable = 0;
            for (FruitBoardState.Fruit fruit : state.boardFruits) {
                if (!isLikelyBlocked(state, fruit)) droppable++;
            }
            int blocked = Math.max(0, objects - droppable);
            int unlockGain = 0;
            int continuationPairs = 0;
            for (FruitBoardState.Fruit fruit : state.boardFruits) {
                if (countOverlaps(state, fruit) > 0) unlockGain++;
                if (countMatches(state, fruit) >= 2) continuationPairs++;
            }
            return new TeachingStateV464(
                    objects,
                    state.trayCount(),
                    objects,
                    droppable,
                    blocked,
                    directPairs,
                    Math.min(8, unlockGain),
                    Math.min(8, continuationPairs)
            );
        } finally {
            if (!frame.isRecycled()) frame.recycle();
        }
    }

    private static UiDecision probeUi(Host host) {
        ScreenOcr.Snapshot snapshot = host.ocr("水果界面探测");
        if (snapshot == null || snapshot.isEmpty()) return UiDecision.NONE;

        String text = snapshot.fullText;

        // Strong page guards must run before generic words like “完成/继续”.
        if (looksLikeTaskPanelText(text)) {
            host.log("[水果页面守卫] OCR确认已回任务面板");
            return UiDecision.LEFT_GAME;
        }
        if (looksLikeFailedRound(text)) return UiDecision.FAILED;
        if (looksLikeCompletedRoundText(text)) return UiDecision.COMPLETED;

        if (looksLikeLeaveConfirmation(text)) {
            ScreenOcr.Item item = findTextCandidate(snapshot, "继续玩");
            if (item != null && host.tap(
                    item.centerX(), item.centerY(), "FRUIT_UI_CONTINUE")) {
                host.log("[水果UI] 离开确认弹窗 → 点击‘继续玩’");
                return UiDecision.PAGE_CHANGED;
            }
            host.log("[水果UI] 检测到离开确认弹窗，但未取得可靠‘继续玩’坐标");
            return UiDecision.NONE;
        }

        if (looksLikeRevivePopup(text)
                && text.contains("还剩")
                && containsAny(text, "过关", "复活吗")) {
            ScreenOcr.Item item = findTextCandidate(snapshot, "复活");
            if (item != null && host.tap(
                    item.centerX(), item.centerY(), "FRUIT_UI_REVIVE")) {
                host.log("[水果UI] 失败/复活弹窗 → 点击‘复活’");
                return UiDecision.PAGE_CHANGED;
            }
            host.log("[水果UI] 检测到复活弹窗，但未取得可靠‘复活’坐标");
            return UiDecision.NONE;
        }

        if (looksLikeFruitStartScreen(text)) {
            ScreenOcr.Item item = findTextCandidate(snapshot, "开始游戏", "进入游戏");
            if (item != null && host.tap(
                    item.centerX(), item.centerY(), "水果游戏-开始游戏")) {
                host.log("[水果UI] 开始页 → 点击‘开始游戏’");
                return UiDecision.PAGE_CHANGED;
            }
            host.log("[水果UI] 检测到开始页，但未取得可靠开始按钮坐标");
            return UiDecision.NONE;
        }

        if (looksLikeRewardToolPopup(text)) {
            ScreenOcr.Item close = findToolModalCloseCandidate(snapshot);
            // Measured from 13737.mp4: close button center is about
            // (879,500) on 1080x2340 => normalized (0.814, 0.214).
            int closeX = close != null
                    ? close.centerX() : Math.round(snapshot.width * 0.814f);
            int closeY = close != null
                    ? close.centerY() : Math.round(snapshot.height * 0.214f);

            if (GameTapPolicy.allows(
                    closeX, closeY, snapshot.width, snapshot.height,
                    "FRUIT_TOOL_MODAL_CLOSE")
                    && host.tap(closeX, closeY, "FRUIT_TOOL_MODAL_CLOSE")) {
                host.log("[水果弹窗] 已关闭奖励道具弹窗；不点击视频/使用按钮");
                return UiDecision.POPUP_CLOSED;
            }

            host.log("[水果弹窗] 已识别奖励道具弹窗，但关闭坐标未通过安全白名单");
            return UiDecision.NONE;
        }

        boolean popupEvidence = looksLikeBlockingFunctionPopupText(text)
                || containsAny(text, "购买道具", "道具已获得", "广告加载", "激励视频");
        if (popupEvidence) {
            ScreenOcr.Item close = findCloseCandidate(snapshot);
            if (close != null) {
                if (host.tap(close.centerX(), close.centerY(), "POPUP_CLOSE")) {
                    host.log("[水果弹窗] 已关闭明确识别到的关闭控件");
                    return UiDecision.POPUP_CLOSED;
                }
            }

            int closeX = Math.round(snapshot.width * 0.94f);
            int closeY = Math.round(snapshot.height * 0.08f);
            if (GameTapPolicy.allows(closeX, closeY, snapshot.width, snapshot.height,
                    "POPUP_CLOSE_TOP_RIGHT")) {
                if (host.tap(closeX, closeY, "POPUP_CLOSE_TOP_RIGHT")) {
                    host.log("[水果弹窗] OCR确认阻塞层但关闭字符不可读；尝试右上角关闭");
                    return UiDecision.POPUP_CLOSED;
                }
            }
        }

        return UiDecision.NONE;
    }

    private static ScreenOcr.Item findTextCandidate(
            ScreenOcr.Snapshot snapshot,
            String... values
    ) {
        ScreenOcr.Item best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ScreenOcr.Item item : snapshot.items) {
            String t = item.text == null ? "" : item.text.replace(" ", "");
            boolean matched = false;
            for (String value : values) {
                if (value != null && !value.isEmpty() && t.contains(value)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) continue;

            int score = 100;
            // Real modal/start buttons are normally in the central/lower area.
            if (item.centerX() > snapshot.width * 0.18
                    && item.centerX() < snapshot.width * 0.82) score += 30;
            if (item.centerY() > snapshot.height * 0.35
                    && item.centerY() < snapshot.height * 0.92) score += 30;
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static ScreenOcr.Item findToolModalCloseCandidate(
            ScreenOcr.Snapshot snapshot
    ) {
        ScreenOcr.Item best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ScreenOcr.Item item : snapshot.items) {
            String t = item.text == null ? "" : item.text.trim();
            boolean explicitClose = t.contains("×")
                    || t.equalsIgnoreCase("x")
                    || t.contains("关闭");
            if (!explicitClose) continue;

            double nx = item.centerX() / (double) Math.max(1, snapshot.width);
            double ny = item.centerY() / (double) Math.max(1, snapshot.height);
            if (nx < .76 || nx > .87 || ny < .17 || ny > .26) continue;

            int score = 100
                    - (int) (Math.abs(nx - .814) * 300)
                    - (int) (Math.abs(ny - .214) * 300);
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static ScreenOcr.Item findCloseCandidate(ScreenOcr.Snapshot snapshot) {
        ScreenOcr.Item best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ScreenOcr.Item item : snapshot.items) {
            String t = item.text == null ? "" : item.text.trim();
            boolean explicitClose = t.contains("关闭")
                    || t.contains("取消")
                    || t.contains("知道")
                    || t.contains("×")
                    || t.equalsIgnoreCase("x");
            if (!explicitClose) continue;

            int score = 100;
            if (item.centerX() > snapshot.width * 0.78) score += 30;
            if (item.centerY() < snapshot.height * 0.35) score += 30;
            if (item.centerX() > snapshot.width * 0.82
                    && item.centerY() < snapshot.height * 0.18) score += 60;
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static boolean looksLikeRoundComplete(FruitBoardState state) {
        return state != null && state.boardFruits.isEmpty() && state.trayCount() == 0;
    }

    private static boolean isLikelyBlocked(
            FruitBoardState state,
            FruitBoardState.Fruit target
    ) {
        return countOverlaps(state, target) >= 2;
    }

    private static int countOverlaps(FruitBoardState state, FruitBoardState.Fruit target) {
        int count = 0;
        for (FruitBoardState.Fruit other : state.boardFruits) {
            if (other == target) continue;
            if (target.centerX >= other.left && target.centerX <= other.right
                    && target.centerY >= other.top && target.centerY <= other.bottom) {
                count++;
            }
        }
        return count;
    }

    private static int countMatches(FruitBoardState state, FruitBoardState.Fruit target) {
        int count = 0;
        for (FruitBoardState.Fruit other : state.boardFruits) {
            if (other == target) continue;
            if (target.similarityDistance(other) < 0.29) count++;
        }
        return count;
    }

    private static int extractRemainingCount(String text) {
        if (text == null || text.isEmpty()) return -1;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(
                        "(?:剩余|剩小|还剩)\\s*([0-9]{1,4})"
                ).matcher(text);
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean containsAny(String text, String... values) {
        if (text == null) return false;
        for (String value : values) {
            if (value != null && !value.isEmpty() && text.contains(value)) return true;
        }
        return false;
    }

    private enum UiDecision {
        NONE,
        PAGE_CHANGED,
        POPUP_CLOSED,
        LEFT_GAME,
        COMPLETED,
        FAILED
    }
}
