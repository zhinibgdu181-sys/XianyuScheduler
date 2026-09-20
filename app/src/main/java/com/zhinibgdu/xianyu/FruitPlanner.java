package com.zhinibgdu.xianyu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic state search for the currently visible fruit board.
 *
 * The planner never decides from one historical coordinate. Every search node
 * contains a complete copy of the observed fruit set. Search depth is deliberately
 * small because the executor re-observes after every successful tap.
 */
final class FruitPlanner {
    private static final double PAIR_MAX_DISTANCE = 0.29;
    private static final int MAX_SEARCH_DEPTH = 6;

    private FruitPlanner() {}

    static Plan plan(FruitBoardState state) {
        if (state == null || state.boardFruits.isEmpty()) return Plan.empty();

        List<Move> candidates = new ArrayList<>();
        for (int i = 0; i < state.boardFruits.size(); i++) {
            FruitBoardState.Fruit a = state.boardFruits.get(i);
            double accessA = clickability(state, a);
            for (int j = i + 1; j < state.boardFruits.size(); j++) {
                FruitBoardState.Fruit b = state.boardFruits.get(j);
                double similarity = a.similarityDistance(b);
                if (similarity > PAIR_MAX_DISTANCE) continue;
                double accessB = clickability(state, b);

                boolean trayMatch = matchesTray(state, a) || matchesTray(state, b);
                double pairScore = similarityScore(similarity)
                        + 0.22 * (accessA + accessB)
                        + (trayMatch ? 0.16 : 0.0);

                // If the tray is almost full, refuse non-pair moves.
                if (state.trayCount() >= 2 && !trayMatch) continue;

                candidates.add(new Move(i, j, pairScore));
            }
        }

        if (!candidates.isEmpty()) {
            SearchResult best = searchPairs(state, candidates, MAX_SEARCH_DEPTH);
            if (best != null && !best.moves.isEmpty()) {
                Move first = best.moves.get(0);
                List<Click> clicks = new ArrayList<>();
                clicks.add(new Click(
                        state.boardFruits.get(first.a).centerX,
                        state.boardFruits.get(first.a).centerY,
                        "PAIR_FIRST"
                ));
                clicks.add(new Click(
                        state.boardFruits.get(first.b).centerX,
                        state.boardFruits.get(first.b).centerY,
                        "PAIR_SECOND"
                ));
                return new Plan(clicks, best.score, "搜索到" + best.moves.size() + "组安全配对");
            }
        }

        // No immediate pair: choose an accessible fruit with the largest estimated
        // unlock gain. This is a single exploratory move; the next frame is authoritative.
        if (state.trayCount() < 3) {
            FruitBoardState.Fruit best = null;
            double bestScore = -Double.MAX_VALUE;
            for (FruitBoardState.Fruit fruit : state.boardFruits) {
                double access = clickability(state, fruit);
                if (access < 0.25) continue;

                int overlap = overlapCount(state, fruit);
                int future = futurePairCount(state, fruit);
                double score = 0.56 * access + 0.30 * Math.min(1.0, overlap / 3.0)
                        + 0.14 * Math.min(1.0, future / 2.0);

                // A fruit already represented in the tray gets priority because the
                // next click can immediately clear a tray slot.
                if (matchesTray(state, fruit)) score += 0.25;

                if (score > bestScore) {
                    bestScore = score;
                    best = fruit;
                }
            }
            if (best != null) {
                List<Click> clicks = new ArrayList<>();
                clicks.add(new Click(best.centerX, best.centerY, "UNLOCK"));
                return new Plan(clicks, bestScore, "无即时配对，选择最高解锁增益对象");
            }
        }

        return Plan.empty();
    }

    private static SearchResult searchPairs(
            FruitBoardState root,
            List<Move> rootMoves,
            int maxDepth
    ) {
        SearchResult best = new SearchResult(0.0, new ArrayList<>());
        searchRecursive(root, rootMoves, 0, maxDepth, 0.0,
                new ArrayList<>(), best);
        return best.moves.isEmpty() ? null : best;
    }

    private static void searchRecursive(
            FruitBoardState state,
            List<Move> moves,
            int depth,
            int maxDepth,
            double score,
            List<Move> path,
            SearchResult best
    ) {
        if (score > best.score && !path.isEmpty()) {
            best.score = score;
            best.moves = new ArrayList<>(path);
        }
        if (depth >= maxDepth) return;

        Set<Integer> usedPairs = new HashSet<>();
        for (Move move : moves) {
            int key = move.a * 10000 + move.b;
            if (!usedPairs.add(key)) continue;

            List<FruitBoardState.Fruit> nextFruits = new ArrayList<>();
            for (int i = 0; i < state.boardFruits.size(); i++) {
                if (i != move.a && i != move.b) {
                    nextFruits.add(state.boardFruits.get(i));
                }
            }
            FruitBoardState next = new FruitBoardState(
                    state.width, state.height, nextFruits, state.trayFruits
            );

            List<Move> nextMoves = generateMoves(next);
            path.add(move);
            double nextScore = score + move.score + 0.14 * Math.max(0, moves.size() - 1);
            searchRecursive(next, nextMoves, depth + 1, maxDepth, nextScore, path, best);
            path.remove(path.size() - 1);
        }
    }

    private static List<Move> generateMoves(FruitBoardState state) {
        List<Move> result = new ArrayList<>();
        for (int i = 0; i < state.boardFruits.size(); i++) {
            for (int j = i + 1; j < state.boardFruits.size(); j++) {
                FruitBoardState.Fruit a = state.boardFruits.get(i);
                FruitBoardState.Fruit b = state.boardFruits.get(j);
                double similarity = a.similarityDistance(b);
                if (similarity > PAIR_MAX_DISTANCE) continue;
                if (state.trayCount() >= 2 && !matchesTray(state, a) && !matchesTray(state, b)) continue;
                double score = similarityScore(similarity)
                        + 0.22 * (clickability(state, a) + clickability(state, b));
                result.add(new Move(i, j, score));
            }
        }
        return result;
    }

    private static boolean matchesTray(FruitBoardState state, FruitBoardState.Fruit fruit) {
        for (FruitBoardState.Fruit tray : state.trayFruits) {
            if (fruit.similarityDistance(tray) < PAIR_MAX_DISTANCE) return true;
        }
        return false;
    }

    private static int futurePairCount(FruitBoardState state, FruitBoardState.Fruit target) {
        int count = 0;
        for (FruitBoardState.Fruit other : state.boardFruits) {
            if (other == target) continue;
            if (target.similarityDistance(other) < PAIR_MAX_DISTANCE) count++;
        }
        return count;
    }

    private static int overlapCount(FruitBoardState state, FruitBoardState.Fruit target) {
        int count = 0;
        for (FruitBoardState.Fruit other : state.boardFruits) {
            if (other == target) continue;
            if (contains(other, target.centerX, target.centerY)) count++;
        }
        return count;
    }

    private static double clickability(FruitBoardState state, FruitBoardState.Fruit target) {
        int blockers = overlapCount(state, target);
        double centerBias = 1.0 - Math.min(1.0, Math.abs(
                target.centerX - state.width / 2.0
        ) / Math.max(1.0, state.width / 2.0));
        double topBias = 1.0 - Math.min(1.0, Math.max(0,
                target.centerY - state.height * 0.22
        ) / Math.max(1.0, state.height * 0.62));

        return Math.max(0.0,
                0.58
                        + 0.18 * centerBias
                        + 0.24 * topBias
                        - Math.min(0.65, blockers * 0.28)
        );
    }

    private static boolean contains(FruitBoardState.Fruit fruit, int x, int y) {
        return x >= fruit.left && x <= fruit.right && y >= fruit.top && y <= fruit.bottom;
    }

    private static double similarityScore(double distance) {
        return Math.max(0.0, 1.0 - distance / PAIR_MAX_DISTANCE);
    }

    static final class Plan {
        final List<Click> clicks;
        final double score;
        final String reason;

        Plan(List<Click> clicks, double score, String reason) {
            this.clicks = clicks == null ? new ArrayList<>() : new ArrayList<>(clicks);
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
        final double score;

        Move(int a, int b, double score) {
            this.a = a;
            this.b = b;
            this.score = score;
        }
    }

    private static final class SearchResult {
        double score;
        List<Move> moves;

        SearchResult(double score, List<Move> moves) {
            this.score = score;
            this.moves = moves;
        }
    }
}
