import { CoreNetworkError, CoreProblem } from "../core/errors.ts";
import type { PlaybackSession, TimerHandle } from "./types.ts";

/** Lets chained promise callbacks run (many microtask turns). */
export async function flush(turns = 20): Promise<void> {
  for (let i = 0; i < turns; i += 1) {
    await Promise.resolve();
  }
}

interface FakeTimer {
  id: number;
  at: number;
  callback: () => void;
}

export class FakeClock {
  current: number;
  private nextId = 1;
  private timers: FakeTimer[] = [];

  constructor(start = 1_000_000) {
    this.current = start;
  }

  now = (): number => this.current;

  setTimeout = (callback: () => void, ms: number): TimerHandle => {
    const timer = { id: this.nextId, at: this.current + ms, callback };
    this.nextId += 1;
    this.timers.push(timer);
    return timer.id;
  };

  clearTimeout = (handle: TimerHandle): void => {
    this.timers = this.timers.filter((timer) => timer.id !== handle);
  };

  pending(): number {
    return this.timers.length;
  }

  /** Moves time forward, firing due timers in order and letting promises settle after each. */
  async advance(ms: number): Promise<void> {
    const target = this.current + ms;
    await flush();
    for (;;) {
      const due = this.timers.filter((timer) => timer.at <= target).sort((a, b) => a.at - b.at || a.id - b.id)[0];
      if (!due) {
        break;
      }
      this.timers = this.timers.filter((timer) => timer !== due);
      this.current = Math.max(this.current, due.at);
      due.callback();
      await flush();
    }
    this.current = target;
    await flush();
  }
}

export interface RecordedCall {
  method: string;
  path: string;
  body: unknown;
  options: Record<string, unknown>;
}

export type Responder = (call: RecordedCall, index: number) => unknown;

/** A scripted core client: the responder returns a value, or throws a CoreProblem/CoreNetworkError. */
export class FakeClient {
  calls: RecordedCall[] = [];
  csrf: unknown = { headerName: "X-CSRF-TOKEN", token: "t" };
  responder: Responder;
  /** When set, successful responses expose these headers through options.onHeaders. */
  headers: ((call: RecordedCall) => Record<string, string>) | null = null;

  constructor(responder: Responder = () => ({})) {
    this.responder = responder;
  }

  request = async <T>(method: string, path: string, options: Record<string, unknown> = {}): Promise<T> => {
    const call: RecordedCall = { method, path, body: options.body, options };
    this.calls.push(call);
    await Promise.resolve();
    const result = (await this.responder(call, this.calls.length - 1)) as T;
    if (this.headers && typeof options.onHeaders === "function") {
      options.onHeaders(new Headers(this.headers(call)));
    }
    return result;
  };

  cachedCsrf = (): unknown => this.csrf;

  to(path: string): RecordedCall[] {
    return this.calls.filter((call) => call.path === path);
  }
}

export function problem(status: number, code: string | null = null): CoreProblem {
  return new CoreProblem(status, code, null, null);
}

export function networkError(): CoreNetworkError {
  return new CoreNetworkError();
}

export const SESSION_ID = "11111111-1111-1111-1111-111111111111";

export function makeSession(overrides: Partial<PlaybackSession> = {}, nowMs = 1_000_000): PlaybackSession {
  const sessionId = overrides.sessionId ?? SESSION_ID;
  return {
    sessionId,
    profileId: "22222222-2222-2222-2222-222222222222",
    contentId: "33333333-3333-3333-3333-333333333333",
    durationMs: 600_000,
    resumePositionMs: 0,
    expiresAt: new Date(nowMs + 300_000).toISOString(),
    manifestPath: `/api/media/v1/streams/${sessionId}/master.m3u8`,
    heartbeatSeconds: 15,
    ...overrides,
  };
}
