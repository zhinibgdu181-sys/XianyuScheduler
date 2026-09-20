package com.zhinibgdu.xianyu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * V4.80: binds passive human teaching samples to one concrete task session.
 *
 * GAME_RESULT and TASK_RESULT are deliberately independent. A game may fail while
 * the Xianyu task is still completed, or the game may complete while the task
 * result remains unverified. UNKNOWN is never promoted to a replay candidate.
 */
final class TeachingOutcomeStore {

    static final String SUCCESS = "SUCCESS";
    static final String FAILURE = "FAILURE";
    static final String UNKNOWN = "UNKNOWN";

    private static final String PREFS = "xianyu_teaching_outcomes_v80";
    private static final String HISTORY_KEY = "history";
    private static final int MAX_HISTORY = 160;
    private static final AtomicLong SEQ = new AtomicLong();

    private static volatile String sessionId = "";
    private static volatile String task = "";
    private static volatile String scene = "";
    private static volatile String gameResult = UNKNOWN;
    private static volatile String taskResult = UNKNOWN;

    private TeachingOutcomeStore() {}

    static synchronized void begin(Context context, String taskName, String initialScene) {
        long now = System.currentTimeMillis();
        sessionId = Long.toString(now) + "-" + Long.toString(SEQ.incrementAndGet());
        task = safe(taskName);
        scene = safe(initialScene);
        gameResult = UNKNOWN;
        taskResult = UNKNOWN;
        persist(context, "BEGIN");
    }

    static synchronized void setScene(Context context, String value) {
        if (value != null && !value.trim().isEmpty()) scene = safe(value);
        persist(context, "SCENE");
    }

    static synchronized void setGameResult(Context context, String result, String reason) {
        gameResult = normalize(result);
        persist(context, "GAME:" + safe(reason));
    }

    static synchronized void setTaskResult(Context context, String result, String reason) {
        taskResult = normalize(result);
        persist(context, "TASK:" + safe(reason));
    }

    static String currentSessionId() {
        return sessionId;
    }

    static String currentTask() {
        return task;
    }

    static String currentScene() {
        return scene;
    }

    static String currentGameResult() {
        return gameResult;
    }

    static String currentTaskResult() {
        return taskResult;
    }

    static boolean taskSucceeded() {
        return SUCCESS.equals(taskResult);
    }

    static boolean gameSucceeded() {
        return SUCCESS.equals(gameResult);
    }

    static boolean hasSession() {
        return !sessionId.isEmpty() && !task.isEmpty();
    }

    /**
     * Only a verified task success can promote generic human gestures as
     * task-level replay candidates. Old rows without an outcome are excluded.
     */
    static boolean taskReplayEligible() {
        return hasSession() && SUCCESS.equals(taskResult);
    }

    /**
     * Only a verified game success can promote game-specific strategy knowledge.
     */
    static boolean gameReplayEligible() {
        return hasSession() && SUCCESS.equals(gameResult);
    }

    static String summary() {
        return String.format(
                Locale.US,
                "session=%s task=%s scene=%s game=%s taskResult=%s",
                sessionId, task, scene, gameResult, taskResult);
    }

    private static String normalize(String value) {
        if (SUCCESS.equals(value) || FAILURE.equals(value)) return value;
        return UNKNOWN;
    }

    private static synchronized void persist(Context context, String event) {
        if (context == null || sessionId.isEmpty()) return;
        try {
            SharedPreferences p = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String old = p.getString(HISTORY_KEY, "");
            String line = String.format(
                    Locale.US,
                    "%d|%s|%s|%s|%s|%s|%s|%s",
                    System.currentTimeMillis(),
                    sessionId,
                    safe(task),
                    safe(scene),
                    gameResult,
                    taskResult,
                    safe(event),
                    "V80");
            String[] rows = old == null || old.isEmpty() ? new String[0] : old.split("\n");
            int start = Math.max(0, rows.length - MAX_HISTORY + 1);
            StringBuilder next = new StringBuilder();
            for (int i = start; i < rows.length; i++) {
                if (next.length() > 0) next.append('\n');
                next.append(rows[i]);
            }
            if (next.length() > 0) next.append('\n');
            next.append(line);
            p.edit().putString(HISTORY_KEY, next.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "_").replace("\n", " ").replace("\r", " ").trim();
    }
}
