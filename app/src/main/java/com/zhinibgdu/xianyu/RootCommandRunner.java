package com.zhinibgdu.xianyu;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** Bounded execution for screenshot commands whose output is deliberately unused. */
final class RootCommandRunner {
    interface Cancellation { boolean cancelled(); }
    private RootCommandRunner() {}
    static boolean run(String executable, String command, long timeoutMs, Cancellation cancellation) {
        Process process = null;
        try {
            if (cancellation != null && cancellation.cancelled()) return false;
            // Redirect before waiting: a full stdout/stderr pipe cannot deadlock the child.
            process = new ProcessBuilder(executable, "-c", command)
                    .redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
            while (System.nanoTime() < deadline) {
                if (cancellation != null && cancellation.cancelled()) return false;
                long leftMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                if (process.waitFor(Math.min(100L, leftMs), TimeUnit.MILLISECONDS))
                    return process.exitValue() == 0 && (cancellation == null || !cancellation.cancelled());
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                try { process.getErrorStream().close(); } catch (Exception ignored) {}
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
            }
        }
    }
}
