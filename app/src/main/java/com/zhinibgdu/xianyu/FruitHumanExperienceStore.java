package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * V4.64: records verified human-demonstration transitions.
 *
 * A human takeover is not itself treated as a successful strategy. We only
 * reinforce a strategy after a passive post-takeover observation proves that
 * the board made useful progress. The raw transition is also retained as a
 * compact audit trail so later versions can inspect real demonstrations.
 */
final class FruitHumanExperienceStore {

    private static final String PREFS = "xianyu_task_profiles_v48";
    private static final String LOG_KEY = "fruit_human_demo_v1";
    private static final int MAX_RECORDS = 120;

    private FruitHumanExperienceStore() {}

    static final class Transition {
        final String strategy;
        final boolean success;

        Transition(String strategy, boolean success) {
            this.strategy = strategy;
            this.success = success;
        }
    }

    static Transition classify(
            int beforeRemaining,
            int afterRemaining,
            int beforeTray,
            int afterTray,
            int beforeObjects,
            int afterObjects,
            int beforeBlocked,
            int afterBlocked
    ) {
        return classify(
                beforeRemaining, afterRemaining,
                beforeTray, afterTray,
                beforeObjects, afterObjects,
                beforeBlocked, afterBlocked,
                -1, -1,
                -1, -1
        );
    }

    /**
     * Rich human-demo classifier. A tray increase alone is not a success:
     * filling another tray slot can be a bad human move. SAFE_PUSH is admitted
     * only when the new tray occupant is accompanied by independently observed
     * follow-up evidence (a new direct pair or newly droppable fruit), and the
     * tray still has a spare slot.
     */
    static Transition classify(
            int beforeRemaining,
            int afterRemaining,
            int beforeTray,
            int afterTray,
            int beforeObjects,
            int afterObjects,
            int beforeBlocked,
            int afterBlocked,
            int beforeDroppable,
            int afterDroppable,
            int beforeDirectPairs,
            int afterDirectPairs
    ) {
        if (beforeRemaining >= 0 && afterRemaining >= 0
                && afterRemaining < beforeRemaining - 1) {
            return new Transition(FruitStrategyExperienceStore.STRATEGY_PAIR, true);
        }
        if (beforeTray >= 0 && afterTray >= 0 && afterTray < beforeTray) {
            return new Transition(FruitStrategyExperienceStore.STRATEGY_TRAY_MATCH, true);
        }
        // A tray increase is a distinct safety case. Do not let the generic
        // unblock rule classify a risky tray fill as successful experience.
        if (beforeTray >= 0 && afterTray >= 0 && afterTray > beforeTray) {
            boolean safePushEvidence =
                    (beforeDroppable >= 0 && afterDroppable > beforeDroppable)
                            || (beforeDirectPairs >= 0 && afterDirectPairs > beforeDirectPairs);
            if (afterTray < 3 && safePushEvidence) {
                return new Transition(FruitStrategyExperienceStore.STRATEGY_SAFE_PUSH, true);
            }
            return null;
        }
        if ((beforeObjects >= 0 && afterObjects >= 0 && afterObjects < beforeObjects)
                || (beforeBlocked >= 0 && afterBlocked >= 0 && afterBlocked < beforeBlocked)) {
            return new Transition(FruitStrategyExperienceStore.STRATEGY_TRAY_UNBLOCK, true);
        }
        return null;
    }

    /**
     * Two observations must represent the same structural state before a human
     * transition is admitted as verified. This filters transient OCR/animation
     * frames without learning or replaying the underlying human tap.
     */
    static boolean sameStructuralState(
            FruitGameSolver.TeachingStateV464 a,
            FruitGameSolver.TeachingStateV464 b
    ) {
        if (a == null || b == null) return false;
        return a.remaining == b.remaining
                && a.trayCount == b.trayCount
                && a.objects == b.objects
                && a.droppable == b.droppable
                && a.blocked == b.blocked
                && a.directPairs == b.directPairs
                && a.unlockGain == b.unlockGain
                && a.continuationPairs == b.continuationPairs;
    }

    /**
     * Impossible observations are rejected from strategy learning. They are audit
     * evidence only; no failure/success weight is changed for any strategy.
     */
    static String rejectReason(
            FruitGameSolver.TeachingStateV464 before,
            FruitGameSolver.TeachingStateV464 after
    ) {
        if (before == null || after == null) return "state_missing";
        if (before.remaining >= 0 && after.remaining >= 0
                && after.remaining > before.remaining) {
            return "remaining_increased";
        }
        if (after.trayCount > 3) return "tray_over_capacity";
        return null;
    }

    /**
     * Only a verified positive result on a known strategy family is allowed to
     * reinforce the runtime strategy store. A rejected/false/manual observation
     * can never be converted into strategy experience.
     */
    static boolean shouldReinforce(Transition transition) {
        return transition != null
                && transition.success
                && FruitStrategyExperienceStore.isKnownStrategy(transition.strategy);
    }

    /**
     * V4.80: structural progress is not enough to promote a human demonstration.
     * The task result must be independently verified as SUCCESS.
     */
    static boolean shouldReinforce(Context context, Transition transition) {
        return shouldReinforce(transition)
                && TeachingOutcomeStore.gameReplayEligible();
    }

    static void record(
            Context context,
            String taskName,
            FruitGameSolver.TeachingStateV464 before,
            FruitGameSolver.TeachingStateV464 after,
            Transition transition
    ) {
        if (context == null || before == null || after == null || transition == null) return;

        String rejected = rejectReason(before, after);
        if (rejected != null) {
            recordRejectedObservation(context, taskName, after, rejected);
            return;
        }

        String line = String.format(
                Locale.US,
                "%d|SESSION=%s|OUTCOME=PENDING|%s|VERIFIED|%s|R%d>%d|T%d>%d|O%d>%d|D%d>%d|B%d>%d|P%d|U%d|C%d",
                System.currentTimeMillis(),
                safe(TeachingOutcomeStore.currentSessionId()),
                safe(taskName),
                transition.strategy,
                before.remaining, after.remaining,
                before.trayCount, after.trayCount,
                before.objects, after.objects,
                before.droppable, after.droppable,
                before.blocked, after.blocked,
                before.directPairs,
                before.unlockGain,
                before.continuationPairs
        );
        appendAuditLine(context, line);

        if (!shouldReinforce(transition)) {
            return;
        }

        FruitStrategyExperienceStore.recordVerifiedHumanSuccess(
                context,
                transition.strategy,
                before.remaining,
                before.trayCount,
                before.objects,
                before.droppable,
                before.blocked,
                before.directPairs,
                before.unlockGain,
                before.continuationPairs
        );
    }

    /**
     * V4.80: promote deferred fruit transitions only after the task result
     * is independently verified as SUCCESS. FAILURE/UNKNOWN are audit-only.
     */
    static synchronized void promoteCurrentSession(Context context) {
        if (context == null || !TeachingOutcomeStore.gameReplayEligible()) return;
        String session = TeachingOutcomeStore.currentSessionId();
        if (session == null || session.isEmpty()) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(LOG_KEY, "");
        if (old == null || old.isEmpty()) return;

        String token = "|SESSION=" + safe(session) + "|OUTCOME=PENDING|";
        String[] rows = old.split("\\n");
        StringBuilder next = new StringBuilder();
        int promoted = 0;

        for (String row : rows) {
            String out = row;
            if (row != null && row.contains(token) && row.contains("|VERIFIED|")) {
                try {
                    String[] p = row.split("\\|");
                    if (p.length >= 14) {
                        String strategy = p[5];
                        int beforeRemaining = parseRight(p[6], 'R');
                        int afterRemaining = parseAfter(p[6]);
                        int beforeTray = parseRight(p[7], 'T');
                        int afterTray = parseAfter(p[7]);
                        int beforeObjects = parseRight(p[8], 'O');
                        int afterObjects = parseAfter(p[8]);
                        int beforeDroppable = parseRight(p[9], 'D');
                        int afterDroppable = parseAfter(p[9]);
                        int beforeBlocked = parseRight(p[10], 'B');
                        int afterBlocked = parseAfter(p[10]);
                        int beforeDirectPairs = parseIntSuffix(p[11]);
                        int unlockGain = parseIntSuffix(p[12]);
                        int continuationPairs = parseIntSuffix(p[13]);

                        if (FruitStrategyExperienceStore.isKnownStrategy(strategy)) {
                            FruitStrategyExperienceStore.recordVerifiedHumanSuccess(
                                    context, strategy,
                                    beforeRemaining, beforeTray, beforeObjects,
                                    beforeDroppable, beforeBlocked, beforeDirectPairs,
                                    unlockGain, continuationPairs);
                            out = row.replace(
                                    token,
                                    "|SESSION=" + safe(session) + "|OUTCOME=SUCCESS|");
                            promoted++;
                        }
                    }
                } catch (Throwable ignored) {
                    // Keep malformed audit rows untouched; never guess a strategy.
                }
            }
            if (next.length() > 0) next.append('\n');
            next.append(out);
        }

        prefs.edit().putString(LOG_KEY, next.toString()).apply();
    }

    private static int parseRight(String value, char prefix) {
        if (value == null || value.length() < 4 || value.charAt(0) != prefix) {
            throw new IllegalArgumentException("bad transition");
        }
        int arrow = value.indexOf('>');
        if (arrow <= 1) throw new IllegalArgumentException("bad transition");
        return Integer.parseInt(value.substring(1, arrow));
    }

    private static int parseAfter(String value) {
        int arrow = value.indexOf('>');
        if (arrow < 0 || arrow + 1 >= value.length()) {
            throw new IllegalArgumentException("bad transition");
        }
        return Integer.parseInt(value.substring(arrow + 1));
    }

    private static int parseIntSuffix(String value) {
        if (value == null || value.length() < 2) throw new IllegalArgumentException("bad number");
        return Integer.parseInt(value.substring(1));
    }

    /**
     * Records an observation that was deliberately excluded from learning.
     * This is useful for diagnosing human mistakes without ever making them
     * executable knowledge.
     */
    static void recordRejectedObservation(
            Context context,
            String taskName,
            FruitGameSolver.TeachingStateV464 state,
            String reason
    ) {
        if (context == null || state == null) return;
        String line = String.format(
                Locale.US,
                "%d|%s|REJECTED|%s|R%d|T%d|O%d|D%d|B%d|P%d|U%d|C%d",
                System.currentTimeMillis(),
                safe(taskName),
                safe(reason),
                state.remaining,
                state.trayCount,
                state.objects,
                state.droppable,
                state.blocked,
                state.directPairs,
                state.unlockGain,
                state.continuationPairs
        );
        appendAuditLine(context, line);
    }

    private static void appendAuditLine(Context context, String line) {
        if (context == null || line == null || line.isEmpty()) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String old = prefs.getString(LOG_KEY, "");
        String[] rows = old.isEmpty() ? new String[0] : old.split("\n");
        int start = Math.max(0, rows.length - MAX_RECORDS + 1);
        StringBuilder next = new StringBuilder();
        for (int i = start; i < rows.length; i++) {
            if (next.length() > 0) next.append('\n');
            next.append(rows[i]);
        }
        if (next.length() > 0) next.append('\n');
        next.append(line);
        prefs.edit().putString(LOG_KEY, next.toString()).apply();
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "_").replace("\n", " ").trim();
    }
}
