package com.zhinibgdu.xianyu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Root screenshot + ML Kit Chinese OCR fallback for UC WebView pages.
 *
 * 闲鱼币和任务列表在部分版本中由 UC WebView 渲染，UIAutomator 只能看到
 * "WVUCWebView / 首页 / 领 ×1" 之类的极少文本。这个类只在 XML 信息不足时
 * 使用截图 OCR，避免靠盲目固定坐标点击。
 */
final class ScreenOcr {

    private static final long ROOT_TIMEOUT_MS = 8000L;
    private static final long OCR_TIMEOUT_MS = 12000L;

    private ScreenOcr() {
    }

    private static TextRecognizer sharedRecognizer;

    static synchronized void close() {
        if (sharedRecognizer != null) {
            try { sharedRecognizer.close(); } catch (Exception ignored) {}
            sharedRecognizer = null;
        }
    }

    /** Returns a decoded root screenshot for image-based solvers. The caller owns the Bitmap. */
    static Bitmap captureBitmap(Context context, String suPath,
                                RootCommandRunner.Cancellation cancellation) {
        if (context == null || suPath == null || suPath.trim().isEmpty()) return null;
        File screenshot = null;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) return null;
            screenshot = File.createTempFile("xianyu_frame_", ".png", dir);
            String path = shellQuote(screenshot.getAbsolutePath());
            if (!RootCommandRunner.run(suPath, "screencap -p " + path + " && chmod 0644 " + path,
                    ROOT_TIMEOUT_MS, cancellation)) return null;
            return BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        } catch (Throwable ignored) {
            return null;
        } finally {
            safeDelete(screenshot);
        }
    }

    static synchronized Snapshot capture(Context context, String suPath,
                                         RootCommandRunner.Cancellation cancellation) {
        if (context == null || suPath == null || suPath.trim().isEmpty()) return Snapshot.empty();
        File screenshot = null;
        Bitmap bitmap = null;
        TextRecognizer recognizer = null;
        Task<Text> pending = null;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null || (!dir.exists() && !dir.mkdirs())) return Snapshot.empty();
            screenshot = File.createTempFile("xianyu_ocr_", ".png", dir);
            String path = shellQuote(screenshot.getAbsolutePath());
            if (!RootCommandRunner.run(suPath, "screencap -p " + path + " && chmod 0644 " + path,
                    ROOT_TIMEOUT_MS, cancellation)) return Snapshot.empty();
            bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
            if (bitmap == null) return Snapshot.empty();
            boolean welfareFishVisible = hasWelfareFishOverlay(bitmap);
            if (sharedRecognizer == null)
                sharedRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            recognizer = sharedRecognizer;
            pending = recognizer.process(InputImage.fromBitmap(bitmap, 0));
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OCR_TIMEOUT_MS);
            while (!pending.isComplete()) {
                if (System.nanoTime() >= deadline || (cancellation != null && cancellation.cancelled()))
                    return Snapshot.empty();
                Thread.sleep(100L);
            }
            if (cancellation != null && cancellation.cancelled()) return Snapshot.empty();
            Text result = pending.getResult();
            List<Item> items = new ArrayList<>();
            if (result != null) {
                for (Text.TextBlock block : result.getTextBlocks()) {
                    for (Text.Line line : block.getLines()) {
                        String text = normalize(line.getText());
                        Rect bounds = line.getBoundingBox();
                        if (!text.isEmpty() && bounds != null) items.add(new Item(text, new Rect(bounds)));
                    }
                }
            }
            return new Snapshot(result == null ? "" : normalize(result.getText()), items,
                    bitmap.getWidth(), bitmap.getHeight(), welfareFishVisible);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Snapshot.empty();
        } catch (Exception e) {
            return Snapshot.empty();
        } finally {
            if (pending != null && !pending.isComplete()) {
                // ML Kit may still be reading the input after our timeout. It owns these
                // resources until completion; the next request gets a new recognizer.
                sharedRecognizer = null;
                final Bitmap heldBitmap = bitmap;
                final TextRecognizer heldRecognizer = recognizer;
                pending.addOnCompleteListener(Runnable::run, done -> {
                    if (heldBitmap != null) heldBitmap.recycle();
                    if (heldRecognizer != null) heldRecognizer.close();
                });
            } else if (bitmap != null) {
                bitmap.recycle();
            }
            safeDelete(screenshot);
        }
    }

    /**
     * 闲鱼“滑动浏览15s”完成标记是右下角的小黄鱼图标，不是稳定的 OCR 文本。
     * 某些页面完成后 OCR 仍会从商品内容里识别出“滑动浏览9s”，因此这里直接
     * 检测任务浮层的小黄鱼像素；仅在右下区域统计高饱和黄色像素。
     */
    private static boolean hasWelfareFishOverlay(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = Math.max(0, Math.round(width * 0.72f));
        int top = Math.max(0, Math.round(height * 0.70f));
        int bottom = Math.min(height, Math.round(height * 0.90f));

        int yellow = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xff;
                int g = (pixel >> 8) & 0xff;
                int b = pixel & 0xff;
                if (r >= 200 && g >= 150 && b <= 105 && r - b >= 110) {
                    if (++yellow >= 900) return true;
                }
            }
        }
        return false;
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void safeDelete(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Throwable ignored) {
        }
    }

    static final class Snapshot {
        final String fullText;
        final List<Item> items;
        final int width;
        final int height;
        final boolean welfareFishVisible;

        Snapshot(String fullText, List<Item> items, int width, int height, boolean welfareFishVisible) {
            this.fullText = fullText == null ? "" : fullText;
            this.items = items == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(items));
            this.width = width;
            this.height = height;
            this.welfareFishVisible = welfareFishVisible;
        }

        /** Backward-compatible constructor for existing unit-test fixtures. */
        Snapshot(String fullText, List<Item> items, int width, int height) {
            this(fullText, items, width, height, false);
        }

        static Snapshot empty() {
            return new Snapshot("", Collections.emptyList(), 0, 0, false);
        }

        boolean isEmpty() {
            return fullText.isEmpty() && items.isEmpty();
        }

        boolean contains(String token) {
            return token != null && !token.isEmpty() && fullText.contains(token);
        }

        Item findBest(String... tokens) {
            if (tokens == null) return null;

            Item best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Item item : items) {
                if (item == null || item.text.isEmpty()) continue;
                for (String token : tokens) {
                    if (token == null || token.isEmpty()) continue;
                    int score = matchScore(item.text, token);
                    if (score > bestScore) {
                        bestScore = score;
                        best = item;
                    }
                }
            }
            return bestScore > 0 ? best : null;
        }

        private static int matchScore(String text, String token) {
            if (text.equals(token)) return 10000 - text.length();
            if (text.startsWith(token)) return 8000 - text.length();
            if (text.endsWith(token)) return 7500 - text.length();
            if (text.contains(token)) return 6000 - text.length();
            return -1;
        }
    }

    static final class Item {
        final String text;
        final Rect bounds;

        Item(String text, Rect bounds) {
            this.text = text == null ? "" : text;
            this.bounds = bounds == null ? new Rect() : bounds;
        }

        int centerX() {
            return bounds.centerX();
        }

        int centerY() {
            return bounds.centerY();
        }

        String boundsString() {
            return "[" + bounds.left + "," + bounds.top + "]["
                    + bounds.right + "," + bounds.bottom + "]";
        }
    }
}
