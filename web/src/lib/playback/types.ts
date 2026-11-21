// DTOs mirroring contracts/http/core-playback.v1.yaml.

export type PlaybackState = "PLAYING" | "PAUSED" | "BUFFERING" | "SEEKING" | "ENDED";

export interface PlaybackSession {
  sessionId: string;
  profileId: string;
  contentId: string;
  durationMs: number;
  resumePositionMs: number;
  expiresAt: string;
  manifestPath: string;
  heartbeatSeconds: number;
}

export interface ProgressBody {
  sequence: number;
  positionMs: number;
  playedMs: number;
  state: PlaybackState;
}

export interface ProgressResult {
  accepted: boolean;
  sequence: number;
  positionMs: number;
  watchedMs: number;
  qualified: boolean;
  state: PlaybackState;
}

export interface HistoryItem {
  contentId: string;
  title: string;
  positionMs: number;
  durationMs: number;
  completed: boolean;
  updatedAt: string;
}

export interface NextEpisode {
  contentId: string | null;
}

/** Opaque timer handle; the real value is a number in browsers and a Timeout object in Node. */
export type TimerHandle = unknown;

export interface Timers {
  setTimeout(callback: () => void, ms: number): TimerHandle;
  clearTimeout(handle: TimerHandle): void;
}

export const MAX_PLAYED_MS = 30_000;

/** `positionMs` = round(currentTime * 1000) clamped to [0, durationMs]. */
export function toPositionMs(currentTimeSeconds: number, durationMs: number): number {
  if (!Number.isFinite(currentTimeSeconds)) {
    return 0;
  }
  return Math.min(Math.max(Math.round(currentTimeSeconds * 1000), 0), durationMs);
}
