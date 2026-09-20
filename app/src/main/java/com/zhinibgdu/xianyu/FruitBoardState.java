package com.zhinibgdu.xianyu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable snapshot of the visible fruit board. */
final class FruitBoardState {
    final int width;
    final int height;
    final List<Fruit> boardFruits;
    final List<Fruit> trayFruits;

    FruitBoardState(int width, int height, List<Fruit> boardFruits, List<Fruit> trayFruits) {
        this.width = width;
        this.height = height;
        List<Fruit> board = boardFruits == null ? Collections.emptyList() : new ArrayList<>(boardFruits);
        List<Fruit> tray = trayFruits == null ? Collections.emptyList() : new ArrayList<>(trayFruits);
        board.sort(Comparator.comparingInt((Fruit f) -> f.centerY).thenComparingInt(f -> f.centerX));
        tray.sort(Comparator.comparingInt((Fruit f) -> f.centerX));
        this.boardFruits = Collections.unmodifiableList(board);
        this.trayFruits = Collections.unmodifiableList(tray);
    }

    int trayCount() { return Math.min(3, trayFruits.size()); }

    boolean isEmpty() { return boardFruits.isEmpty() && trayFruits.isEmpty(); }

    int fingerprintChangesAgainst(FruitBoardState other) {
        if (other == null) return boardFruits.size();
        int matched = 0;
        boolean[] used = new boolean[other.boardFruits.size()];
        for (Fruit mine : boardFruits) {
            int best = -1;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < other.boardFruits.size(); i++) {
                if (used[i]) continue;
                double d = mine.similarityDistance(other.boardFruits.get(i));
                if (d < bestDistance) {
                    bestDistance = d;
                    best = i;
                }
            }
            if (best >= 0 && bestDistance < 0.42) {
                used[best] = true;
                matched++;
            }
        }
        return Math.max(boardFruits.size(), other.boardFruits.size()) - matched;
    }

    static final class Fruit {
        final int centerX, centerY, left, top, right, bottom, pixelArea;
        final float meanR, meanG, meanB, meanHue, saturation, value;
        final float[] hueHistogram;
        /**
         * Compact local appearance fingerprint sampled around the fruit center.
         * Each cell stores normalized RGB. This is intentionally small so it can
         * be compared on-device without OpenCV/native dependencies.
         */
        final float[] visualGrid;

        Fruit(int centerX, int centerY, int left, int top, int right, int bottom,
              int pixelArea, float meanR, float meanG, float meanB,
              float meanHue, float saturation, float value, float[] hueHistogram) {
            this(centerX, centerY, left, top, right, bottom, pixelArea,
                    meanR, meanG, meanB, meanHue, saturation, value,
                    hueHistogram, null);
        }

        Fruit(
                int centerX, int centerY, int left, int top, int right, int bottom,
                int pixelArea, float meanR, float meanG, float meanB,
                float meanHue, float saturation, float value,
                float[] hueHistogram, float[] visualGrid) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.pixelArea = pixelArea;
            this.meanR = meanR;
            this.meanG = meanG;
            this.meanB = meanB;
            this.meanHue = meanHue;
            this.saturation = saturation;
            this.value = value;
            this.hueHistogram = hueHistogram == null ? new float[12] : hueHistogram.clone();
            this.visualGrid = visualGrid == null ? new float[0] : visualGrid.clone();
        }

        int width() { return Math.max(1, right - left); }
        int height() { return Math.max(1, bottom - top); }
        float aspectRatio() { return width() / (float) height(); }

        double colorDistance(Fruit other) {
            if (other == null) return Double.MAX_VALUE;

            double rgb = (
                    Math.abs(meanR - other.meanR)
                            + Math.abs(meanG - other.meanG)
                            + Math.abs(meanB - other.meanB)
            ) / (255.0 * 3.0);
            double hue = circularHueDistance(meanHue, other.meanHue) / 180.0;
            double sv = (Math.abs(saturation - other.saturation)
                    + Math.abs(value - other.value)) / 2.0;

            double hist = 0.0;
            for (int i = 0; i < Math.min(hueHistogram.length, other.hueHistogram.length); i++) {
                hist += Math.abs(hueHistogram[i] - other.hueHistogram[i]);
            }
            hist *= 0.5;

            double areaRatio = Math.min(
                    1.0,
                    Math.abs(pixelArea - (double) other.pixelArea)
                            / Math.max(1.0, Math.max(pixelArea, other.pixelArea))
            );
            double aspect = Math.min(1.0,
                    Math.abs(aspectRatio() - other.aspectRatio()) * 0.50);

            double grid = visualGridDistance(other);

            // The local visual fingerprint is the strongest identity signal.
            // Mean colour/hue remain useful as a fallback for frames where the
            // patch could not be sampled.
            if (visualGrid.length > 0 && other.visualGrid.length == visualGrid.length) {
                return 0.40 * grid
                        + 0.18 * rgb
                        + 0.12 * hue
                        + 0.08 * sv
                        + 0.14 * hist
                        + 0.05 * areaRatio
                        + 0.03 * aspect;
            }

            return 0.25 * rgb
                    + 0.22 * hue
                    + 0.15 * sv
                    + 0.28 * hist
                    + 0.06 * areaRatio
                    + 0.04 * aspect;
        }

        private double visualGridDistance(Fruit other) {
            if (other == null || visualGrid.length == 0
                    || other.visualGrid.length != visualGrid.length) {
                return 1.0;
            }
            double total = 0.0;
            for (int i = 0; i < visualGrid.length; i++) {
                total += Math.abs(visualGrid[i] - other.visualGrid[i]);
            }
            return Math.min(1.0, total / visualGrid.length);
        }

        double similarityDistance(Fruit other) { return colorDistance(other); }

        static double circularHueDistance(double a, double b) {
            double d = Math.abs(a - b);
            return d > 180.0 ? 360.0 - d : d;
        }
    }
}
