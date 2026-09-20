package com.zhinibgdu.xianyu;

import android.graphics.Bitmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Fast, dependency-free visual parser for the fruit board.
 *
 * It intentionally uses the complete current frame rather than OCR snippets or
 * previously remembered coordinates. The parser is conservative: small/noisy
 * components are discarded and only colorful, compact components inside the
 * game regions become fruit candidates.
 */
final class FruitVisionEngine {
    private static final int SAMPLE_STEP = 4;
    private static final float BOARD_TOP = 0.16f;
    private static final float BOARD_BOTTOM = 0.82f;
    private static final float TRAY_TOP = 0.82f;
    private static final float TRAY_BOTTOM = 0.97f;

    private FruitVisionEngine() {}

    static FruitBoardState observe(Bitmap source) {
        if (source == null || source.isRecycled() || source.getWidth() < 100 || source.getHeight() < 100) {
            return new FruitBoardState(0, 0, null, null);
        }

        List<FruitBoardState.Fruit> board = detectRegion(source,
                Math.round(source.getHeight() * BOARD_TOP),
                Math.round(source.getHeight() * BOARD_BOTTOM),
                7, 5000);

        List<FruitBoardState.Fruit> tray = detectRegion(source,
                Math.round(source.getHeight() * TRAY_TOP),
                Math.round(source.getHeight() * TRAY_BOTTOM),
                5, 1800);

        // A tray can contain decorative icons; keep at most three strongest components.
        if (tray.size() > 3) {
            tray.sort((a, b) -> Integer.compare(b.pixelArea, a.pixelArea));
            tray = new ArrayList<>(tray.subList(0, 3));
        }

        return new FruitBoardState(source.getWidth(), source.getHeight(), board, tray);
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
        final int gridW = Math.max(1, (width + SAMPLE_STEP - 1) / SAMPLE_STEP);
        final int gridH = Math.max(1, (bottom - top + SAMPLE_STEP - 1) / SAMPLE_STEP);
        final boolean[] mask = new boolean[gridW * gridH];

        for (int gy = 0; gy < gridH; gy++) {
            int y = Math.min(bottom - 1, top + gy * SAMPLE_STEP);
            for (int gx = 0; gx < gridW; gx++) {
                int x = Math.min(width - 1, gx * SAMPLE_STEP);
                int p = bitmap.getPixel(x, y);
                mask[gy * gridW + gx] = isFruitLikePixel(p);
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
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            long sumR = 0, sumG = 0, sumB = 0;
            float[] hueHist = new float[12];

            while (!queue.isEmpty()) {
                int idx = queue.removeFirst();
                int gx = idx % gridW;
                int gy = idx / gridW;
                int x = Math.min(width - 1, gx * SAMPLE_STEP);
                int y = Math.min(bottom - 1, top + gy * SAMPLE_STEP);
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

                float[] hsv = rgbToHsv(r, g, b);
                int bin = Math.max(0, Math.min(11, (int) (hsv[0] / 30.0f)));
                hueHist[bin] += 1.0f;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = gx + dx;
                        int ny = gy + dy;
                        if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue;
                        int n = ny * gridW + nx;
                        if (mask[n] && !seen[n]) {
                            seen[n] = true;
                            queue.addLast(n);
                        }
                    }
                }
            }

            if (count < minArea || count > maxArea) continue;

            int boxW = Math.max(1, maxX - minX + SAMPLE_STEP);
            int boxH = Math.max(1, maxY - minY + SAMPLE_STEP);
            float ratio = boxW / (float) boxH;
            if (ratio < 0.38f || ratio > 2.60f) continue;

            float fill = count / (float) Math.max(1, (boxW * boxH) / (SAMPLE_STEP * SAMPLE_STEP));
            if (fill < 0.08f) continue;

            for (int i = 0; i < hueHist.length; i++) {
                hueHist[i] /= Math.max(1.0f, count);
            }

            float meanR = sumR / (float) count;
            float meanG = sumG / (float) count;
            float meanB = sumB / (float) count;
            float[] hsv = rgbToHsv(Math.round(meanR), Math.round(meanG), Math.round(meanB));

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
            boolean merged = false;
            for (FruitBoardState.Fruit existing : result) {
                double distance = Math.hypot(
                        candidate.centerX - existing.centerX,
                        candidate.centerY - existing.centerY
                );
                double color = candidate.colorDistance(existing);
                if (distance < Math.min(candidate.width(), existing.width()) * 0.36
                        && color < 0.18) {
                    if (candidate.pixelArea > existing.pixelArea) {
                        result.remove(existing);
                        result.add(candidate);
                    }
                    merged = true;
                    break;
                }
            }
            if (!merged) result.add(candidate);
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

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255.0f;
        float gf = g / 255.0f;
        float bf = b / 255.0f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h;
        if (d == 0.0f) h = 0.0f;
        else if (max == rf) h = 60.0f * (((gf - bf) / d) % 6.0f);
        else if (max == gf) h = 60.0f * (((bf - rf) / d) + 2.0f);
        else h = 60.0f * (((rf - gf) / d) + 4.0f);
        if (h < 0) h += 360.0f;
        float s = max == 0.0f ? 0.0f : d / max;
        return new float[]{h, s, max};
    }
}
