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
    public void repeatedSameFruitDoesNotDeadlockPairing() {
        FruitBoardState.Fruit a = fruit(200, 700, 240, 70, 70, 0);
        FruitBoardState.Fruit b = fruit(500, 900, 239, 71, 71, 0);
        FruitBoardState.Fruit c = fruit(800, 1100, 241, 69, 69, 0);

        FruitBoardState state = new FruitBoardState(
                1080, 2400,
                Arrays.asList(a, b, c),
                null
        );

        FruitPlanner.Plan plan = FruitPlanner.plan(state);
        assertFalse(plan.isEmpty());
        assertTrue(plan.clicks.get(0).reason.startsWith("PAIR"));
    }

    @Test
    public void fullTrayDoesNotSuppressPairRescue() {
        FruitBoardState.Fruit tray = new FruitBoardState.Fruit(500, 2050, 465, 2015, 535, 2085,
                4900, 70, 220, 70, 120, 0.8f, 0.8f,
                new float[]{0.0f,0.0f,0.0f,0.0f,0.8f,0.0f,0.0f,0.0f,0.0f,0.0f,0.0f,0.0f});
        FruitBoardState state = new FruitBoardState(
                1080, 2400,
                Arrays.asList(
                        fruit(300, 900, 240, 70, 70, 0),
                        fruit(700, 1200, 230, 90, 80, 20)
                ),
                Arrays.asList(tray, tray, tray)
        );

        FruitPlanner.Plan plan = FruitPlanner.plan(state);
        assertFalse(plan.isEmpty());
        assertTrue(plan.reason.contains("执行一组后立即验证"));
    }

    @Test
    public void manySimilarFruitsStillProduceImmediatePair() {
        java.util.List<FruitBoardState.Fruit> fruits = new java.util.ArrayList<>();
        for (int i = 0; i < 70; i++) {
            int x = 80 + (i % 10) * 90;
            int y = 180 + (i / 10) * 120;
            float delta = (i % 3) - 1;
            fruits.add(fruit(x, y, 240 + delta, 70 - delta, 70, delta));
        }

        FruitBoardState state = new FruitBoardState(1080, 2400, fruits, null);
        FruitPlanner.Plan plan = FruitPlanner.plan(state);
        assertFalse(plan.isEmpty());
        assertTrue(plan.clicks.get(0).reason.startsWith("PAIR"));
    }

    @Test
    public void versionedStartScreenIsRecognizedWithoutFruitWord() {
        assertTrue(FruitGameSolver.looksLikeFruitStartScreen(
                "VERSION1.0.2 d6f51 开始游戏 第1关 排行榜"
        ));
    }


    @Test
    public void trayMatchUsesSingleBoardTap() {
        FruitBoardState.Fruit pending = fruit(540, 1850, 240, 210, 40, 50);
        FruitBoardState.Fruit mate = fruit(760, 1180, 241, 209, 42, 51);
        FruitBoardState.Fruit other = fruit(250, 900, 70, 80, 230, 220);

        FruitBoardState state = new FruitBoardState(
                1080, 2400,
                Arrays.asList(mate, other),
                Arrays.asList(pending)
        );

        FruitPlanner.Plan plan = FruitPlanner.plan(state);
        assertFalse(plan.isEmpty());
        assertTrue(plan.clicks.size() == 1);
        assertTrue("TRAY_MATCH".equals(plan.clicks.get(0).reason));
    }

}
