package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Learns gesture STYLE only.
 *
 * Stored samples contain relative motion [dx, dy, tMs] for one isolated tap or
 * swipe. Absolute screen positions, page names, task order, click targets,
 * screenshots, account data and inter-action sequences are deliberately not
 * stored, so this store cannot reproduce a user's task workflow.
 */
final class HumanGestureStyleStore {
    // V4.50.1 deliberately starts a new store so old absolute-position samples
    // from the first 4.50.0 implementation are not reused.
    private static final String PREFS = "human_gesture_style_v451";
    private static final String KEY_SAMPLES = "samples";
    private static final int MAX_SAMPLES = 1200;
    private static final long TTL_MS = 60L * 24L * 60L * 60L * 1000L;
    private static final long MAX_BYTES = 2L * 1024L * 1024L;

    private HumanGestureStyleStore() {}

    static final class GestureTemplate {
        final boolean swipe;
        final int durationMs;
        final int width;
        final int height;
        // Relative coordinates. First point is normally 0,0.
        final List<int[]> points;

        GestureTemplate(boolean swipe, int durationMs, int width, int height, List<int[]> points) {
            this.swipe = swipe;
            this.durationMs = Math.max(1, durationMs);
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            this.points = points == null ? new ArrayList<>() : points;
        }

        int startX() { return points.isEmpty() ? 0 : points.get(0)[0]; }
        int startY() { return points.isEmpty() ? 0 : points.get(0)[1]; }
        int endX() { return points.isEmpty() ? 0 : points.get(points.size() - 1)[0]; }
        int endY() { return points.isEmpty() ? 0 : points.get(points.size() - 1)[1]; }
    }

    static synchronized void recordTap(
            Context context,
            int durationMs,
            int width,
            int height,
            List<int[]> trajectory
    ) {
        recordGesture(context, false, durationMs, width, height, trajectory);
    }

    static synchronized void recordSwipe(
            Context context,
            int durationMs,
            int width,
            int height,
            List<int[]> trajectory
    ) {
        recordGesture(context, true, durationMs, width, height, trajectory);
    }

    private static void recordGesture(
            Context context,
            boolean swipe,
            int durationMs,
            int width,
            int height,
            List<int[]> trajectory
    ) {
        if (context == null || width <= 0 || height <= 0) return;

        List<int[]> absolute = sanitizeAbsolutePoints(
                trajectory, width, height, durationMs);
        if ((!swipe && absolute.isEmpty()) || (swipe && absolute.size() < 2)) return;

        List<int[]> relative = toRelativePoints(
                absolute, width, height, durationMs);
        if ((!swipe && relative.isEmpty()) || (swipe && relative.size() < 2)) return;

        append(context,
                (swipe ? "S|" : "T|")
                        + System.currentTimeMillis()
                        + "|" + Math.max(1, durationMs)
                        + "|" + width
                        + "|" + height
                        + "|" + encodePoints(relative));
    }

    static synchronized int sampleCount(Context context) {
        return load(context).size();
    }

    static synchronized String summary(Context context) {
        List<String> rows = load(context);
        int taps = 0;
        int swipes = 0;
        ArrayList<Integer> tapDurations = new ArrayList<>();
        ArrayList<Integer> swipeDurations = new ArrayList<>();

        for (String row : rows) {
            if (row == null) continue;
            if (row.startsWith("T|")) {
                GestureTemplate t = parseGesture(row, false);
                if (t != null) {
                    taps++;
                    tapDurations.add(t.durationMs);
                }
            } else if (row.startsWith("S|")) {
                GestureTemplate t = parseGesture(row, true);
                if (t != null) {
                    swipes++;
                    swipeDurations.add(t.durationMs);
                }
            }
        }

        return "已学习 " + taps + " 次点击 · " + swipes + " 次滑动"
                + (taps > 0 ? " · 点击约" + median(tapDurations, 0) + "ms" : "")
                + (swipes > 0 ? " · 滑动约" + median(swipeDurations, 0) + "ms" : "")
                + " · 仅动作风格";
    }

    static synchronized GestureTemplate sampleTap(Context context) {
        ArrayList<GestureTemplate> candidates = new ArrayList<>();
        for (String row : load(context)) {
            if (row != null && row.startsWith("T|")) {
                GestureTemplate t = parseGesture(row, false);
                if (t != null && !t.points.isEmpty()) candidates.add(t);
            }
        }
        return chooseRecent(candidates);
    }

    static synchronized GestureTemplate sampleSwipe(
            Context context,
            int requestedDx,
            int requestedDy
    ) {
        ArrayList<GestureTemplate> all = new ArrayList<>();
        ArrayList<GestureTemplate> sameDirection = new ArrayList<>();

        boolean verticalRequest = Math.abs(requestedDy) >= Math.abs(requestedDx);
        int signX = Integer.compare(requestedDx, 0);
        int signY = Integer.compare(requestedDy, 0);

        for (String row : load(context)) {
            if (row == null || !row.startsWith("S|")) continue;
            GestureTemplate t = parseGesture(row, true);
            if (t == null || t.points.size() < 2) continue;
            all.add(t);

            int dx = t.endX() - t.startX();
            int dy = t.endY() - t.startY();
            boolean vertical = Math.abs(dy) >= Math.abs(dx);
            boolean directionOk = verticalRequest == vertical;
            if (verticalRequest) {
                directionOk &= signY == 0 || Integer.compare(dy, 0) == signY;
            } else {
                directionOk &= signX == 0 || Integer.compare(dx, 0) == signX;
            }
            if (directionOk) sameDirection.add(t);
        }

        return chooseRecent(sameDirection.isEmpty() ? all : sameDirection);
    }

    private static GestureTemplate chooseRecent(List<GestureTemplate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        int from = Math.max(0, candidates.size() - 50);
        int span = candidates.size() - from;
        long seed = System.nanoTime() ^ (System.currentTimeMillis() << 7);
        int index = from + (int) Math.floorMod(seed, span);
        return candidates.get(index);
    }

    private static GestureTemplate parseGesture(String row, boolean swipe) {
        try {
            String[] p = row.split("\\|", -1);
            if (p.length < 6) return null;
            if (swipe && !"S".equals(p[0])) return null;
            if (!swipe && !"T".equals(p[0])) return null;

            int duration = Integer.parseInt(p[2]);
            int width = Integer.parseInt(p[3]);
            int height = Integer.parseInt(p[4]);
            List<int[]> points = decodeRelativePoints(
                    p[5], width, height, duration);
            if (points.isEmpty()) return null;

            return new GestureTemplate(
                    swipe, duration, width, height, points);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void append(Context context, String row) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null || row == null || row.isEmpty()) return;

        ArrayList<String> rows = new ArrayList<>(load(context));
        rows.add(row);

        long now = System.currentTimeMillis();
        ArrayList<String> kept = new ArrayList<>();
        for (String item : rows) {
            long at = timestamp(item);
            if (at <= 0L || now - at <= TTL_MS) kept.add(item);
        }

        while (kept.size() > MAX_SAMPLES || estimateBytes(kept) > MAX_BYTES) {
            if (kept.isEmpty()) break;
            kept.remove(0);
        }

        save(context, kept);
    }

    private static List<String> load(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return new ArrayList<>();
        String raw = prefs.getString(KEY_SAMPLES, "");
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();

        String[] rows = raw.split("\\n");
        ArrayList<String> out = new ArrayList<>(rows.length);
        Collections.addAll(out, rows);
        return out;
    }

    private static void save(Context context, List<String> rows) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return;

        StringBuilder sb = new StringBuilder();
        for (String row : rows) {
            if (row == null || row.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(row.replace("\n", " ").replace("\r", " "));
        }
        prefs.edit().putString(KEY_SAMPLES, sb.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long timestamp(String row) {
        if (row == null) return 0L;
        String[] p = row.split("\\|", -1);
        if (p.length < 2) return 0L;
        try {
            return Long.parseLong(p[1]);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static List<int[]> sanitizeAbsolutePoints(
            List<int[]> source,
            int width,
            int height,
            int durationMs
    ) {
        ArrayList<int[]> out = new ArrayList<>();
        if (source == null) return out;

        int lastT = -1;
        for (int[] p : source) {
            if (p == null || p.length < 2) continue;

            int x = Math.max(0, Math.min(width - 1, p[0]));
            int y = Math.max(0, Math.min(height - 1, p[1]));
            int t = p.length >= 3 ? p[2] : 0;
            t = Math.max(0, Math.min(Math.max(1, durationMs), t));

            if (!out.isEmpty()) {
                int[] last = out.get(out.size() - 1);
                if (last[0] == x && last[1] == y
                        && Math.abs(last[2] - t) < 8) {
                    continue;
                }
            }

            if (t < lastT) t = lastT;
            out.add(new int[]{x, y, t});
            lastT = t;
            if (out.size() >= 64) break;
        }
        return out;
    }

    private static List<int[]> toRelativePoints(
            List<int[]> absolute,
            int width,
            int height,
            int durationMs
    ) {
        ArrayList<int[]> out = new ArrayList<>();
        if (absolute == null || absolute.isEmpty()) return out;

        int originX = absolute.get(0)[0];
        int originY = absolute.get(0)[1];
        int lastT = -1;

        for (int[] p : absolute) {
            if (p == null || p.length < 3) continue;
            int dx = Math.max(-width, Math.min(width, p[0] - originX));
            int dy = Math.max(-height, Math.min(height, p[1] - originY));
            int t = Math.max(0, Math.min(Math.max(1, durationMs), p[2]));
            if (t < lastT) t = lastT;
            out.add(new int[]{dx, dy, t});
            lastT = t;
        }
        return out;
    }

    private static String encodePoints(List<int[]> points) {
        StringBuilder sb = new StringBuilder(points.size() * 18);
        for (int[] p : points) {
            if (p == null || p.length < 3) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(p[0]).append(',').append(p[1]).append(',').append(p[2]);
        }
        return sb.toString();
    }

    private static List<int[]> decodeRelativePoints(
            String encoded,
            int width,
            int height,
            int duration
    ) {
        ArrayList<int[]> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;

        for (String token : encoded.split(";")) {
            String[] p = token.split(",");
            if (p.length < 3) continue;
            try {
                int dx = Math.max(-width, Math.min(width, Integer.parseInt(p[0])));
                int dy = Math.max(-height, Math.min(height, Integer.parseInt(p[1])));
                int t = Math.max(0, Math.min(duration, Integer.parseInt(p[2])));
                out.add(new int[]{dx, dy, t});
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int median(List<Integer> values, int fallback) {
        if (values == null || values.isEmpty()) return fallback;
        ArrayList<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static long estimateBytes(List<String> rows) {
        long total = 0L;
        for (String row : rows) {
            if (row != null) total += row.length() * 2L + 2L;
            if (total > MAX_BYTES) return total;
        }
        return total;
    }
}
