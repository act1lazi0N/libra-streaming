import { CoreProblem, isAbortError } from "../core/errors.ts";
import { isTerminalProgressProblem, isTransientFailure } from "./terminal.ts";
import {
  MAX_PLAYED_MS,
  type PlaybackSession,
  type PlaybackState,
  type ProgressBody,
  type TimerHandle,
  type Timers,
} from "./types.ts";

export interface ProgressClient {
  request<T>(
    method: string,
    path: string,
    options?: { body?: unknown; keepalive?: boolean; cachedCsrfOnly?: boolean; auth?: boolean },
  ): Promise<T>;
  cachedCsrf(): unknown;
}

export interface ProgressDeps extends Timers {
  client: ProgressClient;
  now(): number;
  /** Current clamped playback position, used by the heartbeat. */
  currentPositionMs(): number;
  onTerminal(problem: CoreProblem): void;
}

/** What happened to one report: delivered, given up on, ended the session, or never sent. */
export type ReportOutcome = "sent" | "dropped" | "terminal" | "closed" | "skipped";

/** Delays before the 2nd and 3rd attempt of a failed report; after that it is dropped. */
export const RETRY_DELAYS_MS: readonly number[] = [1000, 3000];

interface QueuedReport {
  path: string;
  body: ProgressBody;
  /** Only the explicit end() may be sent after close(). */
  force: boolean;
  settle(outcome: ReportOutcome): void;
}

/**
 * Sends sequence-numbered progress to Core. Core credits watch time only for the interval after an
 * accepted PLAYING report, so playedMs is exactly the time the video really spent playing since
 * the previous report, never paused/buffering/seeking time, and a sequence is never reused for a
 * different body.
 */
export class ProgressReporter {
  private readonly session: PlaybackSession;
  private readonly deps: ProgressDeps;
  private sequence = 0;
  private localState: PlaybackState = "PAUSED"; // admission leaves the session PAUSED
  private playingSince: number | null = null;
  private accruedMs = 0;
  private lastPositionMs = 0;
  private readonly queue: QueuedReport[] = [];
  private inFlight = false;
  private closed = false;
  private terminated = false;
  private finished = false;
  private heartbeat: TimerHandle | null = null;
  private retryWait: { handle: TimerHandle; force: boolean; wake(): void } | null = null;

  constructor(session: PlaybackSession, deps: ProgressDeps) {
    this.session = session;
    this.deps = deps;
  }

  /** Record a state observation; reports it unless it repeats a non-playing state. */
  observe(state: PlaybackState, positionMs: number): Promise<ReportOutcome> {
    if (this.closed || this.terminated || this.finished) {
      return Promise.resolve("closed");
    }
    return this.submit(state, positionMs, false);
  }

  /** The explicit final report (POST .../end). Allowed after close(), never after termination. */
  end(positionMs: number): Promise<ReportOutcome> {
    if (this.terminated || this.finished) {
      return Promise.resolve("closed");
    }
    return this.submit("ENDED", positionMs, true);
  }

  /**
   * Synchronous best effort for pagehide / visibilitychange=hidden / stop(): when playing, fold the
   * accrual into a PAUSED report sent with keepalive and only the cached CSRF token.
   */
  flushOnHide(positionMs: number): void {
    if (this.closed || this.terminated || this.finished || this.localState !== "PLAYING") {
      return;
    }
    if (!this.deps.client.cachedCsrf()) {
      return;
    }
    const body = this.fold("PAUSED", positionMs);
    this.clearHeartbeat();
    this.deps.client
      .request("POST", this.path("progress"), { body, keepalive: true, cachedCsrfOnly: true, auth: false })
      .catch(() => undefined);
  }

  close(): void {
    this.closed = true;
    this.clearHeartbeat();
    const pending = this.queue.splice(0);
    for (const item of pending) {
      if (item.force) {
        this.queue.push(item);
      } else {
        item.settle("closed");
      }
    }
    // A forced end() keeps its backoff; only waits of reports that close() discards are woken.
    if (this.retryWait && !this.retryWait.force) {
      const wait = this.retryWait;
      this.deps.clearTimeout(wait.handle);
      this.retryWait = null;
      wait.wake();
    }
  }

  private path(endpoint: "progress" | "end"): string {
    return `/playback/sessions/${encodeURIComponent(this.session.sessionId)}/${endpoint}`;
  }

  /** Folds accrual up to now, advances the local state and returns the body for the next sequence. */
  private fold(state: PlaybackState, positionMs: number): ProgressBody {
    const now = this.deps.now();
    if (this.localState === "PLAYING" && this.playingSince !== null) {
      this.accruedMs += Math.max(now - this.playingSince, 0);
    }
    this.playingSince = state === "PLAYING" ? now : null;
    const position = Math.min(Math.max(Math.round(positionMs), 0), this.session.durationMs);
    this.sequence += 1;
    const body: ProgressBody = {
      sequence: this.sequence,
      positionMs: position,
      playedMs: Math.min(Math.round(this.accruedMs), MAX_PLAYED_MS),
      state,
    };
    this.accruedMs = 0;
    this.localState = state;
    this.lastPositionMs = position;
    return body;
  }

  private submit(state: PlaybackState, positionMs: number, force: boolean): Promise<ReportOutcome> {
    if (state === this.localState && state !== "PLAYING") {
      return Promise.resolve("skipped");
    }
    if (state === "SEEKING" && this.localState === "PLAYING") {
      // Core credits nothing on a SEEKING report (PlaybackService.progress), so the time played since the last
      // report is checkpointed first as PLAYING. At `seeking` the video already reports the target, so the
      // pre-seek position is estimated from the last reported one plus the time played since.
      const playing = this.playingSince === null ? 0 : Math.max(this.deps.now() - this.playingSince, 0);
      const checkpoint = this.fold("PLAYING", this.lastPositionMs + this.accruedMs + playing);
      void this.enqueue(this.path("progress"), checkpoint, force);
    }
    const body = this.fold(state, positionMs);
    if (state === "PLAYING") {
      this.scheduleHeartbeat();
    } else {
      this.clearHeartbeat();
    }
    if (state === "ENDED") {
      this.finished = true;
    }
    return this.enqueue(this.path(state === "ENDED" ? "end" : "progress"), body, force);
  }

  private enqueue(path: string, body: ProgressBody, force: boolean): Promise<ReportOutcome> {
    return new Promise<ReportOutcome>((settle) => {
      this.queue.push({ path, body, force, settle });
      this.pump();
    });
  }

  private scheduleHeartbeat(): void {
    this.clearHeartbeat();
    this.heartbeat = this.deps.setTimeout(() => {
      this.heartbeat = null;
      if (this.closed || this.terminated || this.localState !== "PLAYING") {
        return;
      }
      void this.submit("PLAYING", this.deps.currentPositionMs(), false);
    }, this.session.heartbeatSeconds * 1000);
  }

  private clearHeartbeat(): void {
    if (this.heartbeat !== null) {
      this.deps.clearTimeout(this.heartbeat);
      this.heartbeat = null;
    }
  }

  private pump(): void {
    if (this.inFlight) {
      return;
    }
    const item = this.queue.shift();
    if (!item) {
      return;
    }
    this.inFlight = true;
    void this.deliver(item).then((outcome) => {
      this.inFlight = false;
      item.settle(outcome);
      this.pump();
    });
  }

  private async deliver(item: QueuedReport): Promise<ReportOutcome> {
    for (let attempt = 0; ; attempt += 1) {
      if (this.terminated || (this.closed && !item.force)) {
        return "closed";
      }
      try {
        // After close() a sign-out may be under way: such a request must never trigger a refresh.
        await this.deps.client.request("POST", item.path, this.closed ? { body: item.body, auth: false } : { body: item.body });
        return "sent";
      } catch (error) {
        if (isAbortError(error)) {
          return "dropped";
        }
        if (isTerminalProgressProblem(error)) {
          this.terminate(error);
          return "terminal";
        }
        if (!isTransientFailure(error) || attempt >= RETRY_DELAYS_MS.length) {
          // Dropped: its accrued time is lost and never re-attributed to a later sequence.
          return "dropped";
        }
        await this.sleep(RETRY_DELAYS_MS[attempt], item.force);
      }
    }
  }

  private sleep(ms: number, force: boolean): Promise<void> {
    return new Promise<void>((resolve) => {
      if (this.closed && !force) {
        resolve();
        return;
      }
      this.retryWait = {
        handle: this.deps.setTimeout(() => {
          this.retryWait = null;
          resolve();
        }, ms),
        force,
        wake: resolve,
      };
    });
  }

  private terminate(problem: CoreProblem): void {
    if (this.terminated) {
      return;
    }
    this.terminated = true;
    this.clearHeartbeat();
    this.queue.splice(0).forEach((item) => item.settle("closed"));
    this.deps.onTerminal(problem);
  }
}
