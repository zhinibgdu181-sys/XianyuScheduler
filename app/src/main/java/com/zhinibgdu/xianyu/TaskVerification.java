package com.zhinibgdu.xianyu;

import java.util.Locale;

/**
 * Verification helper for task rows that are validated by OCR after a click/return flow.
 *
 * The key rule is: never treat a single OCR snapshot as final proof when the UI may still be
 * repainting after returning from a video/ad page. Use a short stability window and require a
 * real state change before we call it successful.
 */
final class TaskVerification {
    private TaskVerification() {}

    static final class Evidence {
        final boolean beforePresent;
        final boolean afterPresent;
        final int beforeProgress;
        final int afterProgress;
        final String beforeText;
        final String afterText;
        final String beforeAction;
        final String afterAction;

        Evidence(boolean beforePresent, boolean afterPresent,
                 int beforeProgress, int afterProgress,
                 String beforeText, String afterText,
                 String beforeAction, String afterAction) {
            this.beforePresent = beforePresent;
            this.afterPresent = afterPresent;
            this.beforeProgress = beforeProgress;
            this.afterProgress = afterProgress;
            this.beforeText = normalize(beforeText);
            this.afterText = normalize(afterText);
            this.beforeAction = normalizeAction(beforeAction);
            this.afterAction = normalizeAction(afterAction);
        }
    }

    enum Status {
        SUCCESS,
        UNVERIFIED,
        FAILED
    }

    static final class Result {
        final Status status;
        final String reason;

        private Result(Status status, String reason) {
            this.status = status;
            this.reason = reason;
        }

        static Result success(String reason) {
            return new Result(Status.SUCCESS, reason);
        }

        static Result unverified(String reason) {
            return new Result(Status.UNVERIFIED, reason);
        }

        static Result failed(String reason) {
            return new Result(Status.FAILED, reason);
        }
    }

    /**
     * Returns SUCCESS only when there is concrete evidence that the task row changed.
     * For video/ad return flows, a single OCR snapshot that still shows the task row is not enough
     * to mark the task as failed; a short stability window is required.
     */
    static Result verify(Evidence evidence) {
        if (evidence == null) {
            return Result.unverified("no_evidence");
        }

        if (PairVerification.rowDisappeared(evidence.beforePresent, evidence.afterPresent)) {
            return Result.success("row_disappeared");
        }

        if (PairVerification.progressIncreased(evidence.beforeProgress, evidence.afterProgress)) {
            return Result.success("progress_increased");
        }

        if (isActionChanged(evidence.beforeAction, evidence.afterAction)) {
            return Result.success("action_changed");
        }

        if (isTextCompleted(evidence.afterText)) {
            return Result.success("text_completed");
        }

        if (isTextMaybeReward(evidence.beforeText) && isTextMaybeReward(evidence.afterText)
                && !sameNormalizedText(evidence.beforeText, evidence.afterText)
                && !isNoProgressChange(evidence.beforeText, evidence.afterText)) {
            return Result.success("text_reflow");
        }

        if (evidence.afterPresent && evidence.beforePresent) {
            return Result.unverified("no_progress_change");
        }

        return Result.failed("row_missing_or_invalid_state");
    }

    private static boolean isActionChanged(String beforeAction, String afterAction) {
        if (beforeAction == null || afterAction == null) return false;
        return !beforeAction.equals(afterAction) && (!"unknown".equals(beforeAction) || !"unknown".equals(afterAction));
    }

    private static boolean isTextCompleted(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = normalize(text);
        return t.contains("已领取")
                || t.contains("已完成")
                || t.contains("完成领取")
                || t.contains("领取成功")
                || t.contains("已完成领取")
                || t.contains("已领")
                || t.contains("已达成");
    }

    private static boolean isTextMaybeReward(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = normalize(text);
        return t.contains("领取")
                || t.contains("奖励")
                || t.contains("去完成")
                || t.contains("看视频")
                || t.contains("视频奖励")
                || t.contains("收益")
                || t.contains("领奖")
                || t.contains("奖励已");
    }

    private static boolean isNoProgressChange(String beforeText, String afterText) {
        String b = normalize(beforeText);
        String a = normalize(afterText);
        return b.equals(a) || (b.contains("去完成") && a.contains("去完成"));
    }

    private static boolean sameNormalizedText(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return t.toLowerCase(Locale.ROOT);
    }

    private static String normalizeAction(String action) {
        if (action == null || action.trim().isEmpty()) return "unknown";
        String t = action.trim();
        if (t.contains("领取") || t.contains("奖励") || t.contains("成功")) return "claim";
        if (t.contains("去完成") || t.contains("继续") || t.contains("完成")) return "go";
        if (t.contains("已领") || t.contains("已完成")) return "done";
        return "unknown";
    }
}
