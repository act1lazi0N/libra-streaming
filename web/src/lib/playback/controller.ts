import { CoreProblem, isAbortError } from "../core/errors.ts";
import { receiptClock } from "./clock.ts";
import { fetchNextEpisode } from "./history.ts";
import { ProgressReporter, type ProgressClient } from "./progress.ts";
import { RenewalScheduler, type RenewalClient } from "./renewal.ts";
import { isReadmitProblem } from "./terminal.ts";
import { toPositionMs, type PlaybackSession, type PlaybackState, type Timers } from "./types.ts";

export const MAX_READMISSIONS = 2;
export const MIN_READMIT_GAP_MS = 30_000;
export const TICKET_RECOVERY_GAP_MS = 30_000;
export const NATIVE_HLS_MIME = "application/vnd.apple.mpegurl";

/** The subset of HTMLVideoElement the controller needs. */
export interface VideoLike {
  currentTime: number;
  readonly paused: boolean;
  readonly ended: boolean;
  src: string;
  pause(): void;
  removeAttribute(name: string): void;
  load(): void;
  addEventListener(type: string, listener: () => void): void;
  removeEventListener(type: string, listener: () => void): void;
  canPlayType(type: string): string;
  play(): Promise<void> | void;
}

export interface PlayerFatalError {
  kind: "network" | "media" | "other";
  /** HTTP status of the failed network request, when known. */
  httpStatus: number | null;
}

export interface HlsPlayer {
  startLoad(): void;
  recoverMediaError(): void;
  destroy(): void;
}

export interface HlsCreateOptions {
  manifestPath: string;
  startPositionSeconds: number;
  video: VideoLike;
  onFatal(error: PlayerFatalError): void;
}

/** Adapter over hls.js so the controller is testable without a browser. */
export interface HlsEngine {
  isSupported(): boolean;
  create(options: HlsCreateOptions): HlsPlayer;
}

export type PlayerStatus = "loading" | "playing" | "paused" | "buffering" | "ended" | "error" | "denied";
export type NextEpisodeState = { state: "none" } | { state: "loading" } | { state: "ready"; contentId: string | null };

export interface PlayerSnapshot {
  status: PlayerStatus;
  /** Why status is "error": no HLS support at all, or a playback failure that may be retried. */
  errorReason: "unsupported" | "playback" | null;
  /** Problem code (or HTTP-derived fallback) when status is "denied". */
  deniedCode: string | null;
  resumePositionMs: number;
  next: NextEpisodeState;
}

export interface ControllerClient extends ProgressClient, RenewalClient {
  request<T>(
    method: string,
    path: string,
    options?: {
      body?: unknown;
      signal?: AbortSignal;
      keepalive?: boolean;
      cachedCsrfOnly?: boolean;
      auth?: boolean;
      onHeaders?(headers: Headers): void;
    },
  ): Promise<T>;
}

export interface ControllerDeps extends Timers {
  client: ControllerClient;
  now(): number;
  video: VideoLike;
  engine: HlsEngine;
  onChange(snapshot: PlayerSnapshot): void;
}

const INITIAL: PlayerSnapshot = {
  status: "loading",
  errorReason: null,
  deniedCode: null,
  resumePositionMs: 0,
  next: { state: "none" },
};

function deniedCodeOf(problem: CoreProblem): string {
  if (problem.code) {
    return problem.code;
  }
  return problem.status === 404 ? "NOT_FOUND" : problem.status === 401 ? "AUTHENTICATION_REQUIRED" : "FORBIDDEN";
}

/**
 * Owns one watch page's playback: admission, hls.js, progress reporting, ticket renewal and the
 * bounded recovery rules. Free of React; the page just renders snapshots.
 */
export class PlaybackController {
  private readonly deps: ControllerDeps;
  private snapshot: PlayerSnapshot = INITIAL;
  private profileId = "";
  private contentId = "";
  private session: PlaybackSession | null = null;
  private reporter: ProgressReporter | null = null;
  private renewal: RenewalScheduler | null = null;
  private hls: HlsPlayer | null = null;
  private nativeCleanup: (() => void) | null = null;
  private nativeAttached = false;
  private readonly abort = new AbortController();
  private listeners: Array<[string, () => void]> = [];
  private admitToken = 0;
  private stopped = false;
  private ending = false;
  /** Whether the current session's video has played at least once (distinguishes the initial load from a stall). */
  private hasPlayed = false;
  private readmissions = 0;
  private lastReadmitAt: number | null = null;
  private mediaRecovered = false;
  private lastTicketRecoveryAt: number | null = null;

  constructor(deps: ControllerDeps) {
    this.deps = deps;
  }

  getSnapshot(): PlayerSnapshot {
    return this.snapshot;
  }

  /** One user action = one admission request. Never retried automatically after a denial. */
  start(profileId: string, contentId: string): Promise<void> {
    this.profileId = profileId;
    this.contentId = contentId;
    this.attachVideoListeners();
    return this.admit(true);
  }

  /** Manual retry after a player-error. Resets the automatic recovery budgets (a user action). */
  retry(): Promise<void> {
    if (this.stopped || this.snapshot.status !== "error") {
      return Promise.resolve();
    }
    this.readmissions = 0;
    this.lastReadmitAt = null;
    this.mediaRecovered = false;
    this.lastTicketRecoveryAt = null;
    return this.admit(true);
  }

  /** pagehide / visibilitychange=hidden: best-effort PAUSED report, nothing awaited. */
  flushOnHide(): void {
    if (this.stopped || this.ending || !this.reporter) {
      return;
    }
    this.reporter.flushOnHide(this.position());
  }

  /** The page became visible again; if the video kept playing, tell Core so time accrues again. */
  resumeAfterHide(): void {
    if (this.stopped || this.ending || !this.reporter) {
      return;
    }
    const video = this.deps.video;
    if (!video.paused && !video.ended) {
      void this.reporter.observe("PLAYING", this.position());
    }
  }

  /** Unmount, navigation, profile switch, sign-out. Nothing is sent for this session afterwards. */
  stop(): void {
    if (this.stopped) {
      return;
    }
    if (this.reporter && !this.ending) {
      this.reporter.flushOnHide(this.position());
    }
    this.stopped = true;
    this.admitToken += 1;
    this.abort.abort();
    this.teardownSession();
    this.detachVideoListeners();
  }

  private update(patch: Partial<PlayerSnapshot>): void {
    this.snapshot = { ...this.snapshot, ...patch };
    this.deps.onChange(this.snapshot);
  }

  private position(): number {
    return toPositionMs(this.deps.video.currentTime, this.session?.durationMs ?? 0);
  }

  // ---- admission -------------------------------------------------------------------------------

  private async admit(autoplay: boolean): Promise<void> {
    const token = (this.admitToken += 1);
    this.teardownSession();
    this.update({ status: "loading", errorReason: null, deniedCode: null, next: { state: "none" } });
    let session: PlaybackSession;
    const clock = receiptClock(this.deps.now);
    try {
      session = await this.deps.client.request<PlaybackSession>("POST", "/playback/sessions", {
        body: { profileId: this.profileId, contentId: this.contentId },
        signal: this.abort.signal,
        onHeaders: clock.onHeaders,
      });
    } catch (error) {
      if (this.stopped || token !== this.admitToken || isAbortError(error)) {
        return;
      }
      this.failAdmission(error);
      return;
    }
    if (this.stopped || token !== this.admitToken) {
      return;
    }
    this.begin(session, autoplay, clock.expiryOf(session.expiresAt));
  }

  private failAdmission(error: unknown): void {
    if (error instanceof CoreProblem && [401, 403, 404, 409].includes(error.status)) {
      this.update({ status: "denied", deniedCode: deniedCodeOf(error) });
      return;
    }
    this.update({ status: "error", errorReason: "playback" });
  }

  private begin(session: PlaybackSession, autoplay: boolean, expiresAtMs: number): void {
    this.session = session;
    this.ending = false;
    this.hasPlayed = false;
    const reporter = new ProgressReporter(session, {
      client: this.deps.client,
      now: this.deps.now,
      setTimeout: this.deps.setTimeout,
      clearTimeout: this.deps.clearTimeout,
      currentPositionMs: () => this.position(),
      onTerminal: (problem) => {
        if (this.reporter === reporter) {
          this.onSessionLost(problem);
        }
      },
    });
    const renewal = new RenewalScheduler(
      session,
      {
        client: this.deps.client,
        now: this.deps.now,
        setTimeout: this.deps.setTimeout,
        clearTimeout: this.deps.clearTimeout,
        onTerminal: (problem) => {
          if (this.renewal === renewal) {
            this.onSessionLost(problem);
          }
        },
      },
      expiresAtMs,
    );
    this.reporter = reporter;
    this.renewal = renewal;
    this.update({ resumePositionMs: session.resumePositionMs, status: "loading" });

    const video = this.deps.video;
    if (this.deps.engine.isSupported()) {
      const player: HlsPlayer = this.deps.engine.create({
        manifestPath: session.manifestPath,
        startPositionSeconds: session.resumePositionMs / 1000,
        video,
        onFatal: (error) => {
          if (this.hls === player) {
            this.onFatal(error, player, renewal);
          }
        },
      });
      this.hls = player;
    } else if (video.canPlayType(NATIVE_HLS_MIME)) {
      this.attachNative(session);
    } else {
      this.teardownSession();
      this.update({ status: "error", errorReason: "unsupported" });
      return;
    }
    renewal.start();
    if (autoplay) {
      try {
        void Promise.resolve(video.play()).catch(() => undefined);
      } catch {
        // Autoplay can be blocked; the viewer presses play.
      }
    }
  }

  private attachNative(session: PlaybackSession): void {
    const video = this.deps.video;
    const seek = () => {
      cleanup();
      if (session.resumePositionMs > 0) {
        video.currentTime = session.resumePositionMs / 1000;
      }
    };
    const cleanup = () => {
      video.removeEventListener("loadedmetadata", seek);
      this.nativeCleanup = null;
    };
    video.addEventListener("loadedmetadata", seek);
    this.nativeCleanup = cleanup;
    this.nativeAttached = true;
    video.src = session.manifestPath;
  }

  // ---- video events ----------------------------------------------------------------------------

  private attachVideoListeners(): void {
    const bind = (type: string, handler: () => void) => {
      this.deps.video.addEventListener(type, handler);
      this.listeners.push([type, handler]);
    };
    bind("playing", () => {
      if (this.reporter && !this.stopped) {
        this.hasPlayed = true;
      }
      this.observe("PLAYING", "playing");
    });
    bind("pause", () => {
      // The browser fires pause right before ended; ended carries the final report.
      if (!this.deps.video.ended) {
        this.observe("PAUSED", "paused");
      }
    });
    bind("waiting", () => {
      // Before the first frame plays (autoplay unpauses the element while nothing is buffered yet) this is the
      // initial load, not a stall: no report, the session is still PAUSED on the server.
      if (!this.deps.video.paused && this.hasPlayed) {
        this.observe("BUFFERING", "buffering");
      }
    });
    // Before the first frame, seeks are the player positioning itself (resume start position, gap skipping), not
    // the viewer: the session is still PAUSED on the server and nothing needs reporting.
    bind("seeking", () => {
      if (this.hasPlayed) {
        this.observe("SEEKING", "buffering");
      }
    });
    bind("seeked", () => {
      const video = this.deps.video;
      if (video.ended || !this.hasPlayed) {
        return;
      }
      if (video.paused) {
        this.observe("PAUSED", "paused");
      } else {
        this.observe("PLAYING", "playing");
      }
    });
    bind("ended", () => this.onEnded());
    bind("error", () => {
      if (!this.hls && this.reporter && !this.ending) {
        this.failPlayback();
      }
    });
  }

  private detachVideoListeners(): void {
    for (const [type, handler] of this.listeners) {
      this.deps.video.removeEventListener(type, handler);
    }
    this.listeners = [];
  }

  private observe(state: PlaybackState, status: PlayerStatus): void {
    if (this.stopped || this.ending || !this.reporter) {
      return;
    }
    void this.reporter.observe(state, this.position());
    this.update({ status });
  }

  private onEnded(): void {
    if (this.stopped || this.ending || !this.reporter) {
      return;
    }
    this.ending = true;
    const reporter = this.reporter;
    this.renewal?.close();
    this.update({ status: "ended", next: { state: "loading" } });
    void reporter.end(this.position()).then((outcome) => {
      if (this.stopped || this.reporter !== reporter) {
        return;
      }
      if (outcome === "sent" || outcome === "terminal" || outcome === "dropped") {
        void this.loadNextEpisode();
      } else {
        this.update({ next: { state: "none" } });
      }
    });
  }

  private async loadNextEpisode(): Promise<void> {
    let next: string | null = null;
    try {
      next = await fetchNextEpisode(this.deps.client, this.profileId, this.contentId, this.abort.signal);
    } catch (error) {
      if (this.stopped || isAbortError(error)) {
        return;
      }
      next = null;
    }
    if (!this.stopped) {
      this.update({ next: { state: "ready", contentId: next } });
    }
  }

  // ---- failures and recovery -------------------------------------------------------------------

  /** Core ended this session (progress or renewal answer): re-admit or stop, per the problem. */
  private onSessionLost(problem: CoreProblem): void {
    if (this.stopped || this.ending) {
      return;
    }
    const wasPlaying = !this.deps.video.paused && !this.deps.video.ended;
    this.teardownSession();
    if (isReadmitProblem(problem)) {
      this.autoReadmit(wasPlaying);
      return;
    }
    this.update({ status: "denied", deniedCode: deniedCodeOf(problem) });
  }

  private autoReadmit(wasPlaying: boolean): void {
    const now = this.deps.now();
    const tooSoon = this.lastReadmitAt !== null && now - this.lastReadmitAt < MIN_READMIT_GAP_MS;
    if (this.readmissions >= MAX_READMISSIONS || tooSoon) {
      this.update({ status: "error", errorReason: "playback" });
      return;
    }
    this.readmissions += 1;
    this.lastReadmitAt = now;
    void this.admit(wasPlaying);
  }

  private onFatal(error: PlayerFatalError, player: HlsPlayer, renewal: RenewalScheduler): void {
    if (this.stopped || this.ending) {
      return;
    }
    if (error.kind === "network" && (error.httpStatus === 401 || error.httpStatus === 403)) {
      void this.recoverTicket(player, renewal);
      return;
    }
    if (error.kind === "media" && !this.mediaRecovered) {
      this.mediaRecovered = true;
      player.recoverMediaError();
      return;
    }
    this.failPlayback();
  }

  /** The media ticket lapsed (e.g. after sleep): renew once, then resume loading. */
  private async recoverTicket(player: HlsPlayer, renewal: RenewalScheduler): Promise<void> {
    const now = this.deps.now();
    if (this.lastTicketRecoveryAt !== null && now - this.lastTicketRecoveryAt < TICKET_RECOVERY_GAP_MS) {
      this.failPlayback();
      return;
    }
    this.lastTicketRecoveryAt = now;
    const outcome = await renewal.renewNow();
    if (this.stopped || this.hls !== player) {
      return;
    }
    if (outcome === "renewed") {
      player.startLoad();
    } else if (outcome === "failed") {
      this.failPlayback();
    }
    // "terminal" was already routed to onSessionLost through the scheduler.
  }

  private failPlayback(): void {
    if (this.stopped) {
      return;
    }
    this.reporter?.flushOnHide(this.position());
    this.teardownSession();
    this.update({ status: "error", errorReason: "playback" });
  }

  private teardownSession(): void {
    this.reporter?.close();
    this.renewal?.close();
    this.nativeCleanup?.();
    this.hls?.destroy();
    this.reporter = null;
    this.renewal = null;
    this.hls = null;
    if (this.nativeAttached) {
      // Native HLS keeps loading by itself: detach the source so no further requests are made.
      this.nativeAttached = false;
      const video = this.deps.video;
      video.pause();
      video.removeAttribute("src");
      video.load();
    }
  }
}
