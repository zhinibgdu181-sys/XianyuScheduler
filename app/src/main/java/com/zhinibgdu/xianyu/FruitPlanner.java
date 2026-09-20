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
    private static final double PAIR_MAX_DISTANCE = 0.24;
    private static final double PAIR_HIGH_CONFIDENCE_DISTANCE = 0.13;
    private static final double PAIR_AMBIGUITY_MARGIN = 0.015;
    private static final double PAIR_RESCUE_MAX_DISTANCE = 0.38;
    private static final int MAX_SEARCH_DEPTH = 14;
    private static final long SEARCH_BUDGET_MS = 220L;

    private FruitPlanner() {}

    static Plan plan(FruitBoardState state) {
        if (state == null || state.boardFruits.isEmpty()) return Plan.empty();

        long deadline = System.nanoTime() + SEARCH_BUDGET_MS * 1_000_000L;
        List<Move> rootMoves = generateMoves(state);
        boolean rescue = false;
        if (rootMoves.isEmpty()) {
            rootMoves = generateNearestNeighborRescueMoves(state);
            rescue = !rootMoves.isEmpty();
        }

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
                        (rescue ? "常规配对为0，启动最近邻救援=" : "候选配对=")
                                + rootMoves.size()
                                + "，执行一组后立即验证"
                );
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

                // Do not require reciprocal nearest-neighbour identity.
                // The live board contains repeated fruit types; when three or
                // more visually similar fruits exist, a legitimate pair can
                // naturally fail a strict "each other's nearest" test.
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

                boolean reciprocal = aBestIndex == j && bBestIndex == i;
                boolean oneWayNearest = aBestIndex == j || bBestIndex == i;
                boolean closeEnough = similarity <= PAIR_HIGH_CONFIDENCE_DISTANCE;
                if (!reciprocal && !oneWayNearest && !closeEnough) continue;

                double aMargin = aSecond == Double.MAX_VALUE
                        ? 0.0 : aSecond - aBest;
                double bMargin = bSecond == Double.MAX_VALUE
                        ? 0.0 : bSecond - bBest;

                // Only reject a highly ambiguous relaxed candidate when neither
                // fruit considers the other its nearest match. This prevents the
                // old 0-action deadlock while retaining a conservative first tier.
                if (!reciprocal
                        && !oneWayNearest
                        && aMargin < PAIR_AMBIGUITY_MARGIN
                        && bMargin < PAIR_AMBIGUITY_MARGIN) {
                    continue;
                }

                boolean trayMatch = matchesTray(state, a) || matchesTray(state, b);

                double score = similarityScore(similarity)
                        + 0.24 * (accessA + clickability(state, b))
                        + (trayMatch ? 0.10 : 0.0)
                        + (reciprocal ? 0.10 : 0.0)
                        + 0.20 * Math.min(1.0,
                        (aMargin + bMargin) / 0.12);

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

    /**
     * Last-resort identity bridge. The normal identity gate is intentionally
     * conservative, but a completely empty move list must not leave the game
     * idle forever. Choose only the nearest visual neighbour of each fruit;
     * prefer reciprocal neighbours and cap the distance. The solver verifies
     * the resulting transition immediately, so a bad hypothesis is discarded.
     */
    private static List<Move> generateNearestNeighborRescueMoves(FruitBoardState state) {
        List<Move> reciprocal = new ArrayList<>();
        List<Move> oneWay = new ArrayList<>();
        int n = state.boardFruits.size();
        if (n < 2) return reciprocal;

        int[] bestIndex = new int[n];
        double[] bestDistance = new double[n];
        java.util.Arrays.fill(bestIndex, -1);
        java.util.Arrays.fill(bestDistance, Double.MAX_VALUE);

        for (int i = 0; i < n; i++) {
            FruitBoardState.Fruit a = state.boardFruits.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double d = a.similarityDistance(state.boardFruits.get(j));
                if (d < bestDistance[i]) {
                    bestDistance[i] = d;
                    bestIndex[i] = j;
                }
            }
        }

        Set<String> added = new HashSet<>();
        for (int i = 0; i < n; i++) {
            int j = bestIndex[i];
            if (j < 0 || bestDistance[i] > PAIR_RESCUE_MAX_DISTANCE) continue;
            int a = Math.min(i, j);
            int b = Math.max(i, j);
            String key = a + ":" + b;
            if (!added.add(key)) continue;

            FruitBoardState.Fruit fa = state.boardFruits.get(a);
            FruitBoardState.Fruit fb = state.boardFruits.get(b);
            double d = fa.similarityDistance(fb);
            double access = 0.24 * (clickability(state, fa) + clickability(state, fb));
            double score = Math.max(0.0, 1.0 - d / PAIR_RESCUE_MAX_DISTANCE)
                    + access
                    + (bestIndex[j] == i ? 0.20 : 0.0);
            Move move = new Move(
                    a, b,
                    fa.centerX, fa.centerY,
                    fb.centerX, fb.centerY,
                    score
            );
            if (bestIndex[j] == i) reciprocal.add(move);
            else oneWay.add(move);
        }

        List<Move> result = reciprocal.isEmpty() ? oneWay : reciprocal;
        result.sort((a, b) -> Double.compare(b.score, a.score));
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
