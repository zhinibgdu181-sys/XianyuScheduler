package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTapPolicyTest {

    @Test
    public void markedWallKeepaliveZonesAreAllowed() {
        int w = 1440;
        int h = 3120;

        assertTrue(GameTapPolicy.allows(
                346, 2480, w, h, "WALL_KEEPALIVE_TAP"));
        assertTrue(GameTapPolicy.allows(
                1130, 2480, w, h, "WALL_KEEPALIVE_TAP"));

        assertTrue(GameTapPolicy.allows(
                259, 2465, w, h, "WALL_KEEPALIVE_SWIPE"));
        assertTrue(GameTapPolicy.allows(
                432, 2465, w, h, "WALL_KEEPALIVE_SWIPE"));
        assertTrue(GameTapPolicy.allows(
                1008, 2465, w, h, "WALL_KEEPALIVE_SWIPE"));
        assertTrue(GameTapPolicy.allows(
                1181, 2465, w, h, "WALL_KEEPALIVE_SWIPE"));
    }

    @Test
    public void centerGapAndLowerButtonsAreNotWallKeepaliveZones() {
        int w = 1440;
        int h = 3120;

        assertFalse(GameTapPolicy.allows(
                720, 2480, w, h, "WALL_KEEPALIVE_TAP"));
        assertFalse(GameTapPolicy.allows(
                346, 2920, w, h, "WALL_KEEPALIVE_TAP"));
        assertFalse(GameTapPolicy.allows(
                1130, 2920, w, h, "WALL_KEEPALIVE_SWIPE"));
    }
}
