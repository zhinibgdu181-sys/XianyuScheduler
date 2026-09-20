package com.zhinibgdu.xianyu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Template-based fruit identity layer.
 *
 * It consumes the 7x7 RGB patch already sampled by FruitVisionEngine and
 * compares it against real-game templates. Unknown or ambiguous matches are
 * explicitly rejected instead of being forced into the nearest fruit type.
 */
final class FruitTemplateMatcher {
    private static final double MAX_TEMPLATE_DISTANCE = 0.30;
    private static final double CLICKABLE_TEMPLATE_DISTANCE = 0.245;
    private static final double MIN_SECOND_BEST_MARGIN = 0.018;
    private static final double COVER_OVERLAP_RATIO = 0.30;

    private FruitTemplateMatcher() {}

    static State classify(FruitBoardState raw) {
        if (raw == null) return new State(0, 0, null, null);

        List<DetectedFruit> board = new ArrayList<>();
        for (FruitBoardState.Fruit fruit : raw.boardFruits) {
            board.add(match(fruit));
        }

        // A fruit is considered top-layer/clickable only when the template
        // itself is strong and no stronger overlapping fruit appears to cover it.
        for (int i = 0; i < board.size(); i++) {
            DetectedFruit target = board.get(i);
            if (!target.known() || target.templateDistance > CLICKABLE_TEMPLATE_DISTANCE) {
                target.uncovered = false;
                continue;
            }

            boolean covered = false;
            for (int j = 0; j < board.size(); j++) {
                if (i == j) continue;
                DetectedFruit other = board.get(j);
                if (!other.known()) continue;
                double overlap = overlapRatio(target.fruit, other.fruit);
                if (overlap < COVER_OVERLAP_RATIO) continue;

                // The visually cleaner overlapping sprite is more likely to be
                // the top layer. When both are equally clean, be conservative
                // and refuse the target instead of risking a blocked click.
                if (other.templateDistance <= target.templateDistance + 0.02) {
                    covered = true;
                    break;
                }
            }
            target.uncovered = !covered;
        }

        List<DetectedFruit> tray = new ArrayList<>();
        for (FruitBoardState.Fruit fruit : raw.trayFruits) {
            DetectedFruit detected = match(fruit);
            // Tray fruit does not need an "uncovered" test; it is state, not a
            // board click target.
            detected.uncovered = detected.known();
            tray.add(detected);
        }

        return new State(raw.width, raw.height, board, tray);
    }

    private static DetectedFruit match(FruitBoardState.Fruit fruit) {
        if (fruit == null || fruit.visualGrid.length != 7 * 7 * 3) {
            return new DetectedFruit(
                    fruit, FruitTemplateCatalog.UNKNOWN,
                    1.0, 0.0, false
            );
        }

        int bestIndex = -1;
        double best = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;

        for (int i = 0; i < FruitTemplateCatalog.GRIDS.length; i++) {
            float[] template = FruitTemplateCatalog.GRIDS[i];
            if (template.length != fruit.visualGrid.length) continue;
            double d = shiftedGridDistance(fruit.visualGrid, template);
            if (d < best) {
                second = best;
                best = d;
                bestIndex = i;
            } else if (d < second) {
                second = d;
            }
        }

        double margin = second == Double.MAX_VALUE ? 1.0 : second - best;
        boolean accepted = bestIndex >= 0
                && best <= MAX_TEMPLATE_DISTANCE
                && margin >= MIN_SECOND_BEST_MARGIN;

        String type = accepted
                ? FruitTemplateCatalog.IDS[bestIndex]
                : FruitTemplateCatalog.UNKNOWN;

        double confidence = accepted
                ? Math.max(0.0, 1.0 - best / MAX_TEMPLATE_DISTANCE)
                : 0.0;

        return new DetectedFruit(
                fruit, type, best, confidence, false
        );
    }

    private static double shiftedGridDistance(float[] a, float[] b) {
        final int n = 7;
        double best = Double.MAX_VALUE;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                double total = 0.0;
                int samples = 0;

                for (int y = 0; y < n; y++) {
                    int by = y + dy;
                    if (by < 0 || by >= n) continue;

                    for (int x = 0; x < n; x++) {
                        int bx = x + dx;
                        if (bx < 0 || bx >= n) continue;

                        int ia = (y * n + x) * 3;
                        int ib = (by * n + bx) * 3;
                        total += Math.abs(a[ia] - b[ib]);
                        total += Math.abs(a[ia + 1] - b[ib + 1]);
                        total += Math.abs(a[ia + 2] - b[ib + 2]);
                        samples += 3;
                    }
                }

                if (samples > 0) best = Math.min(best, total / samples);
            }
        }
        return Math.min(1.0, best);
    }

    private static double overlapRatio(
            FruitBoardState.Fruit a,
            FruitBoardState.Fruit b
    ) {
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return 0.0;

        double intersection = (right - left) * (double) (bottom - top);
        double area = Math.max(1.0, a.width() * (double) a.height());
        return Math.min(1.0, intersection / area);
    }

    static final class State {
        final int width;
        final int height;
        final List<DetectedFruit> board;
        final List<DetectedFruit> tray;

        State(
                int width,
                int height,
                List<DetectedFruit> board,
                List<DetectedFruit> tray
        ) {
            this.width = width;
            this.height = height;
            this.board = Collections.unmodifiableList(
                    board == null ? new ArrayList<>() : new ArrayList<>(board)
            );
            this.tray = Collections.unmodifiableList(
                    tray == null ? new ArrayList<>() : new ArrayList<>(tray)
            );
        }

        int stableTrayCount() {
            return Math.min(4, tray.size());
        }

        int knownTrayCount() {
            int count = 0;
            for (DetectedFruit fruit : tray) if (fruit.known()) count++;
            return count;
        }

        int unknownTrayCount() {
            return tray.size() - knownTrayCount();
        }

        int uncoveredKnownCount() {
            int count = 0;
            for (DetectedFruit fruit : board) {
                if (fruit.known() && fruit.uncovered) count++;
            }
            return count;
        }
    }

    static final class DetectedFruit {
        final FruitBoardState.Fruit fruit;
        final String type;
        final double templateDistance;
        final double confidence;
        boolean uncovered;

        DetectedFruit(
                FruitBoardState.Fruit fruit,
                String type,
                double templateDistance,
                double confidence,
                boolean uncovered
        ) {
            this.fruit = fruit;
            this.type = type == null ? FruitTemplateCatalog.UNKNOWN : type;
            this.templateDistance = templateDistance;
            this.confidence = confidence;
            this.uncovered = uncovered;
        }

        boolean known() {
            return !FruitTemplateCatalog.UNKNOWN.equals(type);
        }
    }
}
