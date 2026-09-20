package com.zhinibgdu.xianyu;

import android.graphics.Bitmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast, dependency-free visual parser for the fruit board.
 *
 * Performance rules:
 *  - sample at 5px instead of scanning every pixel;
 *  - do not allocate HSV arrays for every sampled pixel;
 *  - keep the full-resolution coordinates for ROOT taps.
 *
 * The parser remains conservative and produces a complete fresh state on every
 * accepted frame. It never reuses stale fruit coordinates.
 */
final class FruitVisionEngine {
    private static final int SAMPLE_STEP = 5;
    private static final float BOARD_TOP = 0.16f;
    private static final float BOARD_BOTTOM = 0.82f;
    private static final float TRAY_TOP = 0.82f;
    private static final float TRAY_BOTTOM = 0.97f;

    private FruitVisionEngine() {}

    static FruitBoardState observe(Bitmap source) {
        if (source == null
                || source.isRecycled()
                || source.getWidth() < 100
                || source.getHeight() < 100) {
            return new FruitBoardState(0, 0, null, null);
        }

        List<FruitBoardState.Fruit> board = detectRegion(
                source,
                Math.round(source.getHeight() * BOARD_TOP),
                Math.round(source.getHeight() * BOARD_BOTTOM),
                7,
                5000
        );

        List<FruitBoardState.Fruit> tray = detectRegion(
                source,
                Math.round(source.getHeight() * TRAY_TOP),
                Math.round(source.getHeight() * TRAY_BOTTOM),
                5,
                1800
        );

        if (tray.size() > 3) {
            tray.sort((a, b) -> Integer.compare(b.pixelArea, a.pixelArea));
            tray = new ArrayList<>(tray.subList(0, 3));
        }

        return new FruitBoardState(
                source.getWidth(),
                source.getHeight(),
                board,
                tray
        );
    }

    private static List<FruitBoardState.Fruit> detectRegion(
            Bitmap bitmap,
            int top,
            int bottom,
            int minArea,
            int maxArea
    ) {
        final int width = bitmap.getWidth();
        top = Math.max(0, Math.min(bitmap.getHeight() - 1, top));
        bottom = Math.max(top + 1, Math.min(bitmap.getHeight(), bottom));

        final int gridW = Math.max(
                1,
                (width + SAMPLE_STEP - 1) / SAMPLE_STEP
        );
        final int gridH = Math.max(
                1,
                (bottom - top + SAMPLE_STEP - 1) / SAMPLE_STEP
        );
        final boolean[] mask = new boolean[gridW * gridH];

        // Keep the hot path allocation-free: getPixel() is called only once for
        // each sampled point and HSV conversion is reduced to a hue-bin integer.
        for (int gy = 0; gy < gridH; gy++) {
            int y = Math.min(
                    bottom - 1,
                    top + gy * SAMPLE_STEP
            );
            int row = gy * gridW;
            for (int gx = 0; gx < gridW; gx++) {
                int x = Math.min(
                        width - 1,
                        gx * SAMPLE_STEP
                );
                mask[row + gx] = isFruitLikePixel(bitmap.getPixel(x, y));
            }
        }

        boolean[] seen = new boolean[mask.length];
        List<FruitBoardState.Fruit> result = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;

            seen[start] = true;
            queue.clear();
            queue.add(start);

            int count = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            long sumR = 0;
            long sumG = 0;
            long sumB = 0;
            float[] hueHist = new float[12];

            while (!queue.isEmpty()) {
                int idx = queue.removeFirst();
                int gx = idx % gridW;
                int gy = idx / gridW;
                int x = Math.min(
                        width - 1,
                        gx * SAMPLE_STEP
                );
                int y = Math.min(
                        bottom - 1,
                        top + gy * SAMPLE_STEP
                );
                int p = bitmap.getPixel(x, y);

                count++;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);

                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                sumR += r;
                sumG += g;
                sumB += b;

                int hueBin = hueBin(r, g, b);
                hueHist[hueBin] += 1.0f;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;

                        int nx = gx + dx;
                        int ny = gy + dy;
                        if (nx < 0
                                || nx >= gridW
                                || ny < 0
                                || ny >= gridH) {
                            continue;
                        }

                        int n = ny * gridW + nx;
                        if (mask[n] && !seen[n]) {
                            seen[n] = true;
                            queue.addLast(n);
                        }
                    }
                }
            }

            if (count < minArea || count > maxArea) continue;

            int boxW = Math.max(
                    1,
                    maxX - minX + SAMPLE_STEP
            );
            int boxH = Math.max(
                    1,
                    maxY - minY + SAMPLE_STEP
            );
            float ratio = boxW / (float) boxH;
            if (ratio < 0.38f || ratio > 2.60f) continue;

            float fill = count / (float) Math.max(
                    1,
                    (boxW * boxH)
                            / (SAMPLE_STEP * SAMPLE_STEP)
            );
            if (fill < 0.08f) continue;

            for (int i = 0; i < hueHist.length; i++) {
                hueHist[i] /= Math.max(1.0f, count);
            }

            float meanR = sumR / (float) count;
            float meanG = sumG / (float) count;
            float meanB = sumB / (float) count;
            float[] hsv = rgbToHsv(
                    Math.round(meanR),
                    Math.round(meanG),
                    Math.round(meanB)
            );

            result.add(new FruitBoardState.Fruit(
                    (minX + maxX) / 2,
                    (minY + maxY) / 2,
                    minX,
                    minY,
                    maxX + SAMPLE_STEP,
                    maxY + SAMPLE_STEP,
                    count * SAMPLE_STEP * SAMPLE_STEP,
                    meanR,
                    meanG,
                    meanB,
                    hsv[0],
                    hsv[1],
                    hsv[2],
                    hueHist
            ));
        }

        return mergeNearDuplicates(result);
    }

    private static List<FruitBoardState.Fruit> mergeNearDuplicates(
            List<FruitBoardState.Fruit> input
    ) {
        List<FruitBoardState.Fruit> result = new ArrayList<>();

        for (FruitBoardState.Fruit candidate : input) {
            int mergeIndex = -1;

            for (int i = 0; i < result.size(); i++) {
                FruitBoardState.Fruit existing = result.get(i);
                double distance = Math.hypot(
                        candidate.centerX - existing.centerX,
                        candidate.centerY - existing.centerY
                );
                double color = candidate.colorDistance(existing);

                if (distance
                        < Math.min(
                        candidate.width(),
                        existing.width()
                ) * 0.36
                        && color < 0.18) {
                    mergeIndex = i;
                    break;
                }
            }

            if (mergeIndex >= 0) {
                FruitBoardState.Fruit existing = result.get(mergeIndex);
                if (candidate.pixelArea > existing.pixelArea) {
                    result.set(mergeIndex, candidate);
                }
            } else {
                result.add(candidate);
            }
        }

        return result;
    }

    private static boolean isFruitLikePixel(int pixel) {
        int r = (pixel >> 16) & 0xff;
        int g = (pixel >> 8) & 0xff;
        int b = pixel & 0xff;

        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;

        if (max < 70 || delta < 34) return false;
        if (r > 228 && g > 228 && b > 228) return false;
        if (Math.abs(r - g) < 8 && Math.abs(g - b) < 8) return false;

        return true;
    }

    private static int hueBin(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;
        if (delta == 0) return 0;

        float hue;
        if (max == r) {
            hue = 60.0f * ((g - b) / (float) delta);
            if (hue < 0) hue += 360.0f;
        } else if (max == g) {
            hue = 60.0f * ((b - r) / (float) delta + 2.0f);
        } else {
            hue = 60.0f * ((r - g) / (float) delta + 4.0f);
        }

        if (hue < 0) hue += 360.0f;
        return Math.max(
                0,
                Math.min(11, (int) (hue / 30.0f))
        );
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255.0f;
        float gf = g / 255.0f;
        float bf = b / 255.0f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;

        float h;
        if (d == 0.0f) {
            h = 0.0f;
        } else if (max == rf) {
            h = 60.0f * (((gf - bf) / d) % 6.0f);
        } else if (max == gf) {
            h = 60.0f * (((bf - rf) / d) + 2.0f);
        } else {
            h = 60.0f * (((rf - gf) / d) + 4.0f);
        }

        if (h < 0) h += 360.0f;
        float s = max == 0.0f ? 0.0f : d / max;

        return new float[]{h, s, max};
    }
}
