package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTapPolicyTest {

    @Test
    public void oldWallKeepaliveActionsAreCompletelyDisabled() {
        int w = 1440;
        int h = 3120;

        assertFalse(GameTapPolicy.allows(
                346, 2480, w, h, "WALL_KEEPALIVE_TAP"));
        assertFalse(GameTapPolicy.allows(
                1130, 2480, w, h, "WALL_KEEPALIVE_TAP"));
        assertFalse(GameTapPolicy.allows(
                259, 2465, w, h, "WALL_KEEPALIVE_SWIPE"));
        assertFalse(GameTapPolicy.allows(
                1181, 2465, w, h, "WALL_KEEPALIVE_SWIPE"));
    }

    @Test
    public void deadlockShuffleOnlyAllowsBottomRightShuffleArea() {
        int w = 1440;
        int h = 3120;

        assertTrue(GameTapPolicy.allows(
                1120, 2920, w, h, "FRUIT_DEADLOCK_SHUFFLE"));

        assertFalse(GameTapPolicy.allows(
                720, 2920, w, h, "FRUIT_DEADLOCK_SHUFFLE"));
        assertFalse(GameTapPolicy.allows(
                1120, 1800, w, h, "FRUIT_DEADLOCK_SHUFFLE"));
    }

    @Test
    public void deadlockTrayMatchStaysInsideFruitBoard() {
        int w = 1440;
        int h = 3120;

        assertTrue(GameTapPolicy.allows(
                700, 1200, w, h, "DEADLOCK_TRAY_MATCH"));
        assertFalse(GameTapPolicy.allows(
                700, 2500, w, h, "DEADLOCK_TRAY_MATCH"));
    }
}
