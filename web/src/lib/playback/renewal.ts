import { CoreProblem, isAbortError } from "../core/errors.ts";
import { receiptClock } from "./clock.ts";
import type { PlaybackSession, TimerHandle, Timers } from "./types.ts";

export interface RenewalClient {
  request<T>(method: string, path: string, options?: { onHeaders?(headers: Headers): void }): Promise<T>;
}

export interface RenewalDeps extends Timers {
  client: RenewalClient;
  /** Epoch milliseconds. */
  now(): number;
  onTerminal(problem: CoreProblem): void;
}

export type RenewOutcome = "renewed" | "terminal" | "failed" | "closed";

export const RENEW_LEAD_MS = 60_000;
export const MIN_RENEW_DELAY_MS = 1000;
export const BACKOFF_START_MS = 2000;
export const BACKOFF_MAX_MS = 30_000;

/**
 * Renew 60 s before expiry; with 120 s or less left, at half of the remaining time; never sooner than 1 s.
 * The expiry is in client time, derived from the server's own Date header (clock.ts), so a wrong client clock
 * cannot shrink the remaining lifetime into a renew loop.
 */
export function renewalDelayMs(expiresAtMs: number, nowMs: number): number {
  const remaining = expiresAtMs - nowMs;
  const delay = remaining <= 2 * RENEW_LEAD_MS ? remaining / 2 : remaining - RENEW_LEAD_MS;
  return Math.max(Math.round(delay), MIN_RENEW_DELAY_MS);
}

function isTerminalRenewal(error: CoreProblem): boolean {
  if (error.status >= 500 || error.status === 429) {
    return false;
  }
  // 409 (expired/ended/binding changed/not playable), 403, 404, 401 and anything else definitive.
  return true;
}

function isTransientRenewal(error: unknown): boolean {
  return !(error instanceof CoreProblem) || !isTerminalRenewal(error);
}

/** Keeps the Media ticket alive by renewing the playback session before it expires. */
export class RenewalScheduler {
  private readonly session: PlaybackSession;
  private readonly deps: RenewalDeps;
  private expiresAtMs: number;
  private timer: TimerHandle | null = null;
  private closed = false;
  private inFlight: Promise<RenewOutcome> | null = null;
  private failures = 0;

  /** `expiresAtMs` is the expiry in client time (see clock.ts); it defaults to the raw expiresAt. */
  constructor(session: PlaybackSession, deps: RenewalDeps, expiresAtMs?: number) {
    this.session = session;
    this.deps = deps;
    this.expiresAtMs = expiresAtMs ?? Date.parse(session.expiresAt);
  }

  start(): void {
    this.schedule(renewalDelayMs(this.expiresAtMs, this.deps.now()));
  }

  close(): void {
    this.closed = true;
    this.clear();
  }

  /** One immediate renewal (used when the media layer reports an expired ticket). Single-flight. */
  renewNow(): Promise<RenewOutcome> {
    if (this.closed) {
      return Promise.resolve("closed");
    }
    if (!this.inFlight) {
      const flight = this.attempt();
      this.inFlight = flight;
      const release = () => {
        if (this.inFlight === flight) {
          this.inFlight = null;
        }
      };
      flight.then(release, release);
    }
    return this.inFlight;
  }

  private schedule(delayMs: number): void {
    this.clear();
    if (this.closed) {
      return;
    }
    this.timer = this.deps.setTimeout(() => {
      this.timer = null;
      void this.renewNow().then((outcome) => {
        if (outcome === "failed" && !this.closed) {
          this.retryLater();
        }
      });
    }, delayMs);
  }

  private retryLater(): void {
    if (this.deps.now() >= this.expiresAtMs) {
      // The ticket lapsed while Core was unreachable: only a new admission can recover.
      this.closed = true;
      this.clear();
      this.deps.onTerminal(new CoreProblem(409, "PLAYBACK_EXPIRED", null, null));
      return;
    }
    const backoff = Math.min(BACKOFF_START_MS * 2 ** Math.max(this.failures - 1, 0), BACKOFF_MAX_MS);
    this.schedule(backoff);
  }

  private clear(): void {
    if (this.timer !== null) {
      this.deps.clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private async attempt(): Promise<RenewOutcome> {
    try {
      const clock = receiptClock(this.deps.now);
      const renewed = await this.deps.client.request<PlaybackSession>(
        "POST",
        `/playback/sessions/${encodeURIComponent(this.session.sessionId)}/renew`,
        { onHeaders: clock.onHeaders },
      );
      if (this.closed) {
        return "closed";
      }
      const next = clock.expiryOf(renewed.expiresAt);
      if (Number.isFinite(next)) {
        this.expiresAtMs = next;
      }
      this.failures = 0;
      this.schedule(renewalDelayMs(this.expiresAtMs, this.deps.now()));
      return "renewed";
    } catch (error) {
      if (this.closed || isAbortError(error)) {
        return "closed";
      }
      if (isTransientRenewal(error)) {
        this.failures += 1;
        return "failed";
      }
      this.closed = true;
      this.clear();
      if (error instanceof CoreProblem) {
        this.deps.onTerminal(error);
      }
      return "terminal";
    }
  }
}
