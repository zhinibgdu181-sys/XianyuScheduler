package com.zhinibgdu.xianyu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-route fruit planner.
 *
 * The old planner searched only a few pairs and returned the first pair.
 * This version searches a bounded route from the complete observed state,
 * deduplicates equivalent states, and returns the whole executable click route.
 *
 * Execution is still verified by FruitGameSolver: a route is a prediction, not
 * permission to blindly click stale coordinates.
 */
final class FruitPlanner {
    private static final double PAIR_MAX_DISTANCE = 0.20;
    private static final double PAIR_AMBIGUITY_MARGIN = 0.035;
    private static final int MAX_SEARCH_DEPTH = 14;
    private static final long SEARCH_BUDGET_MS = 220L;

    private FruitPlanner() {}

    static Plan plan(FruitBoardState state) {
        if (state == null || state.boardFruits.isEmpty()) return Plan.empty();

        long deadline = System.nanoTime() + SEARCH_BUDGET_MS * 1_000_000L;
        List<Move> rootMoves = generateMoves(state);

        if (!rootMoves.isEmpty()) {
            SearchResult best = new SearchResult();
            search(state, rootMoves, 0, 0.0, new ArrayList<>(),
                    new HashSet<>(), best, deadline);

            if (!best.path.isEmpty()) {
                // Never replay a long route from a vision model that has not yet
                // been validated on the live screen. Execute exactly one pair,
                // verify the state transition, then rebuild the state.
                Move move = best.path.get(0);
                List<Click> clicks = new ArrayList<>(2);
                clicks.add(new Click(move.ax, move.ay, "PAIR_FIRST"));
                clicks.add(new Click(move.bx, move.by, "PAIR_SECOND"));
                return new Plan(
                        clicks,
                        best.score,
                        "确认一组高置信度配对后立即验证"
                );
            }
        }

        // No pair is currently visible. Select one safe unlock move and force a
        // fresh observation after it; never guess a long route through unknown state.
        if (state.trayCount() < 3) {
            FruitBoardState.Fruit bestFruit = null;
            double bestScore = -Double.MAX_VALUE;
            for (FruitBoardState.Fruit fruit : state.boardFruits) {
                double access = clickability(state, fruit);
                if (access < 0.25) continue;

                int overlap = overlapCount(state, fruit);
                int future = futurePairCount(state, fruit);
                double score = 0.56 * access
                        + 0.30 * Math.min(1.0, overlap / 3.0)
                        + 0.14 * Math.min(1.0, future / 2.0);
                if (matchesTray(state, fruit)) score += 0.25;

                if (score > bestScore) {
                    bestScore = score;
                    bestFruit = fruit;
                }
            }

            if (bestFruit != null) {
                List<Click> clicks = new ArrayList<>();
                clicks.add(new Click(bestFruit.centerX, bestFruit.centerY, "UNLOCK"));
                return new Plan(clicks, bestScore, "无即时配对，执行一次解锁后重识别");
            }
        }

        return Plan.empty();
    }

    private static void search(
            FruitBoardState state,
            List<Move> moves,
            int depth,
            double score,
            List<Move> path,
            Set<String> visited,
            SearchResult best,
            long deadline
    ) {
        if (System.nanoTime() >= deadline) return;

        String key = stateKey(state);
        if (!visited.add(key)) return;

        if (score > best.score || path.size() > best.path.size()) {
            best.score = score;
            best.path = new ArrayList<>(path);
            best.solved = state.boardFruits.isEmpty();
        }

        if (state.boardFruits.isEmpty()) {
            best.solved = true;
            return;
        }
        if (depth >= MAX_SEARCH_DEPTH) return;

        // Try the most promising moves first. This usually finds a useful long
        // route quickly, while the visited set prevents repeated state expansion.
        moves.sort((a, b) -> Double.compare(b.score, a.score));

        for (Move move : moves) {
            if (System.nanoTime() >= deadline) return;

            List<FruitBoardState.Fruit> nextFruits = new ArrayList<>();
            for (int i = 0; i < state.boardFruits.size(); i++) {
                if (i != move.a && i != move.b) {
                    nextFruits.add(state.boardFruits.get(i));
                }
            }

            FruitBoardState next = new FruitBoardState(
                    state.width, state.height, nextFruits, state.trayFruits
            );

            path.add(move);
            double nextScore = score
                    + 2.0
                    + move.score
                    + 0.10 * Math.max(0, moves.size() - 1);

            search(
                    next,
                    generateMoves(next),
                    depth + 1,
                    nextScore,
                    path,
                    visited,
                    best,
                    deadline
            );
            path.remove(path.size() - 1);

            if (best.solved) return;
        }
    }

    private static List<Move> generateMoves(FruitBoardState state) {
        List<Move> result = new ArrayList<>();
        for (int i = 0; i < state.boardFruits.size(); i++) {
            FruitBoardState.Fruit a = state.boardFruits.get(i);
            double accessA = clickability(state, a);

            for (int j = i + 1; j < state.boardFruits.size(); j++) {
                FruitBoardState.Fruit b = state.boardFruits.get(j);
                double similarity = a.similarityDistance(b);
                if (similarity > PAIR_MAX_DISTANCE) continue;

                // Trust a pair only when the two fruits are each other's clear
                // nearest neighbour. This rejects broad local-patch colour
                // matches that can point at the wrong fruit.
                double aBest = Double.MAX_VALUE;
                double aSecond = Double.MAX_VALUE;
                int aBestIndex = -1;
                double bBest = Double.MAX_VALUE;
                double bSecond = Double.MAX_VALUE;
                int bBestIndex = -1;

                for (int k = 0; k < state.boardFruits.size(); k++) {
                    if (k == i) continue;
                    double d = a.similarityDistance(state.boardFruits.get(k));
                    if (d < aBest) {
                        aSecond = aBest;
                        aBest = d;
                        aBestIndex = k;
                    } else if (d < aSecond) {
                        aSecond = d;
                    }
                }
                for (int k = 0; k < state.boardFruits.size(); k++) {
                    if (k == j) continue;
                    double d = b.similarityDistance(state.boardFruits.get(k));
                    if (d < bBest) {
                        bSecond = bBest;
                        bBest = d;
                        bBestIndex = k;
                    } else if (d < bSecond) {
                        bSecond = d;
                    }
                }

                if (aBestIndex != j || bBestIndex != i) continue;
                if (aSecond - aBest < PAIR_AMBIGUITY_MARGIN) continue;
                if (bSecond - bBest < PAIR_AMBIGUITY_MARGIN) continue;

                boolean trayMatch = matchesTray(state, a) || matchesTray(state, b);
                if (state.trayCount() >= 2 && !trayMatch) continue;

                double score = similarityScore(similarity)
                        + 0.22 * (accessA + clickability(state, b))
                        + (trayMatch ? 0.16 : 0.0)
                        + 0.35 * Math.min(1.0,
                        (aSecond - aBest + bSecond - bBest) / 0.20);

                result.add(new Move(
                        i, j,
                        a.centerX, a.centerY,
                        b.centerX, b.centerY,
                        score
                ));
            }
        }
        return result;
    }

    static boolean matchesClickTarget(
            FruitBoardState state,
            Click click
    ) {
        if (state == null || click == null) return false;

        for (FruitBoardState.Fruit fruit : state.boardFruits) {
            double dx = click.x - fruit.centerX;
            double dy = click.y - fruit.centerY;
            double radius = Math.max(
                    24.0,
                    Math.min(fruit.width(), fruit.height()) * 0.62
            );
            if (dx * dx + dy * dy <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static String stateKey(FruitBoardState state) {
        StringBuilder sb = new StringBuilder(32 + state.boardFruits.size() * 20);
        sb.append(state.boardFruits.size()).append('|');
        for (FruitBoardState.Fruit fruit : state.boardFruits) {
            sb.append(fruit.centerX / 12).append(',')
                    .append(fruit.centerY / 12).append(',')
                    .append(Math.round(fruit.meanR / 12.0f)).append(',')
                    .append(Math.round(fruit.meanG / 12.0f)).append(',')
                    .append(Math.round(fruit.meanB / 12.0f)).append(';');
        }
        sb.append('|').append(state.trayCount());
        return sb.toString();
    }

    private static boolean matchesTray(
            FruitBoardState state,
            FruitBoardState.Fruit fruit
    ) {
        for (FruitBoardState.Fruit tray : state.trayFruits) {
            if (fruit.similarityDistance(tray) < PAIR_MAX_DISTANCE) return true;
        }
        return false;
    }

    private static int futurePairCount(
            FruitBoardState state,
            FruitBoardState.Fruit target
    ) {
        int count = 0;
        for (FruitBoardState.Fruit other : state.boardFruits) {
            if (other == target) continue;
            if (target.similarityDistance(other) < PAIR_MAX_DISTANCE) count++;
        }
        return count;
    }

    private static int overlapCount(
            FruitBoardState state,
            FruitBoardState.Fruit target
    ) {
        int count = 0;
        for (FruitBoardState.Fruit other : state.boardFruits) {
            if (other == target) continue;
            if (contains(other, target.centerX, target.centerY)) count++;
        }
        return count;
    }

    private static double clickability(
            FruitBoardState state,
            FruitBoardState.Fruit target
    ) {
        int blockers = overlapCount(state, target);
        double centerBias = 1.0 - Math.min(
                1.0,
                Math.abs(target.centerX - state.width / 2.0)
                        / Math.max(1.0, state.width / 2.0)
        );
        double topBias = 1.0 - Math.min(
                1.0,
                Math.max(0, target.centerY - state.height * 0.22)
                        / Math.max(1.0, state.height * 0.62)
        );

        return Math.max(
                0.0,
                0.58
                        + 0.18 * centerBias
                        + 0.24 * topBias
                        - Math.min(0.65, blockers * 0.28)
        );
    }

    private static boolean contains(
            FruitBoardState.Fruit fruit,
            int x,
            int y
    ) {
        return x >= fruit.left
                && x <= fruit.right
                && y >= fruit.top
                && y <= fruit.bottom;
    }

    private static double similarityScore(double distance) {
        return Math.max(0.0, 1.0 - distance / PAIR_MAX_DISTANCE);
    }

    static final class Plan {
        final List<Click> clicks;
        final double score;
        final String reason;

        Plan(List<Click> clicks, double score, String reason) {
            this.clicks = clicks == null
                    ? new ArrayList<>()
                    : new ArrayList<>(clicks);
            this.score = score;
            this.reason = reason == null ? "" : reason;
        }

        static Plan empty() {
            return new Plan(new ArrayList<>(), 0.0, "无安全动作");
        }

        boolean isEmpty() {
            return clicks.isEmpty();
        }
    }

    static final class Click {
        final int x;
        final int y;
        final String reason;

        Click(int x, int y, String reason) {
            this.x = x;
            this.y = y;
            this.reason = reason;
        }
    }

    private static final class Move {
        final int a;
        final int b;
        final int ax;
        final int ay;
        final int bx;
        final int by;
        final double score;

        Move(
                int a,
                int b,
                int ax,
                int ay,
                int bx,
                int by,
                double score
        ) {
            this.a = a;
            this.b = b;
            this.ax = ax;
            this.ay = ay;
            this.bx = bx;
            this.by = by;
            this.score = score;
        }
    }

    private static final class SearchResult {
        double score = Double.NEGATIVE_INFINITY;
        List<Move> path = new ArrayList<>();
        boolean solved;
    }
}
