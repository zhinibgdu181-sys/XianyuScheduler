package com.zhinibgdu.xianyu;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class FruitRuleDecisionEngineTest {

    private static FruitTemplateMatcher.DetectedFruit d(
            String type,
            int x,
            int y,
            boolean uncovered
    ) {
        FruitBoardState.Fruit raw = new FruitBoardState.Fruit(
                x, y,
                x - 35, y - 35, x + 35, y + 35,
                4900,
                180, 100, 80,
                20, .8f, .8f,
                new float[12],
                new float[147]
        );
        return new FruitTemplateMatcher.DetectedFruit(
                raw, type, .10, .85, uncovered
        );
    }

    private static FruitTemplateMatcher.State state(
            java.util.List<FruitTemplateMatcher.DetectedFruit> board,
            java.util.List<FruitTemplateMatcher.DetectedFruit> trayBottomToTop
    ) {
        return new FruitTemplateMatcher.State(
                1080, 2400, board, trayBottomToTop
        );
    }

    @Test
    public void emptyTrayChoosesMostNumerousUncoveredType() {
        FruitTemplateMatcher.State s = state(
                Arrays.asList(
                        d("A", 100, 500, true),
                        d("B", 300, 600, true),
                        d("B", 500, 700, true)
                ),
                Collections.emptyList()
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(FruitRuleDecisionEngine.Kind.CLICK_FRUIT, decision.kind);
        assertEquals("B", decision.target.type);
    }

    @Test
    public void oneTrayFruitPrioritizesItsTopType() {
        FruitTemplateMatcher.State s = state(
                Arrays.asList(
                        d("A", 100, 500, true),
                        d("B", 300, 600, true),
                        d("B", 500, 700, true)
                ),
                Collections.singletonList(d("A", 540, 1900, true))
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(FruitRuleDecisionEngine.Kind.CLICK_FRUIT, decision.kind);
        assertEquals("A", decision.target.type);
    }

    @Test
    public void oneTrayFruitCanStageNewTypeWhenTopMatchMissing() {
        FruitTemplateMatcher.State s = state(
                Arrays.asList(
                        d("B", 300, 600, true),
                        d("B", 500, 700, true),
                        d("C", 700, 800, true)
                ),
                Collections.singletonList(d("A", 540, 1900, true))
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(FruitRuleDecisionEngine.Kind.CLICK_FRUIT, decision.kind);
        assertEquals("B", decision.target.type);
    }

    @Test
    public void twoDifferentTrayFruitsCanOnlyMatchCurrentTop() {
        // bottom A, top B
        FruitTemplateMatcher.State s = state(
                Arrays.asList(
                        d("A", 100, 500, true),
                        d("B", 300, 600, true),
                        d("C", 500, 700, true)
                ),
                Arrays.asList(
                        d("A", 500, 1900, true),
                        d("B", 540, 1800, true)
                )
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(FruitRuleDecisionEngine.Kind.CLICK_FRUIT, decision.kind);
        assertEquals("B", decision.target.type);
    }

    @Test
    public void twoDifferentTrayFruitsRestartWhenOnlyLowerTypeExists() {
        // bottom A, top B; exposed A does NOT help because A is not the top.
        FruitTemplateMatcher.State s = state(
                Arrays.asList(
                        d("A", 100, 500, true),
                        d("C", 500, 700, true)
                ),
                Arrays.asList(
                        d("A", 500, 1900, true),
                        d("B", 540, 1800, true)
                )
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(
                FruitRuleDecisionEngine.Kind.RESTART_DEADLOCK,
                decision.kind
        );
    }

    @Test
    public void topAdjacentPairWaitsForAutoElimination() {
        // bottom A, then B, top B => only the top B,B pair can auto-clear.
        FruitTemplateMatcher.State s = state(
                Collections.singletonList(d("C", 300, 600, true)),
                Arrays.asList(
                        d("A", 460, 1900, true),
                        d("B", 540, 1800, true),
                        d("B", 620, 1700, true)
                )
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(
                FruitRuleDecisionEngine.Kind.WAIT_TRANSIENT,
                decision.kind
        );
    }

    @Test
    public void separatedSameTypeDoesNotAutoEliminate() {
        // bottom A, middle B, top A: two As are separated by B.
        FruitTemplateMatcher.State s = state(
                Collections.singletonList(d("A", 300, 600, true)),
                Arrays.asList(
                        d("A", 460, 1900, true),
                        d("B", 540, 1800, true),
                        d("A", 620, 1700, true)
                )
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(FruitRuleDecisionEngine.Kind.CLICK_FRUIT, decision.kind);
        assertNotNull(decision.target);
        assertEquals("A", decision.target.type);
    }

    @Test
    public void fullStackOnlyCurrentTopTypeIsSafe() {
        // bottom A, B, top C. Exposed A/B/D must be ignored; only C is safe.
        FruitTemplateMatcher.State s = state(
                Arrays.asList(
                        d("A", 100, 500, true),
                        d("B", 300, 600, true),
                        d("C", 500, 700, true),
                        d("D", 700, 800, true)
                ),
                Arrays.asList(
                        d("A", 460, 1900, true),
                        d("B", 540, 1800, true),
                        d("C", 620, 1700, true)
                )
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(FruitRuleDecisionEngine.Kind.CLICK_FRUIT, decision.kind);
        assertEquals("C", decision.target.type);
    }

    @Test
    public void fullStackRestartsWhenCurrentTopHasNoExposedMatch() {
        FruitTemplateMatcher.State s = state(
                Arrays.asList(
                        d("A", 100, 500, true),
                        d("B", 300, 600, true),
                        d("C", 500, 700, false),
                        d("D", 700, 800, true)
                ),
                Arrays.asList(
                        d("A", 460, 1900, true),
                        d("B", 540, 1800, true),
                        d("C", 620, 1700, true)
                )
        );

        FruitRuleDecisionEngine.Decision decision =
                FruitRuleDecisionEngine.decide(s, new HashSet<>());

        assertEquals(
                FruitRuleDecisionEngine.Kind.RESTART_DEADLOCK,
                decision.kind
        );
    }
}
