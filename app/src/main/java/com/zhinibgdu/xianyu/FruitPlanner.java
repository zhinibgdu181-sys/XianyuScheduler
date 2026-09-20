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
    private static final double PAIR_MAX_DISTANCE = 0.26;
    private static final double PAIR_HIGH_CONFIDENCE_DISTANCE = 0.18;
    private static final double PAIR_AMBIGUITY_MARGIN = 0.018;
    private static final double PAIR_RESCUE_MAX_DISTANCE = 0.28;
    private static final double TRAY_MATCH_MAX_DISTANCE = 0.24;
    private static final double MIN_DROP_SCORE = 0.22;
    private static final int MAX_DOWNWARD_BLOCKERS = 1;
    private static final int MAX_SEARCH_DEPTH = 14;
    private static final long SEARCH_BUDGET_MS = 280L;

    private FruitPlanner() {}

    static String diagnosticSummary(FruitBoardState state) {
        if (state == null || state.boardFruits.isEmpty()) {
            return "objects=0 normalPairs=0 rescuePairs=0 minNearest=NA";
        }
        int normal = 0;
        int rescue = 0;
        double minNearest = Double.MAX_VALUE;
        double sumNearest = 0.0;
        int nearestSamples = 0;
        int safeDrop = 0;

        for (int i = 0; i < state.boardFruits.size(); i++) {
            FruitBoardState.Fruit a = state.boardFruits.get(i);
            if (clickability(state, a) >= MIN_DROP_SCORE
                    && downwardBlockers(state, a) <= MAX_DOWNWARD_BLOCKERS) {
                safeDrop++;
            }
            double nearest = Double.MAX_VALUE;
            for (int j = 0; j < state.boardFruits.size(); j++) {
                if (i == j) continue;
                double d = a.similarityDistance(state.boardFruits.get(j));
                nearest = Math.min(nearest, d);
                if (j > i) {
                    if (d <= PAIR_MAX_DISTANCE) normal++;
                    if (d <= PAIR_RESCUE_MAX_DISTANCE) rescue++;
                }
            }
            if (nearest < Double.MAX_VALUE) {
                minNearest = Math.min(minNearest, nearest);
                sumNearest += nearest;
                nearestSamples++;
            }
        }

        String minText = minNearest == Double.MAX_VALUE
                ? "NA" : String.format(java.util.Locale.US, "%.3f", minNearest);
        String avgText = nearestSamples == 0
                ? "NA" : String.format(java.util.Locale.US, "%.3f", sumNearest / nearestSamples);
        return "objects=" + state.boardFruits.size()
                + " tray=" + state.trayCount()
                + " normalPairs=" + normal
                + " rescuePairs=" + rescue
                + " safeDrop=" + safeDrop
                + " minNearest=" + minText
                + " avgNearest=" + avgText;
    }

    static int distinctTrayTypes(FruitBoardState state) {
        if (state == null || state.trayFruits.isEmpty()) return 0;
        List<FruitBoardState.Fruit> representatives = new ArrayList<>();
        for (FruitBoardState.Fruit fruit : state.trayFruits) {
            boolean matched = false;
            for (FruitBoardState.Fruit representative : representatives) {
                if (fruit.similarityDistance(representative) <= TRAY_MATCH_MAX_DISTANCE) {
                    matched = true;
                    break;
                }
            }
            if (!matched) representatives.add(fruit);
        }
        return representatives.size();
    }

    static boolean hasTrayMatch(FruitBoardState state) {
        return bestTrayMatchAggressive(state) != null;
    }

    static Plan planAggressiveTrayMatch(
            FruitBoardState state,
            Set<String> blockedRootActions
    ) {
        if (state == null || state.trayFruits.isEmpty()) return Plan.empty();

        Click click = bestTrayMatchAggressive(state);
        if (click == null) return Plan.empty();

        List<Click> clicks = new ArrayList<>(1);
        clicks.add(click);
        Plan plan = new Plan(
                clicks,
                100.0,
                "死局破局：强制优先补齐槽内同类"
        );

        Set<String> blocked = blockedRootActions == null
                ? java.util.Collections.emptySet()
                : blockedRootActions;
        return blocked.contains(plan.actionKey()) ? Plan.empty() : plan;
    }

    static Plan plan(FruitBoardState state) {
        return plan(state, java.util.Collections.emptySet());
    }

    static Plan plan(FruitBoardState state, Set<String> blockedRootActions) {
        if (state == null || state.boardFruits.isEmpty()) return Plan.empty();
        Set<String> blocked = blockedRootActions == null
                ? java.util.Collections.emptySet()
                : blockedRootActions;

        Click trayMatch = bestTrayMatch(state);
        if (trayMatch != null) {
            List<Click> clicks = new ArrayList<>(1);
            clicks.add(trayMatch);
            Plan trayPlan = new Plan(
                    clicks,
                    50.0,
                    "槽内已有水果，优先单击同类可落水果"
            );
            if (!blocked.contains(trayPlan.actionKey())) {
                return trayPlan;
            }
        }

        List<Move> rootMoves = generateMoves(state);
        rootMoves.removeIf(move -> blocked.contains(move.actionKey()));
        boolean rescue = false;
        if (rootMoves.isEmpty()) {
            rootMoves = generateNearestNeighborRescueMoves(state);
            rootMoves.removeIf(move -> blocked.contains(move.actionKey()));
            rescue = !rootMoves.isEmpty();
        }

        if (!rootMoves.isEmpty()) {
            /*
             * Human-like mode: do not simulate 14 future pair removals from a
             * physics model that cannot predict the live pile. The reference
             * recording clears the board by repeatedly choosing one obvious
             * same-fruit pair, tapping it, then looking again.
             */
            rootMoves.sort((a, b) -> Double.compare(b.score, a.score));
            Move move = rootMoves.get(0);
            List<Click> clicks = new ArrayList<>(2);
            clicks.add(new Click(move.ax, move.ay, "PAIR_FIRST"));
            clicks.add(new Click(move.bx, move.by, "PAIR_SECOND"));
            return new Plan(
                    clicks,
                    move.score,
                    (rescue ? "严格同类救援=" : "人类式当前帧配对=")
                            + rootMoves.size()
                            + "，点一对后立刻重看"
            );
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
        final int n = state.boardFruits.size();
        if (n < 2) return result;

        /*
         * Precompute pair distances and nearest/second-nearest neighbours once.
         * The previous implementation recalculated a fruit's neighbours inside
         * every candidate pair, making move generation roughly O(n^3). On a
         * 67-object frame this consumed ~13 seconds and the search deadline had
         * already expired before DFS started.
         */
        double[][] distance = new double[n][n];
        double[] best = new double[n];
        double[] second = new double[n];
        int[] bestIndex = new int[n];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        java.util.Arrays.fill(second, Double.MAX_VALUE);
        java.util.Arrays.fill(bestIndex, -1);

        for (int i = 0; i < n; i++) {
            FruitBoardState.Fruit a = state.boardFruits.get(i);
            for (int j = i + 1; j < n; j++) {
                double d = a.similarityDistance(state.boardFruits.get(j));
                distance[i][j] = d;
                distance[j][i] = d;

                if (d < best[i]) {
                    second[i] = best[i];
                    best[i] = d;
                    bestIndex[i] = j;
                } else if (d < second[i]) {
                    second[i] = d;
                }

                if (d < best[j]) {
                    second[j] = best[j];
                    best[j] = d;
                    bestIndex[j] = i;
                } else if (d < second[j]) {
                    second[j] = d;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            FruitBoardState.Fruit a = state.boardFruits.get(i);
            double accessA = clickability(state, a);
            int belowA = downwardBlockers(state, a);

            for (int j = i + 1; j < n; j++) {
                FruitBoardState.Fruit b = state.boardFruits.get(j);
                double accessB = clickability(state, b);
                int belowB = downwardBlockers(state, b);
                double similarity = distance[i][j];
                if (similarity > PAIR_MAX_DISTANCE) continue;

                boolean reciprocal = bestIndex[i] == j && bestIndex[j] == i;
                boolean oneWayNearest = bestIndex[i] == j || bestIndex[j] == i;
                boolean closeEnough = similarity <= PAIR_HIGH_CONFIDENCE_DISTANCE;

                // Identity stays strict; physics does not. A pair can be chosen
                // from anywhere on the board if it is visually convincing.
                boolean identityAccepted = closeEnough
                        || (reciprocal && similarity <= 0.24)
                        || (oneWayNearest && similarity <= 0.21);
                if (!identityAccepted) continue;

                double aMargin = second[i] == Double.MAX_VALUE
                        ? 0.0 : second[i] - best[i];
                double bMargin = second[j] == Double.MAX_VALUE
                        ? 0.0 : second[j] - best[j];

                if (!reciprocal
                        && !oneWayNearest
                        && !closeEnough
                        && aMargin < PAIR_AMBIGUITY_MARGIN
                        && bMargin < PAIR_AMBIGUITY_MARGIN) {
                    continue;
                }

                boolean trayMatch = matchesTray(state, a) || matchesTray(state, b);

                double lowerA = Math.min(1.0,
                        a.centerY / (double) Math.max(1, state.height * 0.60));
                double lowerB = Math.min(1.0,
                        b.centerY / (double) Math.max(1, state.height * 0.60));

                double score = 2.20 * similarityScore(similarity)
                        + 0.22 * (accessA + accessB)
                        + 0.18 * (lowerA + lowerB)
                        + (trayMatch ? 0.16 : 0.0)
                        + (reciprocal ? 0.16 : 0.0)
                        + 0.12 * Math.min(1.0, (aMargin + bMargin) / 0.12)
                        - 0.035 * Math.min(6, belowA + belowB);

                // Lower fruit first. If two matching fruits are vertically
                // related, clearing the lower one is more likely to open the
                // upper fruit's fall corridor before the second tap.
                boolean bFirst = b.centerY > a.centerY;
                result.add(new Move(
                        i, j,
                        bFirst ? b.centerX : a.centerX,
                        bFirst ? b.centerY : a.centerY,
                        bFirst ? a.centerX : b.centerX,
                        bFirst ? a.centerY : b.centerY,
                        score
                ));
            }
        }

        // Search only the strongest alternatives. Keeping thousands of visually
        // near-identical pairs adds branching cost without improving the first
        // verified move.
        result.sort((a, b) -> Double.compare(b.score, a.score));
        if (result.size() > 48) {
            return new ArrayList<>(result.subList(0, 48));
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
            double accessA = clickability(state, fa);
            double accessB = clickability(state, fb);
            int belowA = downwardBlockers(state, fa);
            int belowB = downwardBlockers(state, fb);
            double d = fa.similarityDistance(fb);
            double access = 0.24 * (accessA + accessB);
            double score = 2.0 * Math.max(0.0, 1.0 - d / PAIR_RESCUE_MAX_DISTANCE)
                    + access
                    + (bestIndex[j] == i ? 0.24 : 0.0)
                    - 0.025 * Math.min(6, belowA + belowB);
            boolean bFirst = fb.centerY > fa.centerY;
            Move move = new Move(
                    a, b,
                    bFirst ? fb.centerX : fa.centerX,
                    bFirst ? fb.centerY : fa.centerY,
                    bFirst ? fa.centerX : fb.centerX,
                    bFirst ? fa.centerY : fb.centerY,
                    score
            );
            if (bestIndex[j] == i) {
                reciprocal.add(move);
            } else if (d <= PAIR_HIGH_CONFIDENCE_DISTANCE) {
                // A one-way nearest neighbour is allowed only when identity is
                // already high-confidence. This prevents rescue mode from pairing
                // visually similar but different fruits (observed orange+tomato).
                oneWay.add(move);
            }
        }

        List<Move> result = new ArrayList<>(reciprocal);
        result.addAll(oneWay);
        result.sort((a, b) -> Double.compare(b.score, a.score));
        if (result.size() > 12) {
            return new ArrayList<>(result.subList(0, 12));
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
        int overlaps = overlapCount(state, target);
        int below = downwardBlockers(state, target);

        double centerBias = 1.0 - Math.min(
                1.0,
                Math.abs(target.centerX - state.width / 2.0)
                        / Math.max(1.0, state.width / 2.0)
        );

        // In this game a clicked fruit must fall toward the roofs/collector.
        // The previous "topBias" rewarded high fruits, which is the opposite of
        // the real physics. Fruits already near the lower board are safer.
        double lowerBias = Math.max(
                0.0,
                Math.min(
                        1.0,
                        (target.centerY - state.height * 0.10)
                                / Math.max(1.0, state.height * 0.50)
                )
        );

        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        0.34
                                + 0.36 * lowerBias
                                + 0.12 * centerBias
                                - Math.min(0.36, overlaps * 0.18)
                                - Math.min(0.54, below * 0.22)
                )
        );
    }

    private static int downwardBlockers(
            FruitBoardState state,
            FruitBoardState.Fruit target
    ) {
        int blockers = 0;
        double targetRadius = Math.max(18.0,
                Math.min(target.width(), target.height()) * 0.46);

        for (FruitBoardState.Fruit other : state.boardFruits) {
            if (other == target) continue;
            if (other.centerY <= target.centerY + targetRadius * 0.35) continue;

            double otherRadius = Math.max(18.0,
                    Math.min(other.width(), other.height()) * 0.46);
            double corridor = targetRadius + otherRadius * 0.72;
            if (Math.abs(other.centerX - target.centerX) > corridor) continue;

            // Objects far below still matter, but nearby objects are the ones
            // most likely to physically stop the falling fruit.
            double dy = other.centerY - target.centerY;
            if (dy <= state.height * 0.34) {
                blockers++;
            }
        }
        return blockers;
    }

    private static Click bestTrayMatch(FruitBoardState state) {
        if (state.trayFruits.isEmpty()) return null;

        FruitBoardState.Fruit bestFruit = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (FruitBoardState.Fruit board : state.boardFruits) {
            double drop = clickability(state, board);
            int below = downwardBlockers(state, board);
            // Matching an already occupied collector is strategically valuable.
            // Identity is stricter here, so allow a little more physical risk
            // instead of switching to an unrelated rescue pair.
            if (drop < 0.30 || below > 2) continue;

            double identity = Double.MAX_VALUE;
            for (FruitBoardState.Fruit tray : state.trayFruits) {
                identity = Math.min(identity, board.similarityDistance(tray));
            }
            if (identity > TRAY_MATCH_MAX_DISTANCE) continue;

            double score = 4.0 * Math.max(
                    0.0,
                    1.0 - identity / TRAY_MATCH_MAX_DISTANCE
            ) + 2.0 * drop - 0.30 * below;

            if (score > bestScore) {
                bestScore = score;
                bestFruit = board;
            }
        }

        return bestFruit == null
                ? null
                : new Click(bestFruit.centerX, bestFruit.centerY, "TRAY_MATCH");
    }

    private static Click bestTrayMatchAggressive(FruitBoardState state) {
        if (state == null || state.trayFruits.isEmpty()) return null;

        FruitBoardState.Fruit bestFruit = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (FruitBoardState.Fruit board : state.boardFruits) {
            double identity = Double.MAX_VALUE;
            for (FruitBoardState.Fruit tray : state.trayFruits) {
                identity = Math.min(identity, board.similarityDistance(tray));
            }
            if (identity > TRAY_MATCH_MAX_DISTANCE) continue;

            double drop = clickability(state, board);
            int below = downwardBlockers(state, board);
            double score = 8.0 * Math.max(
                    0.0,
                    1.0 - identity / TRAY_MATCH_MAX_DISTANCE
            ) + 0.35 * drop - 0.03 * Math.min(8, below);

            if (score > bestScore) {
                bestScore = score;
                bestFruit = board;
            }
        }

        return bestFruit == null
                ? null
                : new Click(
                        bestFruit.centerX,
                        bestFruit.centerY,
                        "DEADLOCK_TRAY_MATCH"
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

        String actionKey() {
            if (clicks.isEmpty()) return "";
            if (clicks.size() == 1) {
                Click c = clicks.get(0);
                return "S:" + quantize(c.x) + ":" + quantize(c.y) + ":" + c.reason;
            }
            Click a = clicks.get(0);
            Click b = clicks.get(1);
            return "P:"
                    + quantize(a.x) + ":" + quantize(a.y)
                    + ":" + quantize(b.x) + ":" + quantize(b.y);
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

        String actionKey() {
            return "P:"
                    + quantize(ax) + ":" + quantize(ay)
                    + ":" + quantize(bx) + ":" + quantize(by);
        }
    }

    private static int quantize(int value) {
        return Math.round(value / 24.0f);
    }

    private static final class SearchResult {
        double score = Double.NEGATIVE_INFINITY;
        List<Move> path = new ArrayList<>();
        boolean solved;
    }
}
