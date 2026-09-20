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
    private static final long UI_PROBE_INTERVAL_MS = 900L;
    /**
     * The game shows its own idle ad/reward layer after a period without input.
     * This is a watchdog threshold, not a reason to synthesize arbitrary taps.
     * The solver should re-observe/replan instead of deliberately waiting.
     */
    private static final long MAX_IDLE_BETWEEN_ACTIONS_MS = 2_200L;
    private static final int MAX_NO_PROGRESS = 4;

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
        FruitBoardState current = null;

        host.log("[水果新求解器] 开始：完整截图→识别→搜索→执行→验证");
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

            FruitPlanner.Plan plan = FruitPlanner.plan(current);
            host.log(String.format(
                    Locale.US,
                    "[水果搜索] R=%d T=%d 路线点击=%d score=%.3f reason=%s",
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

            /*
             * A plan now contains multiple complete pairs. We execute one pair,
             * verify the resulting frame, then continue with the already-searched
             * route only if the board transition is consistent. This removes the
             * expensive search between every pair without blindly replaying stale
             * coordinates.
             */
            boolean routeBroken = false;
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
                    if (!host.sleep(90L, 140L)) return Result.ABORTED;
                    continue;
                }

                if (!host.sleep(120L, 190L)) return Result.ABORTED;

                // UNLOCK is intentionally a one-click route. It must be
                // re-observed before another action is allowed.
                if (click.reason.equals("UNLOCK")) {
                    routeBroken = true;
                    break;
                }

                // PAIR_SECOND completes one predicted transition. Verify once per
                // pair instead of after every individual fruit click.
                Bitmap afterFrame = ScreenOcr.captureBitmap(
                        context,
                        suPath,
                        () -> host.aborted()
                );
                if (afterFrame == null) {
                    host.log("[水果验证] 路线截图失败，立即重新识别");
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

                if (afterCount < beforeCount || structuralChange >= 1) {
                    host.log("[水果验证] 路线段成功："
                            + beforeCount + " -> " + afterCount
                            + "，结构变化=" + structuralChange);
                    current = after;
                    noProgress = 0;
                    continue;
                }

                host.log("[水果验证] 路线段未产生可确认变化，"
                        + "立即废弃剩余路线并重新搜索");
                routeBroken = true;
                noProgress++;
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
        if (text == null) return false;
        String normalized = text.replace(" ", "");
        boolean fruitContext = containsAny(normalized,
                "水果", "二消", "果盘", "槽位", "消除水果", "去消了还想消");
        return fruitContext
                && containsAny(normalized, "开始", "进入游戏", "再来一局", "开始游戏", "继续");
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
        // Normal board controls such as "打乱" and "消除" are not popup evidence.
        // Require explicit modal/confirmation language before attempting a close.
        return containsAny(text,
                "弹窗", "弹出", "复活", "继续游戏", "免费观看",
                "购买道具", "道具已获得", "确定", "取消", "关闭", "知道了",
                "广告", "广告加载", "跳过广告", "激励视频", "道具弹窗",
                "再试一次", "再来一次");
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

        boolean popupEvidence = looksLikeBlockingFunctionPopupText(text)
                || looksLikeRevivePopup(text)
                || containsAny(text, "关闭", "×", "取消", "知道了",
                "广告", "广告加载", "跳过广告", "激励视频", "道具弹窗");
        if (popupEvidence) {
            ScreenOcr.Item close = findCloseCandidate(snapshot);
            if (close != null) {
                if (host.tap(close.centerX(), close.centerY(), "POPUP_CLOSE")) {
                    host.log("[水果弹窗] 已关闭识别到的关闭控件；900ms后再次检查");
                    return UiDecision.POPUP_CLOSED;
                }
            }

            // The ad close glyph is frequently rendered without useful OCR text.
            // Only use this coordinate fallback when OCR already proves that a
            // blocking/ad layer exists. Never tap the corner on a normal board.
            int closeX = Math.round(snapshot.width * 0.94f);
            int closeY = Math.round(snapshot.height * 0.08f);
            if (GameTapPolicy.allows(closeX, closeY, snapshot.width, snapshot.height,
                    "POPUP_CLOSE_TOP_RIGHT")) {
                if (host.tap(closeX, closeY, "POPUP_CLOSE_TOP_RIGHT")) {
                    host.log("[水果弹窗] OCR确认广告/弹窗但未识别关闭文字；尝试右上角关闭");
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
            // Strongly prefer the actual ad-close area in the upper-right.
            if (item.centerX() > snapshot.width * 0.82
                    && item.centerY() < snapshot.height * 0.18) score += 100;
            else if (item.centerX() > snapshot.width * 0.78) score += 25;
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

    private static int extractRemainingCount(String text) {
        if (text == null || text.isEmpty()) return -1;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("剩余\\s*([0-9]{1,4})")
                        .matcher(text);
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
        POPUP_CLOSED,
        COMPLETED,
        FAILED
    }
}
