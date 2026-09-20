package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * V4.19 “点点消不停”麻将对子视觉求解器。
 *
 * 已知规则：两张相同麻将牌靠近后消除。教程页还会要求点击已经水平相邻的对子。
 *
 * 安全策略：
 * 1. 先从深青色棋盘背景自动定位 6x6 网格；
 * 2. 只对高相似度的重复牌执行动作；
 * 3. 已相邻的同牌优先用两次点击完成教程/消除；
 * 4. 非相邻同牌优先走“同行/同列且路径为空”的滑动；
 * 5. 没有直线路径时才尝试把其中一张直接拖到另一张旁边的空格；
 * 6. 每次动作后重新截图验证，棋盘没变化就把该动作加入本轮黑名单，不连续盲滑。
 *
 * 不依赖 OpenCV，使用 Bitmap 像素、固定网格归一化和 RGB MAD 相似度。
 */
final class MahjongGameSolver {

    enum Result {
        COMPLETED,
        SAFE_STOP,
        NOT_MAHJONG_GAME,
        ABORTED
    }

    interface Host {
        boolean tap(int x, int y, String reason);
        boolean swipe(int sx, int sy, int ex, int ey, int durationMs, String reason);
        boolean sleep(long minMs, long maxMs);
        boolean aborted();
        void log(String message);
        ScreenOcr.Snapshot ocr(String reason);
    }

    private static final int ANALYSIS_WIDTH = 720;
    private static final int GRID = 6;
    private static final int FEATURE = 32;
    private static final long SCREENSHOT_TIMEOUT_MS = 4200L;
    private static final long MAX_ROUND_MS = 5L * 60L * 1000L;
    private static final int MAX_ACTIONS = 80;

    // Measured from the supplied real tutorial screenshot. True duplicate pairs
    // were <= ~0.065 RGB MAD; nearest false pairs started around ~0.149.
    private static final double MAX_TILE_MAD = 0.115;
    private static final double OCCUPIED_LIGHT_FRACTION = 0.43;

    // Geometry ratios measured from the game's own board. X step is boardWidth/6;
    // Y cells are slightly taller than X cells in the rendered board.
    private static final double GRID_TOP_OFFSET_BY_WIDTH = -0.008;
    private static final double Y_STEP_BY_WIDTH = 0.17044;

    private MahjongGameSolver() {
    }

    static boolean looksLikeMahjongPairGame(String text) {
        String t = normalize(text);
        if (t.isEmpty()) return false;

        // V4.23: "点点消不停" by itself is only the game-card title shown on
        // COIN_HOME, not proof that the board is open. Require real in-game
        // tutorial/board wording so the coin homepage cannot be misclassified.
        return t.contains("点击麻将对")
                || (t.contains("麻将对")
                    && (t.contains("水平相邻") || t.contains("相邻") || t.contains("试试点击")));
    }

    static Result solveOneRound(Context context, String suPath, Host host) {
        if (context == null || suPath == null || suPath.isEmpty() || host == null) {
            return Result.SAFE_STOP;
        }

        if (!host.sleep(260L, 480L)) return Result.ABORTED;
        ScreenOcr.Snapshot firstOcr = host.ocr("麻将游戏V4.19/进入确认");
        if (host.aborted()) return Result.ABORTED;
        String firstText = normalize(firstOcr == null ? "" : firstOcr.fullText);

        BoardFrame board = captureBoard(context, suPath, host);
        if (host.aborted()) return Result.ABORTED;
        if (board == null || board.tileCount < 2) {
            if (!looksLikeMahjongPairGame(firstText)) {
                host.log("[麻将V4.23] 当前页面不像麻将对子游戏，停止");
                return Result.NOT_MAHJONG_GAME;
            }
            host.log("[麻将V4.23] OCR识别到麻将教程，但视觉棋盘暂未定位");
            return Result.SAFE_STOP;
        }

        host.log("[麻将V4.23] ✅ 棋盘定位成功 / 牌数=" + board.tileCount
                + " / board=" + board.left + "," + board.top
                + "-" + board.right + "," + board.bottom);

        long started = SystemClock.elapsedRealtime();
        int actions = 0;
        int stagnant = 0;
        Set<String> failedMoves = new HashSet<>();

        while (!host.aborted()
                && actions < MAX_ACTIONS
                && SystemClock.elapsedRealtime() - started < MAX_ROUND_MS) {

            if (isCompletedText(firstText)) return Result.COMPLETED;
            if (board.tileCount <= 1) {
                if (!host.sleep(420L, 720L)) return Result.ABORTED;
                ScreenOcr.Snapshot doneOcr = host.ocr("麻将游戏V4.19/清盘确认");
                String doneText = normalize(doneOcr == null ? "" : doneOcr.fullText);
                if (isCompletedText(doneText) || looksLikeTaskPanel(doneText)) {
                    host.log("[麻将V4.23] ✅ 棋盘清空/已进入下一阶段");
                    return Result.COMPLETED;
                }
                BoardFrame confirm = captureBoard(context, suPath, host);
                if (confirm == null || confirm.tileCount <= 1) {
                    host.log("[麻将V4.23] ✅ 剩余牌数<=1，按本关完成处理");
                    return Result.COMPLETED;
                }
                board = confirm;
            }

            List<MatchPair> matches = findMatches(board);
            if (matches.isEmpty()) {
                host.log("[麻将V4.23] 没有高置信度重复牌，安全停止，不乱滑");
                return Result.SAFE_STOP;
            }

            Action action = chooseAction(board, matches, failedMoves);
            if (action == null) {
                host.log("[麻将V4.23] 当前重复牌没有安全移动方案，安全停止");
                return Result.SAFE_STOP;
            }

            int beforeCount = board.tileCount;
            String beforeSignature = board.signature();
            boolean issued;

            if (action.kind == ActionKind.ADJACENT_TAP) {
                int ax = board.mapX(action.a.centerX);
                int ay = board.mapY(action.a.centerY);
                int bx = board.mapX(action.b.centerX);
                int by = board.mapY(action.b.centerY);
                host.log("[麻将V4.23] 相邻对子 MAD=" + format(action.mad)
                        + " / A=(" + action.a.row + "," + action.a.col + ")"
                        + " B=(" + action.b.row + "," + action.b.col + ")");
                issued = host.tap(ax, ay, "点击相邻麻将A");
                if (issued && host.sleep(110L, 190L)) {
                    issued = host.tap(bx, by, "点击相邻麻将B");
                }
            } else {
                int sx = board.mapX(action.source.centerX);
                int sy = board.mapY(action.source.centerY);
                int ex = board.mapX(action.destCenterX);
                int ey = board.mapY(action.destCenterY);
                int cells = Math.max(1,
                        Math.abs(action.source.row - action.destRow)
                                + Math.abs(action.source.col - action.destCol));
                int duration = Math.min(520, 230 + cells * 55);
                host.log("[麻将V4.23] "
                        + (action.kind == ActionKind.AXIS_SWIPE ? "直线路径" : "自由拖动")
                        + " MAD=" + format(action.mad)
                        + " / source=(" + action.source.row + "," + action.source.col + ")"
                        + " -> dest=(" + action.destRow + "," + action.destCol + ")"
                        + " 靠近 mate=(" + action.mate.row + "," + action.mate.col + ")");
                issued = host.swipe(sx, sy, ex, ey, duration,
                        action.kind == ActionKind.AXIS_SWIPE ? "滑动麻将靠近同牌" : "拖动麻将靠近同牌");
            }

            if (!issued || host.aborted()) return Result.ABORTED;
            actions++;
            if (!host.sleep(420L, 700L)) return Result.ABORTED;

            BoardFrame after = captureBoard(context, suPath, host);
            if (host.aborted()) return Result.ABORTED;
            if (after == null) {
                ScreenOcr.Snapshot ocr = host.ocr("麻将游戏V4.19/动作后页面");
                String text = normalize(ocr == null ? "" : ocr.fullText);
                if (isCompletedText(text) || looksLikeTaskPanel(text)) {
                    host.log("[麻将V4.23] ✅ 动作后离开棋盘并检测到完成/任务面板");
                    return Result.COMPLETED;
                }
                host.log("[麻将V4.23] 动作后无法定位棋盘，安全停止");
                return Result.SAFE_STOP;
            }

            int delta = beforeCount - after.tileCount;
            String afterSignature = after.signature();
            if (delta >= 2) {
                host.log("[麻将V4.23] ✅ 消除成功 / 牌数 " + beforeCount + " -> " + after.tileCount);
                stagnant = 0;
                failedMoves.clear();
                board = after;
            } else if (!beforeSignature.equals(afterSignature)) {
                // The tile moved but did not disappear yet. Re-plan from the new
                // board instead of repeating the same swipe blindly.
                host.log("[麻将V4.23] 棋盘发生移动但暂未消除，重新规划下一步");
                stagnant = 0;
                board = after;
            } else {
                stagnant++;
                failedMoves.add(action.key());
                host.log("[麻将V4.23] ⚠️ 动作无变化，加入本轮黑名单 / stagnant=" + stagnant);
                if (stagnant >= 4) {
                    host.log("[麻将V4.23] 连续4次动作无变化，安全停止");
                    return Result.SAFE_STOP;
                }
                board = after;
            }

            if (actions % 3 == 0 || board.tileCount <= 4) {
                ScreenOcr.Snapshot ocr = host.ocr("麻将游戏V4.19/阶段确认");
                String text = normalize(ocr == null ? "" : ocr.fullText);
                if (isCompletedText(text) || looksLikeTaskPanel(text)) {
                    host.log("[麻将V4.23] ✅ OCR确认本关完成");
                    return Result.COMPLETED;
                }
                firstText = text;
            }
        }

        if (host.aborted()) return Result.ABORTED;
        host.log("[麻将V4.23] 达到安全动作/时间上限，停止");
        return Result.SAFE_STOP;
    }

    private static List<MatchPair> findMatches(BoardFrame board) {
        List<MatchPair> out = new ArrayList<>();
        List<Tile> tiles = board.tiles;
        for (int i = 0; i < tiles.size(); i++) {
            for (int j = i + 1; j < tiles.size(); j++) {
                Tile a = tiles.get(i);
                Tile b = tiles.get(j);
                double mad = rgbMad(a.feature, b.feature);
                if (mad <= MAX_TILE_MAD) {
                    out.add(new MatchPair(a, b, mad));
                }
            }
        }
        Collections.sort(out, Comparator.comparingDouble(p -> p.mad));
        return out;
    }

    private static Action chooseAction(
            BoardFrame board,
            List<MatchPair> matches,
            Set<String> failed
    ) {
        // 1) Existing adjacent pairs. The tutorial explicitly asks for a
        // horizontally adjacent pair, so horizontal comes first; vertical
        // adjacency remains valid for normal play.
        for (MatchPair pair : matches) {
            if (pair.a.row == pair.b.row && Math.abs(pair.a.col - pair.b.col) == 1) {
                Action a = Action.adjacent(pair);
                if (!failed.contains(a.key())) return a;
            }
        }
        for (MatchPair pair : matches) {
            if (pair.a.col == pair.b.col && Math.abs(pair.a.row - pair.b.row) == 1) {
                Action a = Action.adjacent(pair);
                if (!failed.contains(a.key())) return a;
            }
        }

        // 2) Prefer straight row/column motion through empty cells.
        for (MatchPair pair : matches) {
            Action a = bestMoveTowardMate(board, pair.a, pair.b, pair.mad, true);
            if (a != null && !failed.contains(a.key())) return a;
            a = bestMoveTowardMate(board, pair.b, pair.a, pair.mad, true);
            if (a != null && !failed.contains(a.key())) return a;
        }

        // 3) Conservative fallback: direct drag to an empty cell adjacent to mate.
        // It is verified immediately; if the game rejects free dragging, the move
        // is blacklisted and no blind repetition occurs.
        for (MatchPair pair : matches) {
            Action a = bestMoveTowardMate(board, pair.a, pair.b, pair.mad, false);
            if (a != null && !failed.contains(a.key())) return a;
            a = bestMoveTowardMate(board, pair.b, pair.a, pair.mad, false);
            if (a != null && !failed.contains(a.key())) return a;
        }
        return null;
    }

    private static Action bestMoveTowardMate(
            BoardFrame board,
            Tile source,
            Tile mate,
            double mad,
            boolean axisOnly
    ) {
        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
        Action best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] d : dirs) {
            int dr = mate.row + d[0];
            int dc = mate.col + d[1];
            if (dr < 0 || dr >= GRID || dc < 0 || dc >= GRID) continue;
            if (board.occupied[dr][dc]) continue;
            if (source.row == dr && source.col == dc) continue;

            boolean axis = source.row == dr || source.col == dc;
            if (axisOnly && (!axis || !pathClear(board, source.row, source.col, dr, dc))) {
                continue;
            }
            if (!axisOnly && axis && pathClear(board, source.row, source.col, dr, dc)) {
                // Already handled in the high-confidence pass.
                continue;
            }

            int dist = Math.abs(source.row - dr) + Math.abs(source.col - dc);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = Action.swipe(
                        axisOnly ? ActionKind.AXIS_SWIPE : ActionKind.DIRECT_DRAG,
                        source,
                        mate,
                        dr,
                        dc,
                        board.cellCenterX(dc),
                        board.cellCenterY(dr),
                        mad
                );
            }
        }
        return best;
    }

    private static boolean pathClear(BoardFrame board, int sr, int sc, int dr, int dc) {
        if (sr == dr) {
            int step = dc > sc ? 1 : -1;
            for (int c = sc + step; c != dc; c += step) {
                if (board.occupied[sr][c]) return false;
            }
            return true;
        }
        if (sc == dc) {
            int step = dr > sr ? 1 : -1;
            for (int r = sr + step; r != dr; r += step) {
                if (board.occupied[r][sc]) return false;
            }
            return true;
        }
        return false;
    }

    private static int manhattan(Tile a, Tile b) {
        return Math.abs(a.row - b.row) + Math.abs(a.col - b.col);
    }

    private static BoardFrame captureBoard(Context context, String suPath, Host host) {
        File file = new File(context.getCacheDir(),
                "xianyu_mahjong_v419_" + SystemClock.elapsedRealtime() + ".png");
        Bitmap original = null;
        Bitmap scaled = null;
        try {
            String cmd = "screencap -p " + shellQuote(file.getAbsolutePath());
            if (!runRoot(suPath, cmd, SCREENSHOT_TIMEOUT_MS, host)) return null;
            original = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (original == null || original.getWidth() < 300 || original.getHeight() < 500) return null;

            int analysisWidth = Math.min(ANALYSIS_WIDTH, original.getWidth());
            int analysisHeight = Math.max(1,
                    Math.round(original.getHeight() * (analysisWidth / (float) original.getWidth())));
            scaled = Bitmap.createScaledBitmap(original, analysisWidth, analysisHeight, true);

            BoardGeometry g = locateBoard(scaled);
            if (g == null) return null;
            BoardFrame board = analyzeGrid(scaled, original.getWidth(), original.getHeight(), g);
            if (board == null || board.tileCount < 2) return null;
            return board;
        } catch (Throwable t) {
            host.log("[麻将V4.23] 截图/棋盘解析异常：" + t.getClass().getSimpleName());
            return null;
        } finally {
            safeDelete(file);
            if (scaled != null && scaled != original) safeRecycle(scaled);
            safeRecycle(original);
        }
    }

    private static BoardGeometry locateBoard(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] row = new int[w];
        int minX = w, maxX = -1, minY = h, maxY = -1, hits = 0;

        // Sample every second row but every x. The board background is a unique
        // dark cyan/teal in the supplied game screen.
        for (int y = Math.max(0, h / 10); y < Math.min(h, h * 4 / 5); y += 2) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int color = row[x];
                int r = (color >> 16) & 0xff;
                int g = (color >> 8) & 0xff;
                int b = color & 0xff;
                if (isBoardTeal(r, g, b)) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                    hits++;
                }
            }
        }

        if (maxX <= minX || maxY <= minY) return null;
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        if (width < w * 0.72 || width > w * 0.99) return null;
        if (height < h * 0.35 || hits < width * 40) return null;
        return new BoardGeometry(minX, minY, maxX, maxY);
    }

    private static BoardFrame analyzeGrid(
            Bitmap bitmap,
            int originalWidth,
            int originalHeight,
            BoardGeometry g
    ) {
        float boardWidth = g.right - g.left + 1f;
        float xStep = boardWidth / GRID;
        float yStep = (float) (boardWidth * Y_STEP_BY_WIDTH);
        float gridTop = (float) (g.top + boardWidth * GRID_TOP_OFFSET_BY_WIDTH);

        boolean[][] occupied = new boolean[GRID][GRID];
        List<Tile> tiles = new ArrayList<>();
        for (int r = 0; r < GRID; r++) {
            for (int c = 0; c < GRID; c++) {
                float cx = g.left + (c + 0.5f) * xStep;
                float cy = gridTop + (r + 0.5f) * yStep;
                CellFeature f = extractCell(bitmap, cx, cy, xStep, yStep);
                if (f != null && f.lightFraction >= OCCUPIED_LIGHT_FRACTION) {
                    occupied[r][c] = true;
                    tiles.add(new Tile(r, c, cx, cy, f.rgb));
                }
            }
        }

        if (tiles.size() < 2 || tiles.size() > 36) return null;
        return new BoardFrame(
                bitmap.getWidth(), bitmap.getHeight(),
                originalWidth, originalHeight,
                g.left, g.top, g.right, g.bottom,
                gridTop, xStep, yStep, occupied, tiles
        );
    }

    private static CellFeature extractCell(
            Bitmap bitmap,
            float cx,
            float cy,
            float xStep,
            float yStep
    ) {
        float halfX = xStep * 0.43f;
        float halfY = yStep * 0.43f;
        int x0 = clamp(Math.round(cx - halfX), 0, bitmap.getWidth() - 1);
        int x1 = clamp(Math.round(cx + halfX), x0 + 1, bitmap.getWidth());
        int y0 = clamp(Math.round(cy - halfY), 0, bitmap.getHeight() - 1);
        int y1 = clamp(Math.round(cy + halfY), y0 + 1, bitmap.getHeight());
        int cw = x1 - x0;
        int ch = y1 - y0;
        if (cw < 12 || ch < 12) return null;

        int[] pixels = new int[cw * ch];
        bitmap.getPixels(pixels, 0, cw, x0, y0, cw, ch);

        int light = 0;
        for (int color : pixels) {
            int rr = (color >> 16) & 0xff;
            int gg = (color >> 8) & 0xff;
            int bb = color & 0xff;
            if ((rr + gg + bb) / 3 > 140) light++;
        }
        double lightFraction = light / (double) pixels.length;

        float[] feature = new float[FEATURE * FEATURE * 3];
        int k = 0;
        for (int gy = 0; gy < FEATURE; gy++) {
            int sy = clamp((int) (((gy + 0.5f) * ch) / FEATURE), 0, ch - 1);
            for (int gx = 0; gx < FEATURE; gx++) {
                int sx = clamp((int) (((gx + 0.5f) * cw) / FEATURE), 0, cw - 1);
                int color = pixels[sy * cw + sx];
                feature[k++] = ((color >> 16) & 0xff) / 255f;
                feature[k++] = ((color >> 8) & 0xff) / 255f;
                feature[k++] = (color & 0xff) / 255f;
            }
        }
        return new CellFeature(lightFraction, feature);
    }

    private static double rgbMad(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 1.0;
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
        return sum / a.length;
    }

    private static boolean isBoardTeal(int r, int g, int b) {
        return r < 50
                && g >= 45 && g <= 135
                && b >= 45 && b <= 135
                && Math.abs(g - b) <= 32;
    }

    private static boolean isCompletedText(String text) {
        String t = normalize(text);
        return t.contains("第2关")
                || t.contains("下一关")
                || t.contains("通关")
                || t.contains("过关")
                || t.contains("闯关成功")
                || t.contains("领取奖励")
                || t.contains("任务完成");
    }

    private static boolean looksLikeTaskPanel(String text) {
        String t = normalize(text);
        return t.contains("得骰子赚闲鱼币")
                || (t.contains("闲鱼币") && (t.contains("去完成") || t.contains("领取奖励")));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace(" ", "").replace("\n", "");
    }

    private static boolean runRoot(String suPath, String command, long timeoutMs, Host host) {
        return RootCommandRunner.run(suPath, command, timeoutMs, host::aborted);
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void safeDelete(File file) {
        try { if (file != null && file.exists()) file.delete(); } catch (Throwable ignored) {}
    }

    private static void safeRecycle(Bitmap bitmap) {
        try { if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle(); } catch (Throwable ignored) {}
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String format(double v) {
        return String.format(Locale.US, "%.3f", v);
    }

    private enum ActionKind {
        ADJACENT_TAP,
        AXIS_SWIPE,
        DIRECT_DRAG
    }

    private static final class BoardGeometry {
        final int left, top, right, bottom;
        BoardGeometry(int left, int top, int right, int bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }

    private static final class CellFeature {
        final double lightFraction;
        final float[] rgb;
        CellFeature(double lightFraction, float[] rgb) {
            this.lightFraction = lightFraction; this.rgb = rgb;
        }
    }

    private static final class Tile {
        final int row, col;
        final float centerX, centerY;
        final float[] feature;
        Tile(int row, int col, float centerX, float centerY, float[] feature) {
            this.row = row; this.col = col; this.centerX = centerX; this.centerY = centerY;
            this.feature = feature;
        }
    }

    private static final class MatchPair {
        final Tile a, b;
        final double mad;
        MatchPair(Tile a, Tile b, double mad) {
            this.a = a; this.b = b; this.mad = mad;
        }
    }

    private static final class Action {
        final ActionKind kind;
        final Tile a, b;
        final Tile source, mate;
        final int destRow, destCol;
        final float destCenterX, destCenterY;
        final double mad;

        private Action(ActionKind kind, Tile a, Tile b, Tile source, Tile mate,
                       int destRow, int destCol, float destCenterX, float destCenterY,
                       double mad) {
            this.kind = kind; this.a = a; this.b = b;
            this.source = source; this.mate = mate;
            this.destRow = destRow; this.destCol = destCol;
            this.destCenterX = destCenterX; this.destCenterY = destCenterY;
            this.mad = mad;
        }

        static Action adjacent(MatchPair p) {
            return new Action(ActionKind.ADJACENT_TAP, p.a, p.b,
                    null, null, -1, -1, 0f, 0f, p.mad);
        }

        static Action swipe(ActionKind kind, Tile source, Tile mate,
                            int dr, int dc, float dx, float dy, double mad) {
            return new Action(kind, null, null, source, mate, dr, dc, dx, dy, mad);
        }

        String key() {
            if (kind == ActionKind.ADJACENT_TAP) {
                return "T:" + a.row + "," + a.col + ":" + b.row + "," + b.col;
            }
            return kind.name() + ":" + source.row + "," + source.col
                    + "->" + destRow + "," + destCol
                    + ":M" + mate.row + "," + mate.col;
        }
    }

    private static final class BoardFrame {
        final int analysisWidth, analysisHeight;
        final int originalWidth, originalHeight;
        final int left, top, right, bottom;
        final float gridTop, xStep, yStep;
        final boolean[][] occupied;
        final List<Tile> tiles;
        final int tileCount;

        BoardFrame(int analysisWidth, int analysisHeight,
                   int originalWidth, int originalHeight,
                   int left, int top, int right, int bottom,
                   float gridTop, float xStep, float yStep,
                   boolean[][] occupied, List<Tile> tiles) {
            this.analysisWidth = analysisWidth; this.analysisHeight = analysisHeight;
            this.originalWidth = originalWidth; this.originalHeight = originalHeight;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.gridTop = gridTop; this.xStep = xStep; this.yStep = yStep;
            this.occupied = occupied; this.tiles = tiles; this.tileCount = tiles.size();
        }

        float cellCenterX(int col) { return left + (col + 0.5f) * xStep; }
        float cellCenterY(int row) { return gridTop + (row + 0.5f) * yStep; }

        int mapX(float analysisX) {
            return clamp(Math.round(analysisX * originalWidth / analysisWidth), 1, originalWidth - 2);
        }
        int mapY(float analysisY) {
            return clamp(Math.round(analysisY * originalHeight / analysisHeight), 1, originalHeight - 2);
        }

        String signature() {
            StringBuilder sb = new StringBuilder(64);
            for (int r = 0; r < GRID; r++) {
                for (int c = 0; c < GRID; c++) sb.append(occupied[r][c] ? '1' : '0');
            }
            return sb.toString();
        }
    }
}
