package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;

import java.util.Locale;

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

    private static final long MAX_ROUND_MS = 120_000L;
    private static final long UI_PROBE_INTERVAL_MS = 1_500L;
    private static final int MAX_NO_PROGRESS = 4;

    private FruitGameSolver() {}

    public static Result solveOneRound(Context context, String suPath, Host host) {
        if (context == null || suPath == null || suPath.trim().isEmpty() || host == null) {
            return Result.SAFE_STOP_CLEAN;
        }

        long deadline = System.currentTimeMillis() + MAX_ROUND_MS;
        long nextUiProbe = 0L;
        int noProgress = 0;
        int replanCount = 0;
        FruitBoardState current = null;

        host.log("[水果新求解器] 开始：完整截图→识别→搜索→执行→验证");
        host.log("[水果新求解器] 不继承旧 FruitGameSolver 的局部决策链");

        while (!host.aborted() && System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();

            if (now >= nextUiProbe) {
                UiDecision ui = probeUi(host);
                nextUiProbe = now + UI_PROBE_INTERVAL_MS;
                if (ui == UiDecision.COMPLETED) return Result.COMPLETED;
                if (ui == UiDecision.FAILED) return Result.GAME_FAILED;
                if (ui == UiDecision.POPUP_CLOSED) {
                    // Popup close changes the frame; immediately rebuild without
                    // consuming a no-progress retry.
                    replanCount++;
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
                            && containsAny(finalOcr.fullText,
                            "完成", "已完成", "任务完成", "下一关", "继续")) {
                        return Result.COMPLETED;
                    }
                }

                if (observed.boardFruits.isEmpty()) {
                    ScreenOcr.Snapshot emptyCheck = host.ocr("水果空板确认");
                    if (emptyCheck != null && !emptyCheck.isEmpty()) {
                        if (looksLikeFailedRound(emptyCheck.fullText)) return Result.GAME_FAILED;
                        if (containsAny(emptyCheck.fullText,
                                "完成", "已完成", "任务完成", "下一关", "继续")) {
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

                current = observed;
            } finally {
                if (!frame.isRecycled()) frame.recycle();
            }

            FruitPlanner.Plan plan = FruitPlanner.plan(current);
            host.log(String.format(
                    Locale.US,
                    "[水果搜索] R=%d T=%d 候选=%d score=%.3f reason=%s",
                    current.boardFruits.size(),
                    current.trayCount(),
                    plan.clicks.size(),
                    plan.score,
                    plan.reason
            ));

            if (plan.isEmpty()) {
                ScreenOcr.Snapshot stuck = host.ocr("水果无安全动作确认");
                if (stuck != null && !stuck.isEmpty()) {
                    if (looksLikeFailedRound(stuck.fullText)) return Result.GAME_FAILED;
                    if (containsAny(stuck.fullText,
                            "完成", "已完成", "任务完成", "下一关")) {
                        return Result.COMPLETED;
                    }
                }

                if (++noProgress >= MAX_NO_PROGRESS) {
                    host.log("[水果新求解器] 连续无安全动作，结束本轮并保留现场");
                    return Result.SAFE_STOP_DIRTY;
                }
                host.sleep(90L, 140L);
                continue;
            }

            FruitPlanner.Click click = plan.clicks.get(0);
            if (!GameTapPolicy.allows(
                    click.x, click.y, current.width, current.height, click.reason
            )) {
                host.log("[水果新求解器] GameTapPolicy 拒绝候选：" + click.x + "," + click.y);
                if (++noProgress >= MAX_NO_PROGRESS) return Result.SAFE_STOP_DIRTY;
                continue;
            }

            host.log("[水果执行] tap " + click.reason + " @" + click.x + "," + click.y);
            if (!host.tap(click.x, click.y, click.reason)) {
                host.log("[水果执行] 点击失败，立即重新规划");
                if (++noProgress >= MAX_NO_PROGRESS) return Result.SAFE_STOP_DIRTY;
                continue;
            }

            // A successful tap must earn a new observation. Do not sleep for a
            // fixed 15s or reuse the previous board as proof of success.
            if (!host.sleep(90L, 170L)) return Result.ABORTED;

            Bitmap afterFrame = ScreenOcr.captureBitmap(
                    context,
                    suPath,
                    () -> host.aborted()
            );
            if (afterFrame == null) {
                host.log("[水果验证] 点击后截图失败，下一轮立即重建状态");
                if (++noProgress >= MAX_NO_PROGRESS) return Result.SAFE_STOP_DIRTY;
                continue;
            }

            FruitBoardState after;
            try {
                after = FruitVisionEngine.observe(afterFrame);
                if (after.width > 0 && after.height > 0) host.onFrameSize(after.width, after.height);
            } finally {
                if (!afterFrame.isRecycled()) afterFrame.recycle();
            }

            int beforeCount = current.boardFruits.size();
            int afterCount = after.boardFruits.size();
            if (afterCount < beforeCount) {
                host.log("[水果验证] 成功：棋盘对象 " + beforeCount + " -> " + afterCount
                        + "；立即重新规划");
                noProgress = 0;
                current = after;
                continue;
            }

            int structuralChange = current.fingerprintChangesAgainst(after);
            if (structuralChange >= 1 && afterCount <= beforeCount + 1) {
                host.log("[水果验证] 棋盘发生结构变化=" + structuralChange + "，接受并重新规划");
                noProgress = 0;
                current = after;
                continue;
            }

            host.log("[水果验证] 首击无确认变化，立即废弃本候选并重新识别");
            noProgress++;
            replanCount++;
            if (replanCount % 3 == 0) {
                ScreenOcr.Snapshot retryProbe = host.ocr("水果连续候选失败确认");
                if (retryProbe != null && looksLikeFailedRound(retryProbe.fullText)) {
                    return Result.GAME_FAILED;
                }
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[水果新求解器] 达到本轮最大运行时间，安全停止并保留当前页面");
        return Result.SAFE_STOP_DIRTY;
    }

    public static boolean looksLikeFruitGame(String text) {
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        return normalized.contains("水果")
                || normalized.contains("二消")
                || normalized.contains("去消了还想消")
                || normalized.contains("果盘")
                || normalized.contains("槽位")
                || normalized.contains("消除水果");
    }

    public static boolean looksLikeFruitStartScreen(String text) {
        return looksLikeFruitGame(text)
                || (text != null && containsAny(text, "开始", "进入游戏", "再来一局"));
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
        return text != null && containsAny(text,
                "打乱", "消除", "解锁", "加槽", "购买", "道具", "使用道具");
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
        if (looksLikeFailedRound(text)) return UiDecision.FAILED;
        if (containsAny(text, "任务完成", "已完成", "下一关", "恭喜完成")) {
            return UiDecision.COMPLETED;
        }

        if (looksLikeBlockingFunctionPopupText(text)
                || looksLikeRevivePopup(text)
                || containsAny(text, "关闭", "×", "取消", "知道了")) {
            ScreenOcr.Item close = findCloseCandidate(snapshot);
            if (close != null) {
                if (host.tap(close.centerX(), close.centerY(), "POPUP_CLOSE")) {
                    host.log("[水果弹窗] 关闭后立即重建完整棋盘");
                    return UiDecision.POPUP_CLOSED;
                }
            }
        }

        return UiDecision.NONE;
    }

    private static ScreenOcr.Item findCloseCandidate(ScreenOcr.Snapshot snapshot) {
        ScreenOcr.Item best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ScreenOcr.Item item : snapshot.items) {
            String t = item.text == null ? "" : item.text;
            int score = 0;
            if (t.contains("关闭")) score += 100;
            if (t.contains("取消")) score += 70;
            if (t.contains("知道")) score += 60;
            if (t.contains("×") || t.equalsIgnoreCase("x")) score += 90;
            if (item.centerX() > snapshot.width * 0.78) score += 25;
            if (item.centerY() < snapshot.height * 0.40) score += 15;
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore > 0 ? best : null;
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

    private static boolean containsAny(String text, String... values) {
        if (text == null) return false;
        for (String value : values) {
            if (value != null && !value.isEmpty() && text.contains(value)) return true;
        }
        return false;
    }

    private enum UiDecision {
        NONE,
        POPUP_CLOSED,
        COMPLETED,
        FAILED
    }
}
