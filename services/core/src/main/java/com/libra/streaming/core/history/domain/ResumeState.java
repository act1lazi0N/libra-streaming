package com.libra.streaming.core.history.domain;

/** Resume rules shared by playback admission and progress acceptance. */
public record ResumeState(long positionMs, boolean completed) {
    public long openingPosition(long durationMs) {
        return completed ? 0 : Math.min(positionMs, durationMs);
    }

    public static boolean completes(long positionMs, long durationMs) {
        return positionMs * 100 >= durationMs * 95;
    }
}
