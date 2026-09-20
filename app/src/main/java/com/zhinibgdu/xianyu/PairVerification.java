package com.zhinibgdu.xianyu;

/** OCR jitter, a transient miss, or a momentary count bounce should not be treated as proof that no pair was cleared. */
final class PairVerification {
    private PairVerification() {}

    static boolean confirmed(int before, int after) {
        if (before < 2 || after < 0 || before <= after) {
            return false;
        }
        return before - after >= 2;
    }

    /** True only when a previously visible task row is no longer visible. */
    static boolean rowDisappeared(boolean beforePresent, boolean afterPresent) {
        return beforePresent && !afterPresent;
    }

    /** Unknown progress must not be treated as an increase. */
    static boolean progressIncreased(int before, int after) {
        return before >= 0 && after >= 0 && after > before;
    }
}
