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

        final long deadline = System.currentTimeMillis() + MAX_ROUND_MS;
        final Set<String> blockedActions = new HashSet<>();

        long nextUiProbe = 0L;
        int badVisionStreak = 0;
        int unknownTrayStreak = 0;

        host.log("[水果规则引擎] 4.47.0：模板类别 + 三槽状态机");
        host.log("[水果规则引擎] 只点击模板已知且判定未遮挡的水果；死局直接重开");

        while (!host.aborted() && System.currentTimeMillis() < deadline) {
            long now = System.currentTimeMillis();

            if (now >= nextUiProbe) {
                UiDecision ui = probeUi(host);
                nextUiProbe = now + UI_PROBE_INTERVAL_MS;
                if (ui == UiDecision.COMPLETED) return Result.COMPLETED;
                if (ui == UiDecision.FAILED) {
                    host.log("[水果规则引擎] 游戏失败页出现，尝试自动重开");
                    if (tryDeadlockRestart(host, null, "游戏失败")) {
                        blockedActions.clear();
                        badVisionStreak = 0;
                        unknownTrayStreak = 0;
                        host.sleep(650L, 900L);
                        continue;
                    }
                    return Result.GAME_FAILED;
                }
                if (ui == UiDecision.LEFT_GAME) return Result.NOT_FRUIT_GAME;
                if (ui == UiDecision.PAGE_CHANGED || ui == UiDecision.POPUP_CLOSED) {
                    blockedActions.clear();
                    badVisionStreak = 0;
                    unknownTrayStreak = 0;
                    host.sleep(180L, 300L);
                    continue;
                }
            }

            Bitmap frame = ScreenOcr.captureBitmap(
                    context,
                    suPath,
                    () -> host.aborted()
            );
            if (frame == null) {
                if (++badVisionStreak >= MAX_NO_PROGRESS) {
                    host.log("[水果规则引擎] 连续截图失败，保留现场");
                    return Result.SAFE_STOP_DIRTY;
                }
                host.sleep(90L, 150L);
                continue;
            }

            FruitBoardState raw;
            FruitTemplateMatcher.State state;
            try {
                raw = FruitVisionEngine.observe(frame);
                state = FruitTemplateMatcher.classify(raw);
                if (raw.width > 0 && raw.height > 0) {
                    host.onFrameSize(raw.width, raw.height);
                }
            } finally {
                if (!frame.isRecycled()) frame.recycle();
            }

            if (looksLikeRoundComplete(raw)) {
                ScreenOcr.Snapshot done = host.ocr("水果通关确认");
                if (done != null && !done.isEmpty()) {
                    if (looksLikeCompletedRoundText(done.fullText)) {
                        host.log("[水果规则引擎] OCR确认通关");
                        return Result.COMPLETED;
                    }
                    if (looksLikeFailedRound(done.fullText)) {
                        if (tryDeadlockRestart(host, state, "失败页")) {
                            blockedActions.clear();
                            host.sleep(650L, 900L);
                            continue;
                        }
                        return Result.GAME_FAILED;
                    }
                }
            }

            int knownBoard = 0;
            int uncoveredBoard = 0;
            int unknownBoard = 0;
            for (FruitTemplateMatcher.DetectedFruit fruit : state.board) {
                if (fruit.known()) {
                    knownBoard++;
                    if (fruit.uncovered) uncoveredBoard++;
                } else {
                    unknownBoard++;
                }
            }

            host.log("[水果模板] board=" + state.board.size()
                    + " known=" + knownBoard
                    + " uncovered=" + uncoveredBoard
                    + " unknown=" + unknownBoard
                    + " tray=" + state.tray.size()
                    + " trayTypes=" + trayTypeSummary(state));

            if (state.board.size() < 4 && extractRemainingCount(
                    textOf(host.ocr("水果模板一致性检查"))) >= 20) {
                badVisionStreak++;
                host.log("[水果模板] 当前帧候选过少，重新截图，不冒险点击");
                if (badVisionStreak >= MAX_NO_PROGRESS) {
                    return Result.SAFE_STOP_DIRTY;
                }
                host.sleep(100L, 160L);
                continue;
            }
            badVisionStreak = 0;

            FruitRuleDecisionEngine.Decision decision =
                    FruitRuleDecisionEngine.decide(state, blockedActions);

            host.log("[水果决策] " + decision.kind + " / " + decision.reason);

            if (decision.kind == FruitRuleDecisionEngine.Kind.RESCAN_UNKNOWN) {
                unknownTrayStreak++;
                if (unknownTrayStreak >= 3) {
                    host.log("[水果决策] 槽位连续3帧无法可靠分类，为避免误点新种类，执行重开");
                    if (tryDeadlockRestart(host, state, "槽位模板连续未知")) {
                        blockedActions.clear();
                        unknownTrayStreak = 0;
                        host.sleep(650L, 900L);
                        continue;
                    }
                    return Result.SAFE_STOP_DIRTY;
                }
                host.sleep(120L, 180L);
                continue;
            }
            unknownTrayStreak = 0;

            if (decision.kind == FruitRuleDecisionEngine.Kind.WAIT_TRANSIENT) {
                host.sleep(180L, 280L);
                continue;
            }

            if (decision.kind == FruitRuleDecisionEngine.Kind.RESTART_DEADLOCK) {
                host.log("[水果死局] " + decision.reason);
                if (tryDeadlockRestart(host, state, decision.reason)) {
                    blockedActions.clear();
                    host.sleep(650L, 900L);
                    continue;
                }
                host.log("[水果死局] 未能确认重开按钮，停止而不是乱点");
                return Result.SAFE_STOP_DIRTY;
            }

            FruitTemplateMatcher.DetectedFruit target = decision.target;
            if (target == null || target.fruit == null
                    || !target.known() || !target.uncovered) {
                host.log("[水果规则引擎] 决策目标不是可靠顶层模板水果，重新识别");
                host.sleep(100L, 160L);
                continue;
            }

            int x = target.fruit.centerX;
            int y = target.fruit.centerY;
            if (!GameTapPolicy.allows(
                    x, y, state.width, state.height,
                    "RULE_FRUIT_CLICK")) {
                host.log("[水果规则引擎] 安全白名单拒绝水果点击 @"
                        + x + "," + y);
                blockedActions.add(decision.actionKey());
                continue;
            }

            ScreenOcr.Snapshot guard = host.ocr("水果规则点击前守卫");
            if (guard != null && !guard.isEmpty()) {
                String text = guard.fullText;
                if (looksLikeTaskPanelText(text)) return Result.NOT_FRUIT_GAME;
                if (looksLikeFailedRound(text)) {
                    if (tryDeadlockRestart(host, state, "点击前检测到失败页")) {
                        blockedActions.clear();
                        host.sleep(650L, 900L);
                        continue;
                    }
                    return Result.GAME_FAILED;
                }
                if (looksLikeBlockingFunctionPopupText(text)
                        || looksLikeLeaveConfirmation(text)
                        || looksLikeFruitStartScreen(text)) {
                    nextUiProbe = 0L;
                    continue;
                }
            }

            String beforeSignature = templateStateSignature(state);
            String actionKey = decision.actionKey();

            host.log("[水果执行] " + target.type
                    + " conf=" + String.format(Locale.US, "%.2f", target.confidence)
                    + " @" + x + "," + y
                    + " / " + decision.reason);

            if (!host.tap(x, y, "RULE_FRUIT_CLICK")) {
                if (!actionKey.isEmpty()) blockedActions.add(actionKey);
                host.log("[水果执行] ROOT点击失败，当前候选加入黑名单");
                continue;
            }

            if (!host.sleep(280L, 430L)) return Result.ABORTED;

            ScreenOcr.Snapshot afterOcr = host.ocr("水果单击后守卫");
            if (afterOcr != null && !afterOcr.isEmpty()) {
                if (looksLikeCompletedRoundText(afterOcr.fullText)) {
                    return Result.COMPLETED;
                }
                if (looksLikeFailedRound(afterOcr.fullText)) {
                    host.log("[水果规则引擎] 点击后进入失败页，自动重开");
                    if (tryDeadlockRestart(host, state, "点击后失败")) {
                        blockedActions.clear();
                        host.sleep(650L, 900L);
                        continue;
                    }
                    return Result.GAME_FAILED;
                }
                if (looksLikeBlockingFunctionPopupText(afterOcr.fullText)
                        || looksLikeLeaveConfirmation(afterOcr.fullText)) {
                    nextUiProbe = 0L;
                    continue;
                }
            }

            Bitmap verifyFrame = ScreenOcr.captureBitmap(
                    context,
                    suPath,
                    () -> host.aborted()
            );
            if (verifyFrame == null) {
                continue;
            }

            FruitTemplateMatcher.State afterState;
            try {
                afterState = FruitTemplateMatcher.classify(
                        FruitVisionEngine.observe(verifyFrame)
                );
            } finally {
                if (!verifyFrame.isRecycled()) verifyFrame.recycle();
            }

            String afterSignature = templateStateSignature(afterState);
            if (beforeSignature.equals(afterSignature)) {
                if (!actionKey.isEmpty()) blockedActions.add(actionKey);
                host.log("[水果验证] 点击后模板状态完全未变化，拉黑当前坐标："
                        + actionKey);
            } else {
                blockedActions.clear();
                host.log("[水果验证] 状态已变化：tray "
                        + trayTypeSummary(state) + " -> "
                        + trayTypeSummary(afterState));
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[水果规则引擎] 达到单局最大运行时间，保留现场");
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
