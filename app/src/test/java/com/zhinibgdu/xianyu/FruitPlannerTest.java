package com.zhinibgdu.xianyu;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FruitPlannerTest {

    private static FruitBoardState.Fruit fruit(int x, int y, float r, float g, float b, float hue) {
        return new FruitBoardState.Fruit(
                x, y, x - 35, y - 35, x + 35, y + 35,
                4900, r, g, b, hue, 0.8f, 0.8f,
                new float[]{0.8f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}
        );
    }

    @Test
    public void findsVisiblePair() {
        FruitBoardState state = new FruitBoardState(
                1080, 2400,
                Arrays.asList(
                        fruit(300, 900, 240, 70, 70, 0),
                        fruit(700, 1200, 238, 72, 72, 1)
                ),
                null
        );

        FruitPlanner.Plan plan = FruitPlanner.plan(state);
        assertFalse(plan.isEmpty());
        assertTrue(plan.clicks.get(0).reason.startsWith("PAIR"));
    }

    @Test
    public void fullTrayRejectsUnmatchedExploration() {
        FruitBoardState tray = new FruitBoardState.Fruit(500, 2050, 465, 2015, 535, 2085,
                4900, 70, 220, 70, 120, 0.8f, 0.8f,
                new float[]{0.0f,0.0f,0.0f,0.0f,0.8f,0.0f,0.0f,0.0f,0.0f,0.0f,0.0f,0.0f});
        FruitBoardState state = new FruitBoardState(
                1080, 2400,
                Arrays.asList(fruit(300, 900, 240, 70, 70, 0)),
                Arrays.asList(tray, tray, tray)
        );

        FruitPlanner.Plan plan = FruitPlanner.plan(state);
        assertTrue(plan.isEmpty());
    }
}
