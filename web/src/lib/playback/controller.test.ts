import assert from "node:assert/strict";
import { afterEach, describe, test } from "node:test";
import {
  PlaybackController,
  type HlsCreateOptions,
  type HlsEngine,
  type HlsPlayer,
  type PlayerSnapshot,
  type VideoLike,
} from "./controller.ts";
import type { PlaybackSession } from "./types.ts";
import { FakeClient, FakeClock, flush, makeSession, networkError, problem, type RecordedCall } from "./test-kit.ts";

const PROFILE = "22222222-2222-2222-2222-222222222222";
const CONTENT = "33333333-3333-3333-3333-333333333333";

function sessionIdFor(n: number): string {
  return `00000000-0000-0000-0000-${String(n).padStart(12, "0")}`;
}

class FakeVideo implements VideoLike {
  currentTime = 0;
  paused = true;
  ended = false;
  src = "";
  playCalls = 0;
  canPlay = "";
  private handlers = new Map<string, Set<() => void>>();

  addEventListener(type: string, listener: () => void): void {
    const set = this.handlers.get(type) ?? new Set();
    set.add(listener);
    this.handlers.set(type, set);
  }
  removeEventListener(type: string, listener: () => void): void {
    this.handlers.get(type)?.delete(listener);
  }
  pauses = 0;
  loads = 0;
  removedAttributes: string[] = [];
  pause(): void {
    this.pauses += 1;
  }
  removeAttribute(name: string): void {
    this.removedAttributes.push(name);
    if (name === "src") {
      this.src = "";
    }
  }
  load(): void {
    this.loads += 1;
  }
  canPlayType(): string {
    return this.canPlay;
  }
  play(): Promise<void> {
    this.playCalls += 1;
    return Promise.resolve();
  }
  listenerCount(): number {
    return [...this.handlers.values()].reduce((sum, set) => sum + set.size, 0);
  }
  fire(type: string): void {
    [...(this.handlers.get(type) ?? [])].forEach((listener) => listener());
  }
}

class FakeHls implements HlsPlayer {
  startLoads = 0;
  recoveries = 0;
  destroyed = false;
  options: HlsCreateOptions;
  constructor(options: HlsCreateOptions) {
    this.options = options;
  }
  startLoad(): void {
    this.startLoads += 1;
  }
  recoverMediaError(): void {
    this.recoveries += 1;
  }
  destroy(): void {
    this.destroyed = true;
  }
}

class FakeEngine implements HlsEngine {
  supported = true;
  players: FakeHls[] = [];
  isSupported(): boolean {
    return this.supported;
  }
  create(options: HlsCreateOptions): HlsPlayer {
    const player = new FakeHls(options);
    this.players.push(player);
    return player;
  }
  get latest(): FakeHls {
    return this.players[this.players.length - 1];
  }
}

interface Script {
  /** Called for the n-th (0-based) admission; may throw. */
  admit?: (n: number) => void;
  resume?: (n: number) => number;
  /** Called for progress/end requests of a session; may throw. */
  progress?: (call: RecordedCall, sessionId: string) => unknown;
  renew?: (call: RecordedCall, sessionId: string) => unknown;
  next?: () => unknown;
  /** Resolves the n-th admission only when released. */
  gateAdmission?: (n: number) => Promise<void> | null;
}

function build(script: Script = {}) {
  const clock = new FakeClock();
  const video = new FakeVideo();
  const engine = new FakeEngine();
  const snapshots: PlayerSnapshot[] = [];
  let admissions = 0;
  const sessions: PlaybackSession[] = [];
  const client = new FakeClient(async (call) => {
    if (call.method === "POST" && call.path === "/playback/sessions") {
      const n = admissions;
      admissions += 1;
      const gate = script.gateAdmission?.(n);
      if (gate) {
        await gate;
      }
      script.admit?.(n);
      const session = makeSession(
        { sessionId: sessionIdFor(n + 1), resumePositionMs: script.resume?.(n) ?? 0 },
        clock.current,
      );
      sessions.push(session);
      return session;
    }
    const match = /^\/playback\/sessions\/([^/]+)\/(progress|end|renew)$/.exec(call.path);
    if (match) {
      if (match[2] === "renew") {
        return script.renew
          ? script.renew(call, match[1])
          : makeSession({ sessionId: match[1] }, clock.current);
      }
      return script.progress?.(call, match[1]) ?? { accepted: true };
    }
    if (call.method === "GET" && call.path.includes("/next-episode/")) {
      return script.next ? script.next() : { contentId: null };
    }
    throw new Error(`unexpected request ${call.method} ${call.path}`);
  });
  const controller = new PlaybackController({
    client,
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    video,
    engine,
    onChange: (snapshot) => snapshots.push(snapshot),
  });
  return {
    clock,
    video,
    engine,
    client,
    controller,
    snapshots,
    sessions,
    admissions: () => admissions,
    admissionCalls: () => client.to("/playback/sessions"),
    reports: () => client.calls.filter((c) => /\/(progress|end)$/.test(c.path)),
    status: () => controller.getSnapshot().status,
    play: () => {
      video.paused = false;
      video.fire("playing");
    },
    pause: () => {
      video.paused = true;
      video.fire("pause");
    },
  };
}

type Harness = ReturnType<typeof build>;
const toStop: Harness[] = [];
function started(script: Script = {}): Harness {
  const h = build(script);
  toStop.push(h);
  return h;
}
afterEach(() => {
  toStop.splice(0).forEach((h) => h.controller.stop());
});

async function begin(script: Script = {}): Promise<Harness> {
  const h = started(script);
  await h.controller.start(PROFILE, CONTENT);
  await flush();
  return h;
}

/** Makes later progress/end reports of the n-th admitted session (1-based) fail with a conflict. */
function endsSession(h: Harness, code = "PLAYBACK_EXPIRED", n = 1): void {
  const inner = h.client.responder;
  h.client.responder = (call, index) => {
    if (call.path === `/playback/sessions/${sessionIdFor(n)}/progress` || call.path === `/playback/sessions/${sessionIdFor(n)}/end`) {
      throw problem(409, code);
    }
    return inner(call, index);
  };
}

describe("PlaybackController admission", () => {
  test("admits exactly once with only profileId and contentId, then attaches hls at the resume position", async () => {
    const h = await begin({ resume: () => 42_500 });
    assert.deepEqual(
      h.admissionCalls().map((c) => [c.method, c.body]),
      [["POST", { profileId: PROFILE, contentId: CONTENT }]],
    );
    assert.equal(h.engine.players.length, 1);
    assert.equal(h.engine.latest.options.manifestPath, `/api/media/v1/streams/${sessionIdFor(1)}/master.m3u8`);
    assert.equal(h.engine.latest.options.startPositionSeconds, 42.5);
    assert.equal(h.video.playCalls, 1);
    assert.equal(h.controller.getSnapshot().resumePositionMs, 42_500);
    assert.equal(h.snapshots[0].status, "loading");
  });

  test("no progress is sent before the video reports playing", async () => {
    const h = await begin();
    await h.clock.advance(60_000);
    assert.equal(h.reports().length, 0);
  });

  const denials: Array<[number, string, string]> = [
    [403, "PREMIUM_REQUIRED", "PREMIUM_REQUIRED"],
    [403, "EMAIL_VERIFICATION_REQUIRED", "EMAIL_VERIFICATION_REQUIRED"],
    [403, "ACCESS_DENIED", "ACCESS_DENIED"],
    [404, "NOT_FOUND", "NOT_FOUND"],
    [409, "NOT_PLAYABLE_CONTENT", "NOT_PLAYABLE_CONTENT"],
    [409, "MEDIA_NOT_READY", "MEDIA_NOT_READY"],
  ];
  for (const [status, code, expected] of denials) {
    test(`admission ${status} ${code} is denied with exactly one request and no automatic retry`, async () => {
      const h = await begin({
        admit: () => {
          throw problem(status, code);
        },
      });
      assert.equal(h.status(), "denied");
      assert.equal(h.controller.getSnapshot().deniedCode, expected);
      assert.equal(h.engine.players.length, 0);
      await h.clock.advance(10 * 60_000);
      assert.equal(h.admissions(), 1);
      await h.controller.retry(); // retry is only for player-error, never for a denial
      assert.equal(h.admissions(), 1);
    });
  }

  test("a 404 without a code is still reported as NOT_FOUND", async () => {
    const h = await begin({
      admit: () => {
        throw problem(404, null);
      },
    });
    assert.equal(h.controller.getSnapshot().deniedCode, "NOT_FOUND");
  });

  test("a network failure is a retryable player-error and retry() admits once more", async () => {
    const h = await begin({
      admit: (n) => {
        if (n === 0) {
          throw networkError();
        }
      },
    });
    assert.equal(h.status(), "error");
    assert.equal(h.controller.getSnapshot().errorReason, "playback");
    assert.equal(h.admissions(), 1);
    await h.controller.retry();
    await flush();
    assert.equal(h.admissions(), 2);
    assert.equal(h.status(), "loading");
    assert.equal(h.engine.players.length, 1);
    assert.equal(h.controller.getSnapshot().errorReason, null);
  });

  test("without hls.js or native HLS support the player reports unsupported", async () => {
    const h = started();
    h.engine.supported = false;
    await h.controller.start(PROFILE, CONTENT);
    await flush();
    assert.equal(h.status(), "error");
    assert.equal(h.controller.getSnapshot().errorReason, "unsupported");
    assert.equal(h.engine.players.length, 0);
    assert.equal(h.video.src, "");
    await h.clock.advance(60_000);
    assert.equal(h.reports().length, 0);
  });

  test("native HLS is used when hls.js is unsupported and resumes at loadedmetadata", async () => {
    const h = started({ resume: () => 12_000 });
    h.engine.supported = false;
    h.video.canPlay = "maybe";
    await h.controller.start(PROFILE, CONTENT);
    await flush();
    assert.equal(h.video.src, `/api/media/v1/streams/${sessionIdFor(1)}/master.m3u8`);
    assert.equal(h.engine.players.length, 0);
    assert.equal(h.video.currentTime, 0);
    h.video.fire("loadedmetadata");
    assert.equal(h.video.currentTime, 12);
    h.video.currentTime = 3;
    h.video.fire("loadedmetadata");
    assert.equal(h.video.currentTime, 3);
  });

  test("a native video error becomes a retryable player-error", async () => {
    const h = started();
    h.engine.supported = false;
    h.video.canPlay = "maybe";
    await h.controller.start(PROFILE, CONTENT);
    await flush();
    h.video.fire("error");
    assert.equal(h.status(), "error");
    assert.equal(h.controller.getSnapshot().errorReason, "playback");
  });
});

describe("PlaybackController video events", () => {
  test("playing then pause after 10 s reports PLAYING(0) and PAUSED(10000) for the admitted session", async () => {
    const h = await begin();
    h.video.currentTime = 0;
    h.play();
    await h.clock.advance(10_000);
    h.video.currentTime = 10;
    h.pause();
    await flush();
    assert.equal(h.status(), "paused");
    assert.deepEqual(
      h.reports().map((c) => [c.path, c.body]),
      [
        [
          `/playback/sessions/${sessionIdFor(1)}/progress`,
          { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" },
        ],
        [
          `/playback/sessions/${sessionIdFor(1)}/progress`,
          { sequence: 2, positionMs: 10_000, playedMs: 10_000, state: "PAUSED" },
        ],
      ],
    );
  });

  test("a seek never earns time: the pre-seek play is checkpointed as PLAYING, SEEKING and the next PLAYING carry 0", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(5000);
    h.video.currentTime = 300;
    h.video.fire("seeking");
    h.video.fire("seeked");
    await h.clock.advance(2000);
    h.video.currentTime = 302;
    h.pause();
    await flush();
    assert.deepEqual(
      h.reports().map((c) => c.body),
      [
        { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" },
        { sequence: 2, positionMs: 5000, playedMs: 5000, state: "PLAYING" },
        { sequence: 3, positionMs: 300_000, playedMs: 0, state: "SEEKING" },
        { sequence: 4, positionMs: 300_000, playedMs: 0, state: "PLAYING" },
        { sequence: 5, positionMs: 302_000, playedMs: 2000, state: "PAUSED" },
      ],
    );
  });

  test("seeking while paused reports SEEKING then PAUSED, never PLAYING", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(1000);
    h.pause();
    h.video.currentTime = 90;
    h.video.fire("seeking");
    h.video.fire("seeked");
    await flush();
    assert.deepEqual(
      h.reports().map((c) => (c.body as { state: string }).state),
      ["PLAYING", "PAUSED", "SEEKING", "PAUSED"],
    );
  });

  test("buffering earns nothing and is ignored while paused", async () => {
    const h = await begin();
    h.video.fire("waiting"); // paused: initial load, not a playback stall
    assert.equal(h.reports().length, 0);
    h.play();
    await h.clock.advance(3000);
    h.video.currentTime = 3;
    h.video.fire("waiting");
    assert.equal(h.status(), "buffering");
    await h.clock.advance(9000);
    h.play();
    await h.clock.advance(1000);
    h.video.currentTime = 4;
    h.pause();
    await flush();
    assert.deepEqual(
      h.reports().map((c) => (c.body as { playedMs: number; state: string }).playedMs),
      [0, 3000, 0, 1000],
    );
    assert.equal(h.reports()[1].body && (h.reports()[1].body as { state: string }).state, "BUFFERING");
  });

  test("the player positioning itself before the first frame is not a seek; the first report is PLAYING", async () => {
    const h = await begin({ resume: () => 12_000 });
    h.video.currentTime = 12;
    h.video.fire("seeking");
    h.video.fire("seeked");
    h.video.paused = false;
    h.video.fire("playing");
    await flush();
    assert.deepEqual(h.reports().map((c) => c.body), [{ sequence: 1, positionMs: 12_000, playedMs: 0, state: "PLAYING" }]);
  });

  test("an autoplay load stall before the first frame is not reported; the first report is PLAYING", async () => {
    const h = await begin();
    h.video.paused = false; // autoplay unpaused the element; nothing is buffered yet
    h.video.fire("waiting");
    await h.clock.advance(2000);
    h.video.fire("playing");
    await flush();
    assert.deepEqual(h.reports().map((c) => c.body), [{ sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" }]);
  });

  test("the pause event fired right before ended is ignored; ended sends the final report to /end", async () => {
    const h = await begin({ next: () => ({ contentId: null }) });
    h.play();
    await h.clock.advance(4000);
    h.video.currentTime = 600;
    h.video.paused = true;
    h.video.ended = true;
    h.video.fire("pause");
    h.video.fire("ended");
    await flush();
    assert.deepEqual(
      h.reports().map((c) => [c.path, c.body]),
      [
        [`/playback/sessions/${sessionIdFor(1)}/progress`, { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" }],
        [`/playback/sessions/${sessionIdFor(1)}/end`, { sequence: 2, positionMs: 600_000, playedMs: 4000, state: "ENDED" }],
      ],
    );
    assert.equal(h.status(), "ended");
  });
});

describe("PlaybackController next episode", () => {
  async function endPlayback(h: Harness): Promise<void> {
    h.play();
    await h.clock.advance(1000);
    h.video.paused = true;
    h.video.ended = true;
    h.video.currentTime = 600;
    h.video.fire("ended");
    await flush();
  }

  test("after a successful /end the next episode is requested for this profile and content", async () => {
    const h = await begin({ next: () => ({ contentId: "44444444-4444-4444-4444-444444444444" }) });
    await endPlayback(h);
    assert.equal(h.client.calls[h.client.calls.length - 1].path, `/profiles/${PROFILE}/next-episode/${CONTENT}`);
    assert.deepEqual(h.controller.getSnapshot().next, {
      state: "ready",
      contentId: "44444444-4444-4444-4444-444444444444",
    });
    assert.equal(h.admissions(), 1);
  });

  test("a null answer means nothing follows", async () => {
    const h = await begin({ next: () => ({ contentId: null }) });
    await endPlayback(h);
    assert.deepEqual(h.controller.getSnapshot().next, { state: "ready", contentId: null });
  });

  test("a terminal answer to /end still looks up the next episode and never re-admits", async () => {
    const h = await begin({ next: () => ({ contentId: "55555555-5555-5555-5555-555555555555" }) });
    h.play();
    await flush();
    endsSession(h, "PLAYBACK_ENDED");
    await h.clock.advance(1000);
    h.video.paused = true;
    h.video.ended = true;
    h.video.fire("ended");
    await flush();
    assert.equal(h.admissions(), 1);
    assert.equal(h.status(), "ended");
    assert.deepEqual(h.controller.getSnapshot().next, {
      state: "ready",
      contentId: "55555555-5555-5555-5555-555555555555",
    });
  });

  test("a failing next-episode lookup falls back to the finished state", async () => {
    const h = await begin({
      next: () => {
        throw networkError();
      },
    });
    await endPlayback(h);
    assert.deepEqual(h.controller.getSnapshot().next, { state: "ready", contentId: null });
  });

  test("after ended nothing more is reported and no renewal happens", async () => {
    const h = await begin({ next: () => ({ contentId: null }) });
    await endPlayback(h);
    const before = h.client.calls.length;
    h.video.paused = false;
    h.video.ended = false;
    h.video.fire("playing");
    await h.clock.advance(10 * 60_000);
    assert.equal(h.client.calls.length, before);
  });
});

describe("PlaybackController re-admission budget", () => {
  test("an expired session is re-admitted once for the same profile and content, resuming at the new position", async () => {
    const h = await begin({ resume: (n) => (n === 1 ? 77_000 : 0) });
    h.play();
    await h.clock.advance(1000);
    endsSession(h, "MEDIA_BINDING_CHANGED");
    h.video.currentTime = 1;
    h.pause();
    await flush();
    assert.equal(h.engine.players[0].destroyed, true);
    assert.equal(h.admissions(), 2);
    assert.deepEqual(h.admissionCalls()[1].body, { profileId: PROFILE, contentId: CONTENT });
    assert.equal(h.engine.players.length, 2);
    assert.equal(h.engine.players[1].options.startPositionSeconds, 77);
    assert.equal(h.engine.players[1].options.manifestPath, `/api/media/v1/streams/${sessionIdFor(2)}/master.m3u8`);
    assert.equal(h.controller.getSnapshot().resumePositionMs, 77_000);
    assert.notEqual(h.status(), "error");
    assert.notEqual(h.status(), "denied");
    // Reports of the new session start over for its own id.
    h.client.responder = (call) => (call.path === "/playback/sessions" ? makeSession() : { accepted: true });
    h.play();
    await flush();
    const last = h.reports()[h.reports().length - 1];
    assert.equal(last.path, `/playback/sessions/${sessionIdFor(2)}/progress`);
    assert.deepEqual(last.body, { sequence: 1, positionMs: 1000, playedMs: 0, state: "PLAYING" });
  });

  for (const code of ["PLAYBACK_EXPIRED", "PLAYBACK_ENDED", "MEDIA_BINDING_CHANGED", "PLAYBACK_EXPIRING"]) {
    test(`409 ${code} on progress triggers a re-admission`, async () => {
      const h = await begin();
      h.play();
      await flush();
      endsSession(h, code);
      await h.clock.advance(1000);
      h.pause();
      await flush();
      assert.equal(h.admissions(), 2);
    });
  }

  /** Waits (video idle, so no heartbeat), ends the n-th session on the server, then the video plays. */
  async function loseSession(h: Harness, n: number, waitMs: number): Promise<void> {
    await h.clock.advance(waitMs);
    endsSession(h, "PLAYBACK_EXPIRED", n);
    h.play();
    await flush();
  }

  test("two terminal answers within 30 s do not loop: the second ends in player-error", async () => {
    const h = await begin();
    await loseSession(h, 1, 1000); // terminal #1 -> re-admit
    assert.equal(h.admissions(), 2);
    await loseSession(h, 2, 5000); // terminal #2 within 30 s of the previous re-admission
    assert.equal(h.admissions(), 2);
    assert.equal(h.status(), "error");
    assert.equal(h.controller.getSnapshot().errorReason, "playback");
    await h.clock.advance(10 * 60_000);
    assert.equal(h.admissions(), 2);
  });

  test("at most two automatic re-admissions per controller, then player-error with a manual retry", async () => {
    const h = await begin();
    await loseSession(h, 1, 1000); // t=1s -> admission 2
    assert.equal(h.admissions(), 2);
    await loseSession(h, 2, 31_000); // t=32s, 31 s after the previous one -> admission 3
    assert.equal(h.admissions(), 3);
    await loseSession(h, 3, 31_000); // budget of two is used up
    assert.equal(h.admissions(), 3);
    assert.equal(h.status(), "error");
    await h.clock.advance(60 * 60_000);
    assert.equal(h.admissions(), 3);
    // The viewer asks again: exactly one admission, and the budget starts over.
    await h.controller.retry();
    await flush();
    assert.equal(h.admissions(), 4);
    assert.equal(h.status(), "loading");
    h.play();
    await flush();
    assert.equal(h.reports()[h.reports().length - 1].path, `/playback/sessions/${sessionIdFor(4)}/progress`);
    await loseSession(h, 4, 1000);
    assert.equal(h.admissions(), 5);
  });

  test("a failing re-admission is not retried automatically", async () => {
    const h = await begin({
      admit: (n) => {
        if (n === 1) {
          throw problem(403, "PREMIUM_REQUIRED");
        }
      },
    });
    await loseSession(h, 1, 1000);
    assert.equal(h.admissions(), 2);
    assert.equal(h.status(), "denied");
    assert.equal(h.controller.getSnapshot().deniedCode, "PREMIUM_REQUIRED");
    await h.clock.advance(10 * 60_000);
    assert.equal(h.admissions(), 2);
  });

  test("403 on progress stops playback with player-denied and never re-admits", async () => {
    const h = await begin();
    h.play();
    await flush();
    h.client.responder = (call) => {
      if (/\/progress$/.test(call.path)) {
        throw problem(403, "PREMIUM_REQUIRED");
      }
      return {};
    };
    await h.clock.advance(1000);
    h.pause();
    await flush();
    assert.equal(h.status(), "denied");
    assert.equal(h.controller.getSnapshot().deniedCode, "PREMIUM_REQUIRED");
    assert.equal(h.admissions(), 1);
    assert.equal(h.engine.players[0].destroyed, true);
  });

  test("404 and a 401 (sign-out) on progress are denials without re-admission", async () => {
    for (const [status, code, expected] of [
      [404, "NOT_FOUND", "NOT_FOUND"],
      [401, "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED"],
    ] as const) {
      const h = await begin();
      h.play();
      await flush();
      h.client.responder = () => {
        throw problem(status, code);
      };
      await h.clock.advance(1000);
      h.pause();
      await flush();
      assert.equal(h.status(), "denied");
      assert.equal(h.controller.getSnapshot().deniedCode, expected);
      assert.equal(h.admissions(), 1);
    }
  });
});

describe("PlaybackController renewal", () => {
  test("renews before expiry through the session's renew endpoint", async () => {
    const h = await begin();
    await h.clock.advance(239_999);
    assert.equal(h.client.to(`/playback/sessions/${sessionIdFor(1)}/renew`).length, 0);
    await h.clock.advance(1);
    assert.equal(h.client.to(`/playback/sessions/${sessionIdFor(1)}/renew`).length, 1);
    assert.equal(h.admissions(), 1);
  });

  test("a terminal renewal conflict re-admits; a terminal denial stops playback", async () => {
    const lost = await begin({
      renew: () => {
        throw problem(409, "MEDIA_BINDING_CHANGED");
      },
    });
    await lost.clock.advance(240_000);
    await flush();
    assert.equal(lost.admissions(), 2);

    const denied = await begin({
      renew: () => {
        throw problem(403, "PREMIUM_REQUIRED");
      },
    });
    await denied.clock.advance(240_000);
    await flush();
    assert.equal(denied.status(), "denied");
    assert.equal(denied.admissions(), 1);
  });

  test("a 409 outside the re-admission set (MEDIA_NOT_READY) is a denial, never a re-admission", async () => {
    const h = await begin({
      renew: () => {
        throw problem(409, "MEDIA_NOT_READY");
      },
    });
    await h.clock.advance(240_000);
    await flush();
    assert.equal(h.status(), "denied");
    assert.equal(h.controller.getSnapshot().deniedCode, "MEDIA_NOT_READY");
    assert.equal(h.admissions(), 1);
  });
});

describe("PlaybackController stop is final", () => {
  test("retry() after stop() admits nothing, even from a retryable player-error", async () => {
    const h = await begin({
      admit: (n) => {
        if (n === 0) {
          throw networkError();
        }
      },
    });
    assert.equal(h.status(), "error");
    h.controller.stop();
    await h.controller.retry();
    await flush();
    assert.equal(h.admissions(), 1);
    assert.equal(h.engine.players.length, 0);
  });
});

describe("PlaybackController hls errors", () => {
  test("a fatal 403 network error renews once and then restarts loading", async () => {
    const h = await begin();
    h.play();
    h.engine.latest.options.onFatal({ kind: "network", httpStatus: 403 });
    await flush();
    assert.equal(h.client.to(`/playback/sessions/${sessionIdFor(1)}/renew`).length, 1);
    assert.equal(h.engine.latest.startLoads, 1);
    assert.notEqual(h.status(), "error");
  });

  test("a 401 segment error is handled like a 403", async () => {
    const h = await begin();
    h.engine.latest.options.onFatal({ kind: "network", httpStatus: 401 });
    await flush();
    assert.equal(h.engine.latest.startLoads, 1);
  });

  test("when the renewal is terminal the lifecycle rules apply instead of startLoad", async () => {
    const h = await begin({
      renew: () => {
        throw problem(409, "PLAYBACK_EXPIRED");
      },
    });
    const first = h.engine.latest;
    first.options.onFatal({ kind: "network", httpStatus: 403 });
    await flush();
    assert.equal(first.startLoads, 0);
    assert.equal(first.destroyed, true);
    assert.equal(h.admissions(), 2);
  });

  test("a repeated ticket failure within 30 s does not loop: player-error", async () => {
    const h = await begin();
    h.engine.latest.options.onFatal({ kind: "network", httpStatus: 403 });
    await flush();
    h.engine.latest.options.onFatal({ kind: "network", httpStatus: 403 });
    await flush();
    assert.equal(h.client.to(`/playback/sessions/${sessionIdFor(1)}/renew`).length, 1);
    assert.equal(h.status(), "error");
  });

  test("a failed renewal after a ticket error is a player-error", async () => {
    const h = await begin({
      renew: () => {
        throw networkError();
      },
    });
    h.engine.latest.options.onFatal({ kind: "network", httpStatus: 403 });
    await flush();
    assert.equal(h.engine.latest.startLoads, 0);
    assert.equal(h.status(), "error");
  });

  test("other fatal network errors are player-errors without renewal", async () => {
    const h = await begin();
    h.engine.latest.options.onFatal({ kind: "network", httpStatus: 503 });
    await flush();
    assert.equal(h.status(), "error");
    assert.equal(h.client.to(`/playback/sessions/${sessionIdFor(1)}/renew`).length, 0);
    assert.equal(h.engine.players[0].destroyed, true);
    assert.equal(h.admissions(), 1);
  });

  test("a fatal media error is recovered once, the second one is a player-error", async () => {
    const h = await begin();
    h.engine.latest.options.onFatal({ kind: "media", httpStatus: null });
    assert.equal(h.engine.latest.recoveries, 1);
    assert.notEqual(h.status(), "error");
    h.engine.latest.options.onFatal({ kind: "media", httpStatus: null });
    assert.equal(h.engine.players[0].recoveries, 1);
    assert.equal(h.status(), "error");
  });

  test("any other fatal error is a player-error", async () => {
    const h = await begin();
    h.engine.latest.options.onFatal({ kind: "other", httpStatus: null });
    assert.equal(h.status(), "error");
  });

  test("a player-error while playing flushes a PAUSED report first", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(6000);
    h.video.currentTime = 6;
    h.engine.latest.options.onFatal({ kind: "other", httpStatus: null });
    await flush();
    const last = h.reports()[h.reports().length - 1];
    assert.deepEqual(last.body, { sequence: 2, positionMs: 6000, playedMs: 6000, state: "PAUSED" });
    assert.equal(last.options.keepalive, true);
  });

  test("errors from a destroyed player are ignored", async () => {
    const h = await begin({ resume: () => 0 });
    h.play();
    endsSession(h);
    await h.clock.advance(1000);
    h.pause();
    await flush();
    const old = h.engine.players[0];
    assert.equal(old.destroyed, true);
    const renewCalls = h.client.calls.length;
    old.options.onFatal({ kind: "network", httpStatus: 403 });
    await flush();
    assert.equal(h.client.calls.length, renewCalls);
    assert.notEqual(h.status(), "error");
  });
});

describe("PlaybackController stop", () => {
  test("stop() while playing flushes a keepalive PAUSED, destroys hls and silences everything", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(5000);
    h.video.currentTime = 5;
    h.controller.stop();
    await flush();
    const last = h.reports()[h.reports().length - 1];
    assert.deepEqual(last.body, { sequence: 2, positionMs: 5000, playedMs: 5000, state: "PAUSED" });
    assert.equal(last.options.keepalive, true);
    assert.equal(last.options.cachedCsrfOnly, true);
    assert.equal(h.engine.players[0].destroyed, true);
    assert.equal(h.video.listenerCount(), 0);
    const calls = h.client.calls.length;
    h.play();
    h.pause();
    h.video.fire("ended");
    await h.clock.advance(60 * 60_000);
    assert.equal(h.client.calls.length, calls);
    assert.equal(h.clock.pending(), 0);
  });

  test("stop() while paused sends no report at all", async () => {
    const h = await begin();
    h.controller.stop();
    await h.clock.advance(60 * 60_000);
    assert.equal(h.reports().length, 0);
    assert.equal(h.client.to(`/playback/sessions/${sessionIdFor(1)}/renew`).length, 0);
  });

  test("stop() twice is harmless and sends the flush only once", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(1000);
    h.controller.stop();
    h.controller.stop();
    await flush();
    assert.equal(h.reports().length, 2);
  });

  test("stopping during admission means the admitted session is never attached", async () => {
    let release: () => void = () => undefined;
    const h = started({
      gateAdmission: () =>
        new Promise<void>((resolve) => {
          release = resolve;
        }),
    });
    const starting = h.controller.start(PROFILE, CONTENT);
    await flush();
    h.controller.stop();
    release();
    await starting;
    await flush();
    assert.equal(h.engine.players.length, 0);
    assert.equal(h.reports().length, 0);
    assert.equal(h.admissions(), 1);
    assert.equal(h.video.listenerCount(), 0);
  });

  test("a re-admission that finishes after stop() is discarded", async () => {
    let release: () => void = () => undefined;
    const h = await begin({
      gateAdmission: (n) =>
        n === 1
          ? new Promise<void>((resolve) => {
              release = resolve;
            })
          : null,
    });
    h.play();
    await flush();
    endsSession(h);
    await h.clock.advance(1000);
    h.pause();
    await flush();
    assert.equal(h.admissions(), 2);
    h.controller.stop();
    release();
    await flush();
    assert.equal(h.engine.players.length, 1);
  });
});

describe("PlaybackController page visibility", () => {
  test("flushOnHide sends a keepalive PAUSED while playing and resumeAfterHide restarts accrual", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(7000);
    h.video.currentTime = 7;
    h.controller.flushOnHide();
    await flush();
    let last = h.reports()[h.reports().length - 1];
    assert.deepEqual(last.body, { sequence: 2, positionMs: 7000, playedMs: 7000, state: "PAUSED" });
    assert.equal(last.options.keepalive, true);
    await h.clock.advance(20_000);
    h.video.currentTime = 27;
    h.controller.resumeAfterHide();
    await flush();
    last = h.reports()[h.reports().length - 1];
    assert.deepEqual(last.body, { sequence: 3, positionMs: 27_000, playedMs: 0, state: "PLAYING" });
  });

  test("resumeAfterHide does nothing when the video is paused", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(1000);
    h.controller.flushOnHide();
    h.video.paused = true;
    const before = h.reports().length;
    h.controller.resumeAfterHide();
    await flush();
    assert.equal(h.reports().length, before);
  });
});

describe("PlaybackController native HLS teardown", () => {
  async function native(script: Script = {}): Promise<Harness> {
    const h = started(script);
    h.engine.supported = false;
    h.video.canPlay = "maybe";
    await h.controller.start(PROFILE, CONTENT);
    await flush();
    assert.equal(h.video.src, `/api/media/v1/streams/${sessionIdFor(1)}/master.m3u8`);
    return h;
  }

  function assertDetached(h: Harness): void {
    assert.equal(h.video.pauses, 1);
    assert.deepEqual(h.video.removedAttributes, ["src"]);
    assert.equal(h.video.src, "");
    assert.equal(h.video.loads, 1);
  }

  test("stop() pauses the video, removes src and calls load()", async () => {
    const h = await native();
    h.controller.stop();
    assertDetached(h);
  });

  test("a 403 on progress detaches the native source", async () => {
    const h = await native();
    h.play();
    await flush();
    h.client.responder = () => {
      throw problem(403, "PREMIUM_REQUIRED");
    };
    await h.clock.advance(1000);
    h.pause();
    await flush();
    assert.equal(h.status(), "denied");
    assertDetached(h);
  });

  test("failPlayback (native video error) detaches the native source", async () => {
    const h = await native();
    h.video.fire("error");
    assert.equal(h.status(), "error");
    assertDetached(h);
  });

  test("the hls.js path never touches the element's src", async () => {
    const h = await begin();
    h.controller.stop();
    assert.equal(h.video.pauses, 0);
    assert.equal(h.video.loads, 0);
    assert.deepEqual(h.video.removedAttributes, []);
  });

  test("stop() while playing flushes with auth:false", async () => {
    const h = await begin();
    h.play();
    await h.clock.advance(1000);
    h.controller.stop();
    const last = h.reports()[h.reports().length - 1];
    assert.equal(last.options.auth, false);
  });
});

describe("PlaybackController renewal with a wrong client clock", () => {
  for (const [label, skew] of [
    ["+7 h", 7 * 3_600_000],
    ["-10 min", -10 * 60_000],
  ] as const) {
    test(`client clock ${label}: admission and renewals use the server-relative expiry`, async () => {
      const h = started();
      const serverNow = () => h.clock.current - skew;
      const inner = h.client.responder;
      h.client.responder = async (call, index) => {
        const result = await inner(call, index);
        if (call.path === "/playback/sessions" || call.path.endsWith("/renew")) {
          return { ...(result as PlaybackSession), expiresAt: new Date(serverNow() + 300_000).toISOString() };
        }
        return result;
      };
      h.client.headers = () => ({ Date: new Date(serverNow()).toUTCString() });
      await h.controller.start(PROFILE, CONTENT);
      await flush();
      const renews = () => h.client.calls.filter((c) => c.path.endsWith("/renew")).length;
      await h.clock.advance(239_999);
      assert.equal(renews(), 0);
      await h.clock.advance(1);
      assert.equal(renews(), 1);
      await h.clock.advance(239_999);
      assert.equal(renews(), 1);
      await h.clock.advance(1);
      assert.equal(renews(), 2);
      await h.clock.advance(240_000);
      assert.equal(renews(), 3);
      assert.equal(h.admissions(), 1);
    });
  }
});
