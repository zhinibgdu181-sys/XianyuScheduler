package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * V4.63: structural strategy experience for the fruit solver.
 *
 * The store remembers which already-safety-validated strategy families worked
 * in similar board states. It never authorizes a tap, changes occlusion gates,
 * or bypasses GameTapPolicy. Memory is only a bounded ranking prior.
 *
 * Human demonstrations have an even stricter admission path: only a verified
 * positive structural outcome may reinforce a known strategy family. Raw manual
 * taps, manual-takeover events, ambiguous observations, and rejected outcomes
 * never enter the ranking store.
 */
final class FruitStrategyExperienceStore {

    static final String STRATEGY_TRAY_MATCH = "TRAY_MATCH";
    static final String STRATEGY_PAIR = "PAIR";
    static final String STRATEGY_SAFE_PUSH = "SAFE_PUSH";
    static final String STRATEGY_LAST_SLOT_PUSH = "LAST_SLOT_PUSH";
    static final String STRATEGY_DEPENDENCY_PUSH = "DEPENDENCY_PUSH";
    static final String STRATEGY_TRAY_UNBLOCK = "TRAY_UNBLOCK";
    static final String STRATEGY_EXPLORATION = "EXPLORATION";

    private static final String PREFS = "xianyu_task_profiles_v48";
    private static final String PREFIX = "fruit_strategy_exp_v2_";
    private static final int MAX_OUTCOME_COUNT = 12;
    private static final long TTL_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final double MAX_BIAS = 0.18;

    private FruitStrategyExperienceStore() {}

    /**
     * Returns a small bounded bias. It is deliberately much smaller than the
     * vision score range so historical experience can reorder competing safe
     * strategies but cannot make an unsafe candidate appear safe.
     */
    static double bias(
            Context context,
            String strategy,
            int remaining,
            int trayCount,
            int objects,
            int droppable,
            int blocked,
            int directPairs,
            int unlockGain,
            int continuationPairs
    ) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null || !isKnownStrategy(strategy)) return 0.0;

        String key = key(strategy, remaining, trayCount, objects, droppable,
                blocked, directPairs, unlockGain, continuationPairs);
        int success = prefs.getInt(key + "_success", 0);
        int failure = prefs.getInt(key + "_failure", 0);
        if (success <= 0 && failure <= 0) return 0.0;

        long last = prefs.getLong(key + "_last", 0L);
        if (last > 0L && System.currentTimeMillis() - last > TTL_MS) return 0.0;

        return biasFromCounts(success, failure);
    }

    static boolean isKnownStrategy(String strategy) {
        return STRATEGY_TRAY_MATCH.equals(strategy)
                || STRATEGY_PAIR.equals(strategy)
                || STRATEGY_SAFE_PUSH.equals(strategy)
                || STRATEGY_LAST_SLOT_PUSH.equals(strategy)
                || STRATEGY_DEPENDENCY_PUSH.equals(strategy)
                || STRATEGY_TRAY_UNBLOCK.equals(strategy)
                || STRATEGY_EXPLORATION.equals(strategy);
    }

    static double biasFromCounts(int success, int failure) {
        success = Math.max(0, Math.min(MAX_OUTCOME_COUNT, success));
        failure = Math.max(0, Math.min(MAX_OUTCOME_COUNT, failure));
        int total = success + failure;
        if (total <= 0) return 0.0;

        // Laplace smoothing + confidence ramp. The first observation cannot swing
        // the decision; repeated verified outcomes are needed before the memory
        // meaningfully reorders strategies.
        double posteriorSuccess = (success + 1.0) / (total + 2.0);
        double confidence = Math.min(1.0, total / 6.0);
        double bias = (posteriorSuccess - 0.5) * 2.0 * MAX_BIAS * confidence;
        return Math.max(-MAX_BIAS, Math.min(MAX_BIAS, bias));
    }

    static void record(
            Context context,
            String strategy,
            int remaining,
            int trayCount,
            int objects,
            int droppable,
            int blocked,
            int directPairs,
            int unlockGain,
            int continuationPairs,
            boolean success
    ) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null || !isKnownStrategy(strategy)) return;

        String key = key(strategy, remaining, trayCount, objects, droppable,
                blocked, directPairs, unlockGain, continuationPairs);
        String suffix = success ? "_success" : "_failure";
        int old = prefs.getInt(key + suffix, 0);
        prefs.edit()
                .putInt(key + suffix, Math.min(MAX_OUTCOME_COUNT, old + 1))
                .putLong(key + "_last", System.currentTimeMillis())
                .apply();
    }

    /**
     * Human teaching admission point.
     *
     * This deliberately has no boolean parameter: a human demonstration may only
     * reinforce the strategy store through this method after the caller has
     * positively verified a structural improvement. This prevents a future caller
     * from accidentally feeding manual/ambiguous/failure observations into memory.
     */
    static void recordVerifiedHumanSuccess(
            Context context,
            String strategy,
            int remaining,
            int trayCount,
            int objects,
            int droppable,
            int blocked,
            int directPairs,
            int unlockGain,
            int continuationPairs
    ) {
        if (!isKnownStrategy(strategy)) return;
        record(context, strategy, remaining, trayCount, objects, droppable, blocked,
                directPairs, unlockGain, continuationPairs, true);
    }

    /**
     * Pure structural signature used both by runtime storage and regression tests.
     * Coordinates are intentionally absent: the same kind of puzzle can appear
     * at different pixels on a later round.
     */
    static String structuralKey(
            int remaining,
            int trayCount,
            int objects,
            int droppable,
            int blocked,
            int directPairs,
            int unlockGain,
            int continuationPairs
    ) {
        return "R" + bucket(remaining, 4, 80)
                + "_T" + clamp(trayCount, -1, 3)
                + "_O" + bucket(objects, 4, 25)
                + "_D" + bucket(droppable, 1, 12)
                + "_B" + bucket(blocked, 2, 12)
                + "_P" + bucket(directPairs, 1, 8)
                + "_U" + bucket(unlockGain, 1, 8)
                + "_C" + bucket(continuationPairs, 1, 8);
    }

    static String strategyKey(
            String strategy,
            int remaining,
            int trayCount,
            int objects,
            int droppable,
            int blocked,
            int directPairs,
            int unlockGain,
            int continuationPairs
    ) {
        return strategy + "|" + structuralKey(
                remaining, trayCount, objects, droppable, blocked,
                directPairs, unlockGain, continuationPairs);
    }

    private static String key(
            String strategy,
            int remaining,
            int trayCount,
            int objects,
            int droppable,
            int blocked,
            int directPairs,
            int unlockGain,
            int continuationPairs
    ) {
        String raw = strategyKey(strategy, remaining, trayCount, objects, droppable,
                blocked, directPairs, unlockGain, continuationPairs);
        return PREFIX + Integer.toHexString(raw.hashCode())
                + "_" + Integer.toHexString(reverseHash(raw));
    }

    private static int reverseHash(String value) {
        return new StringBuilder(value == null ? "" : value).reverse().toString().hashCode();
    }

    private static int bucket(int value, int width, int maxBucket) {
        if (value < 0) return -1;
        return Math.min(maxBucket, value / Math.max(1, width));
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static SharedPreferences prefs(Context context) {
        return context == null
                ? null
                : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String describe(
            String strategy,
            double bias
    ) {
        return String.format(Locale.US, "%s bias=%+.3f", strategy, bias);
    }
}
