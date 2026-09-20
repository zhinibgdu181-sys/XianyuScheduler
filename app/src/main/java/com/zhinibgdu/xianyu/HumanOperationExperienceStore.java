package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * V4.67: compact, task-scoped memory of verified human touch habits.
 *
 * This store records only structured gesture statistics. It never stores screenshots,
 * recordings, or raw touch streams. It is an experience source only; callers must
 * perform their own safety validation before replaying any learned behavior.
 */
final class HumanOperationExperienceStore {

    static final String TYPE_TAP = "tap";
    static final String TYPE_SWIPE = "swipe";
    static final String TYPE_WAIT = "wait";

    private static final String PREFS = "xianyu_task_profiles_v48";
    private static final String KEY_PREFIX = "human_op_v467_";
    private static final String INDEX_KEY = KEY_PREFIX + "index";
    private static final int MAX_RECORDS = 6000;
    private static final long TTL_MS = 45L * 24L * 60L * 60L * 1000L;
    private static final long MAX_STORE_BYTES = 5L * 1024L * 1024L;

    private HumanOperationExperienceStore() {}

    static synchronized void recordTap(
            Context context,
            String task,
            String pageKey,
            int targetCx,
            int targetCy,
            int actualX,
            int actualY,
            int width,
            int height) {
        if (context == null || width <= 0 || height <= 0) return;
        int dx = actualX - targetCx;
        int dy = actualY - targetCy;
        String payload = buildTapPayload(targetCx, targetCy, actualX, actualY,
                dx, dy, width, height, task, pageKey);
        append(context, payload);
    }

    static synchronized void recordRawTap(
            Context context,
            String task,
            String pageKey,
            int actualX,
            int actualY,
            int width,
            int height) {
        recordTap(context, task, pageKey, -1, -1, actualX, actualY, width, height);
    }

    static synchronized void recordSwipe(
            Context context,
            String task,
            String pageKey,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs,
            float pathDistance,
            float angleDeg,
            float avgSpeed,
            int width,
            int height) {
        if (context == null || width <= 0 || height <= 0) return;
        String payload = buildSwipePayload(startX, startY, endX, endY,
                Math.max(0, durationMs), Math.max(0f, pathDistance),
                angleDeg, Math.max(0f, avgSpeed), width, height, task, pageKey);
        append(context, payload);
    }

    /** V4.72: 记录真人滑动曲线摘要与有限轨迹采样点。 */
    static synchronized void recordSwipe(
            Context context,
            String task,
            String pageKey,
            int startX,
            int startY,
            int endX,
            int endY,
            int durationMs,
            float pathDistance,
            float angleDeg,
            float avgSpeed,
            float curvatureRad,
            float maxDeviation,
            float pathRatio,
            List<int[]> trajectory,
            int width,
            int height) {
        if (context == null || width <= 0 || height <= 0) return;
        String payload = buildSwipePayload(startX, startY, endX, endY,
                Math.max(0, durationMs), Math.max(0f, pathDistance),
                angleDeg, Math.max(0f, avgSpeed), curvatureRad,
                Math.max(0f, maxDeviation), Math.max(1f, pathRatio),
                trajectory, width, height, task, pageKey);
        append(context, payload);
    }

    static synchronized void recordWait(
            Context context,
            String task,
            String pageKey,
            long waitMs) {
        if (context == null || waitMs < 0L) return;
        String payload = buildWaitPayload(waitMs, task, pageKey);
        append(context, payload);
    }

    static synchronized int recordCount(Context context) {
        List<String> records = load(context);
        return records == null ? 0 : records.size();
    }

    /**
     * V4.80: close the current teaching session. Only TASK_RESULT=SUCCESS
     * becomes replay-eligible. FAILURE and UNKNOWN remain audit data only.
     */
    static synchronized void finalizeCurrentSessionOutcome(Context context) {
        if (context == null) return;
        String session = TeachingOutcomeStore.currentSessionId();
        if (session == null || session.isEmpty()) return;
        String outcome = TeachingOutcomeStore.currentTaskResult();
        if (!TeachingOutcomeStore.SUCCESS.equals(outcome)
                && !TeachingOutcomeStore.FAILURE.equals(outcome)
                && !TeachingOutcomeStore.UNKNOWN.equals(outcome)) {
            outcome = TeachingOutcomeStore.UNKNOWN;
        }

        List<String> records = load(context);
        if (records == null || records.isEmpty()) return;
        String token = "|SESSION=" + safe(session) + "|OUTCOME=PENDING|";
        String replacement = "|SESSION=" + safe(session)
                + "|OUTCOME=" + safe(outcome) + "|";
        ArrayList<String> next = new ArrayList<>(records.size());
        for (String row : records) {
            if (row != null && row.contains(token)) {
                next.add(row.replace(token, replacement));
            } else {
                next.add(row);
            }
        }
        save(context, next);
    }

    /**
     * Replay consumers must reject legacy rows and every non-success outcome.
     */
    static boolean replayEligible(String record) {
        return record != null
                && record.contains("|SESSION=")
                && record.contains("|OUTCOME=SUCCESS|");
    }

    static synchronized void compact(Context context) {
        List<String> records = load(context);
        if (records == null || records.isEmpty()) return;
        long now = System.currentTimeMillis();
        ArrayList<String> kept = new ArrayList<>();
        for (String r : records) {
            long at = timestampOf(r);
            if (at > 0L && now - at <= TTL_MS) kept.add(r);
        }
        if (kept.size() > MAX_RECORDS) {
            kept = new ArrayList<>(kept.subList(kept.size() - MAX_RECORDS, kept.size()));
        }
        save(context, kept);
    }

    private static void append(Context context, String payload) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return;
        List<String> records = load(context);
        if (records == null) records = new ArrayList<>();
        String session = TeachingOutcomeStore.currentSessionId();
        String taskOutcome = TeachingOutcomeStore.currentTaskResult();
        // New samples are always PENDING until the session receives a verified
        // task result. This prevents an in-progress/old sample from becoming a
        // replay candidate merely because it contains a gesture.
        records.add(System.currentTimeMillis()
                + "|SESSION=" + safe(session)
                + "|OUTCOME=PENDING|TASK_RESULT=" + safe(taskOutcome)
                + "|" + payload);

        long now = System.currentTimeMillis();
        ArrayList<String> kept = new ArrayList<>();
        for (String r : records) {
            long at = timestampOf(r);
            if (at > 0L && now - at <= TTL_MS) kept.add(r);
        }

        while (kept.size() > MAX_RECORDS || estimateBytes(kept) > MAX_STORE_BYTES) {
            if (kept.isEmpty()) break;
            kept.remove(0);
        }
        save(context, kept);
    }

    private static List<String> load(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return null;
        String encoded = prefs.getString(INDEX_KEY, "");
        if (encoded == null || encoded.isEmpty()) return new ArrayList<>();
        String[] rows = encoded.split("\n");
        ArrayList<String> result = new ArrayList<>(rows.length);
        Collections.addAll(result, rows);
        return result;
    }

    private static void save(Context context, List<String> records) {
        SharedPreferences prefs = prefs(context);
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        for (String r : records) {
            if (r == null || r.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.replace("\n", " ").replace("\r", " "));
        }
        prefs.edit().putString(INDEX_KEY, sb.toString()).apply();
    }

    private static long timestampOf(String record) {
        if (record == null) return 0L;
        int p = record.indexOf('|');
        if (p <= 0) return 0L;
        try {
            return Long.parseLong(record.substring(0, p));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long estimateBytes(List<String> records) {
        long bytes = 0L;
        for (String r : records) {
            if (r != null) bytes += r.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes > MAX_STORE_BYTES) return bytes;
        }
        return bytes;
    }

    private static String buildTapPayload(
            int targetCx, int targetCy, int actualX, int actualY,
            int dx, int dy, int width, int height,
            String task, String pageKey) {
        return new StringBuilder(96)
                .append("v1|").append(TYPE_TAP)
                .append('|').append(safe(task))
                .append('|').append(safe(pageKey))
                .append('|').append(targetCx).append('|').append(targetCy)
                .append('|').append(actualX).append('|').append(actualY)
                .append('|').append(dx).append('|').append(dy)
                .append('|').append(width).append('|').append(height)
                .toString();
    }

    private static String buildSwipePayload(
            int startX, int startY, int endX, int endY,
            int durationMs, float pathDistance, float angleDeg, float avgSpeed,
            int width, int height, String task, String pageKey) {
        return new StringBuilder(128)
                .append("v1|").append(TYPE_SWIPE)
                .append('|').append(safe(task))
                .append('|').append(safe(pageKey))
                .append('|').append(startX).append('|').append(startY)
                .append('|').append(endX).append('|').append(endY)
                .append('|').append(durationMs)
                .append('|').append(Float.toString(pathDistance))
                .append('|').append(Float.toString(angleDeg))
                .append('|').append(Float.toString(avgSpeed))
                .append('|').append(width).append('|').append(height)
                .toString();
    }

    private static String buildSwipePayload(
            int startX, int startY, int endX, int endY,
            int durationMs, float pathDistance, float angleDeg, float avgSpeed,
            float curvatureRad, float maxDeviation, float pathRatio,
            List<int[]> trajectory,
            int width, int height, String task, String pageKey) {
        return new StringBuilder(512)
                .append("v2|").append(TYPE_SWIPE)
                .append('|').append(safe(task))
                .append('|').append(safe(pageKey))
                .append('|').append(startX).append('|').append(startY)
                .append('|').append(endX).append('|').append(endY)
                .append('|').append(durationMs)
                .append('|').append(Float.toString(pathDistance))
                .append('|').append(Float.toString(angleDeg))
                .append('|').append(Float.toString(avgSpeed))
                .append('|').append(Float.toString(curvatureRad))
                .append('|').append(Float.toString(maxDeviation))
                .append('|').append(Float.toString(pathRatio))
                .append('|').append(width).append('|').append(height)
                .append('|').append(encodeTrajectory(trajectory, width, height))
                .toString();
    }

    private static String encodeTrajectory(List<int[]> trajectory, int width, int height) {
        if (trajectory == null || trajectory.isEmpty()) return "";
        final int maxPoints = 32;
        StringBuilder sb = new StringBuilder(maxPoints * 18);
        int count = Math.min(maxPoints, trajectory.size());
        for (int i = 0; i < count; i++) {
            int[] p = trajectory.get(i);
            if (p == null || p.length < 2) continue;
            if (sb.length() > 0) sb.append(';');
            int x = Math.max(0, Math.min(width, p[0]));
            int y = Math.max(0, Math.min(height, p[1]));
            sb.append(x).append(',').append(y);
        }
        return sb.toString();
    }

    private static String buildWaitPayload(long waitMs, String task, String pageKey) {
        return new StringBuilder(72)
                .append("v1|").append(TYPE_WAIT)
                .append('|').append(safe(task))
                .append('|').append(safe(pageKey))
                .append('|').append(waitMs)
                .toString();
    }

    private static SharedPreferences prefs(Context context) {
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "_").replace("\n", " ").replace("\r", " ");
    }
}
