package com.libra.streaming.core.playback;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public final class PlaybackModels {
    private PlaybackModels() {}
    public enum State { PLAYING, PAUSED, BUFFERING, SEEKING, ENDED }
    public record Admission(@NotNull UUID profileId, @NotNull UUID contentId) {}
    public record Progress(@JsonProperty(required = true) @Min(1) @Max(9007199254740991L) long sequence,
            @JsonProperty(required = true) @Min(0) @Max(604800000) long positionMs,
            @JsonProperty(required = true) @Min(0) @Max(30000) long playedMs, @NotNull State state) {}
    public record SessionView(UUID sessionId, UUID profileId, UUID contentId, long durationMs,
            long resumePositionMs, Instant expiresAt, String manifestPath, int heartbeatSeconds) {}
    public record ProgressView(boolean accepted, long sequence, long positionMs, long watchedMs,
            boolean qualified, State state) {}
    public record Issued(SessionView view, String ticket) {
        @Override public String toString() { return "Issued[ticket redacted]"; }
    }
    public record ViewingEvent(UUID accountId, UUID profileId, UUID sessionId, UUID contentId, UUID assetId,
            long assetVersion, long sequence, long positionMs, long acceptedWatchedMs, long totalWatchedMs,
            boolean qualified, State state) {}
}
