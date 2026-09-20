package com.zhinibgdu.xianyu;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameTapPolicyTest {

    @Test
    public void legacyKeepaliveAndShuffleAreDisabled() {
        int w = 1440;
        int h = 3120;

        assertFalse(GameTapPolicy.allows(
                346, 2480, w, h, "WALL_KEEPALIVE_TAP"));
        assertFalse(GameTapPolicy.allows(
                1130, 2480, w, h, "WALL_KEEPALIVE_SWIPE"));
        assertFalse(GameTapPolicy.allows(
                1120, 2920, w, h, "FRUIT_DEADLOCK_SHUFFLE"));
    }

    @Test
    public void ruleFruitClicksStayInsideBoard() {
        int w = 1440;
        int h = 3120;

        assertTrue(GameTapPolicy.allows(
                700, 1200, w, h, "RULE_FRUIT_CLICK"));
        assertFalse(GameTapPolicy.allows(
                700, 2500, w, h, "RULE_FRUIT_CLICK"));
    }

    @Test
    public void restartGearAndRestartButtonsHaveSeparateSafeZones() {
        int w = 1440;
        int h = 3120;

        assertTrue(GameTapPolicy.allows(
                108, 218, w, h, "水果游戏-死局设置"));
        assertFalse(GameTapPolicy.allows(
                700, 218, w, h, "水果游戏-死局设置"));

        assertTrue(GameTapPolicy.allows(
                720, 1500, w, h, "水果游戏-死局重新开始"));
        assertTrue(GameTapPolicy.allows(
                720, 1500, w, h, "水果游戏-死局确认重开"));
        assertFalse(GameTapPolicy.allows(
                80, 150, w, h, "水果游戏-死局重新开始"));
    }

    @Test
    public void rewardPopupCloseUsesMeasured13749ButtonArea() {
        int w = 1440;
        int h = 3120;

        // 13749.mp4: X center ≈ (930,655) on 1080x2340
        // => ≈ (1240,874) on 1440x3120.
        assertTrue(GameTapPolicy.allows(
                1240, 874, w, h, "FRUIT_TOOL_MODAL_CLOSE"));

        // Old 4.47.1 target mapped to popup title area and must now be rejected.
        assertFalse(GameTapPolicy.allows(
                1172, 668, w, h, "FRUIT_TOOL_MODAL_CLOSE"));
    }

}
