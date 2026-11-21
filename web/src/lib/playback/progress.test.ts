import assert from "node:assert/strict";
import { describe, test } from "node:test";
import type { CoreProblem } from "../core/errors.ts";
import { ProgressReporter, RETRY_DELAYS_MS } from "./progress.ts";
import { FakeClient, FakeClock, flush, makeSession, networkError, problem, SESSION_ID } from "./test-kit.ts";

const PROGRESS = `/playback/sessions/${SESSION_ID}/progress`;
const END = `/playback/sessions/${SESSION_ID}/end`;

function setup(responder: ConstructorParameters<typeof FakeClient>[0] = () => ({ accepted: true })) {
  const clock = new FakeClock();
  const client = new FakeClient(responder);
  const terminals: CoreProblem[] = [];
  let position = 0;
  const reporter = new ProgressReporter(makeSession(), {
    client,
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    currentPositionMs: () => position,
    onTerminal: (p) => terminals.push(p),
  });
  return {
    clock,
    client,
    reporter,
    terminals,
    setPosition: (value: number) => {
      position = value;
    },
  };
}

describe("ProgressReporter accrual", () => {
  test("playing 10 s then pausing sends PLAYING(0) then PAUSED(10000) with exact bodies", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(10_000);
    await reporter.observe("PAUSED", 10_000);
    assert.deepEqual(
      client.calls.map((c) => [c.method, c.path, c.body]),
      [
        ["POST", PROGRESS, { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" }],
        ["POST", PROGRESS, { sequence: 2, positionMs: 10_000, playedMs: 10_000, state: "PAUSED" }],
      ],
    );
  });

  test("a seek while playing checkpoints the accrual as PLAYING, then SEEKING and the next PLAYING carry 0", async () => {
    // Core credits nothing on a SEEKING report, so time played before the seek must ride a PLAYING report.
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 2000);
    await clock.advance(5000);
    await reporter.observe("SEEKING", 120_000);
    await reporter.observe("PLAYING", 120_000);
    await clock.advance(4000);
    await reporter.observe("PAUSED", 124_000);
    assert.deepEqual(
      client.calls.map((c) => [c.path, c.body]),
      [
        [PROGRESS, { sequence: 1, positionMs: 2000, playedMs: 0, state: "PLAYING" }],
        [PROGRESS, { sequence: 2, positionMs: 7000, playedMs: 5000, state: "PLAYING" }],
        [PROGRESS, { sequence: 3, positionMs: 120_000, playedMs: 0, state: "SEEKING" }],
        [PROGRESS, { sequence: 4, positionMs: 120_000, playedMs: 0, state: "PLAYING" }],
        [PROGRESS, { sequence: 5, positionMs: 124_000, playedMs: 4000, state: "PAUSED" }],
      ],
    );
  });

  test("a seek while paused sends only SEEKING with nothing to credit", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(3000);
    await reporter.observe("PAUSED", 3000);
    await clock.advance(10_000);
    await reporter.observe("SEEKING", 30_000);
    assert.deepEqual(
      client.calls.map((c) => c.body),
      [
        { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" },
        { sequence: 2, positionMs: 3000, playedMs: 3000, state: "PAUSED" },
        { sequence: 3, positionMs: 30_000, playedMs: 0, state: "SEEKING" },
      ],
    );
  });

  test("time spent buffering earns nothing", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(3000);
    await reporter.observe("BUFFERING", 3000);
    await clock.advance(7000);
    await reporter.observe("PLAYING", 3000);
    await clock.advance(2000);
    await reporter.observe("PAUSED", 5000);
    assert.deepEqual(
      client.calls.map((c) => (c.body as { playedMs: number }).playedMs),
      [0, 3000, 0, 2000],
    );
  });

  test("time spent paused earns nothing", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(1000);
    await reporter.observe("PAUSED", 1000);
    await clock.advance(60_000);
    await reporter.observe("PLAYING", 1000);
    await clock.advance(500);
    await reporter.observe("PAUSED", 1500);
    assert.deepEqual(
      client.calls.map((c) => (c.body as { playedMs: number }).playedMs),
      [0, 1000, 0, 500],
    );
  });

  test("a repeated non-playing state sends nothing, including the initial PAUSED", async () => {
    const { client, reporter } = setup();
    assert.equal(await reporter.observe("PAUSED", 0), "skipped");
    await reporter.observe("PLAYING", 0);
    await reporter.observe("PAUSED", 100);
    assert.equal(await reporter.observe("PAUSED", 100), "skipped");
    await reporter.observe("BUFFERING", 100);
    assert.equal(await reporter.observe("BUFFERING", 100), "skipped");
    assert.deepEqual(
      client.calls.map((c) => (c.body as { state: string }).state),
      ["PLAYING", "PAUSED", "BUFFERING"],
    );
  });

  test("a repeated PLAYING still reports and folds the elapsed time", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(2000);
    await reporter.observe("PLAYING", 2000);
    assert.deepEqual(client.calls[1].body, { sequence: 2, positionMs: 2000, playedMs: 2000, state: "PLAYING" });
  });

  test("playedMs never exceeds 30000 even when the clock jumped", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    clock.current += 45_000;
    await reporter.observe("PAUSED", 45_000);
    assert.equal((client.calls[1].body as { playedMs: number }).playedMs, 30_000);
  });

  test("positions are rounded and clamped to [0, durationMs]", async () => {
    const { client, reporter } = setup();
    await reporter.observe("PLAYING", 999_999_999);
    await reporter.observe("PAUSED", -50);
    await reporter.observe("PLAYING", 1234.6);
    assert.deepEqual(
      client.calls.map((c) => (c.body as { positionMs: number }).positionMs),
      [600_000, 0, 1235],
    );
  });

  test("ENDED goes to /end with the folded accrual and everything else to /progress", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(4000);
    assert.equal(await reporter.end(4000), "sent");
    assert.deepEqual(
      client.calls.map((c) => [c.path, c.body]),
      [
        [PROGRESS, { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" }],
        [END, { sequence: 2, positionMs: 4000, playedMs: 4000, state: "ENDED" }],
      ],
    );
    assert.equal(clock.pending(), 0);
  });

  test("observe(ENDED) is routed to /end as well and stops further reports", async () => {
    const { client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await reporter.observe("ENDED", 600_000);
    assert.equal(await reporter.observe("PAUSED", 600_000), "closed");
    assert.deepEqual(
      client.calls.map((c) => c.path),
      [PROGRESS, END],
    );
  });
});

describe("ProgressReporter heartbeat", () => {
  test("sends a PLAYING report every 15 s with at most 30000 played and the current position", async () => {
    const { clock, client, reporter, setPosition } = setup();
    await reporter.observe("PLAYING", 0);
    setPosition(15_000);
    await clock.advance(15_000);
    setPosition(30_000);
    await clock.advance(15_000);
    setPosition(45_000);
    await clock.advance(15_000);
    assert.deepEqual(
      client.calls.map((c) => c.body),
      [
        { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" },
        { sequence: 2, positionMs: 15_000, playedMs: 15_000, state: "PLAYING" },
        { sequence: 3, positionMs: 30_000, playedMs: 15_000, state: "PLAYING" },
        { sequence: 4, positionMs: 45_000, playedMs: 15_000, state: "PLAYING" },
      ],
    );
    for (const call of client.calls) {
      assert.ok((call.body as { playedMs: number }).playedMs <= 30_000);
    }
  });

  test("a heartbeat does not fire before 15 s and stops after pausing", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(14_999);
    assert.equal(client.calls.length, 1);
    await clock.advance(1);
    assert.equal(client.calls.length, 2);
    await reporter.observe("PAUSED", 15_000);
    assert.equal(client.calls.length, 3);
    await clock.advance(120_000);
    assert.equal(client.calls.length, 3);
    assert.equal(clock.pending(), 0);
  });

  test("a state change restarts the heartbeat period", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(10_000);
    await reporter.observe("BUFFERING", 10_000);
    await reporter.observe("PLAYING", 10_000);
    await clock.advance(14_999);
    assert.equal(client.calls.length, 3);
    await clock.advance(1);
    assert.equal(client.calls.length, 4);
    assert.deepEqual(client.calls[3].body, { sequence: 4, positionMs: 0, playedMs: 15_000, state: "PLAYING" });
  });
});

describe("ProgressReporter delivery", () => {
  test("serializes reports: the next one waits for the one in flight", async () => {
    let release: (value: unknown) => void = () => undefined;
    const { client, reporter } = setup((_call, index) =>
      index === 0 ? new Promise((resolve) => (release = resolve)) : { accepted: true },
    );
    void reporter.observe("PLAYING", 0);
    void reporter.observe("PAUSED", 500);
    void reporter.observe("PLAYING", 500);
    await flush();
    assert.equal(client.calls.length, 1);
    release({ accepted: true });
    await flush();
    assert.deepEqual(
      client.calls.map((c) => (c.body as { sequence: number }).sequence),
      [1, 2, 3],
    );
  });

  test("a failed send is retried with the same body at 1 s then 3 s and then succeeds", async () => {
    const { clock, client, reporter } = setup((_call, index) => {
      if (index < 2) {
        throw networkError();
      }
      return { accepted: true };
    });
    const sent = reporter.observe("PLAYING", 0);
    await flush();
    assert.equal(client.calls.length, 1);
    await clock.advance(RETRY_DELAYS_MS[0] - 1);
    assert.equal(client.calls.length, 1);
    await clock.advance(1);
    assert.equal(client.calls.length, 2);
    await clock.advance(RETRY_DELAYS_MS[1] - 1);
    assert.equal(client.calls.length, 2);
    await clock.advance(1);
    assert.equal(client.calls.length, 3);
    assert.equal(await sent, "sent");
    const expected = { sequence: 1, positionMs: 0, playedMs: 0, state: "PLAYING" };
    assert.deepEqual(
      client.calls.map((c) => c.body),
      [expected, expected, expected],
    );
  });

  test("after the retries the report is dropped, its time is lost and never re-attributed to a later sequence", async () => {
    let failing = false;
    const { clock, client, reporter } = setup(() => {
      if (failing) {
        throw problem(503, "UNAVAILABLE");
      }
      return { accepted: true };
    });
    await reporter.observe("PLAYING", 0);
    await clock.advance(10_000);
    failing = true;
    const lost = reporter.observe("PAUSED", 10_000);
    await clock.advance(RETRY_DELAYS_MS[0] + RETRY_DELAYS_MS[1]);
    assert.equal(await lost, "dropped");
    failing = false;
    await clock.advance(1000);
    await reporter.observe("PLAYING", 10_000);
    await clock.advance(2000);
    await reporter.observe("PAUSED", 12_000);
    const bodies = client.calls.map((c) => c.body);
    assert.deepEqual(
      bodies.map((b) => (b as { sequence: number }).sequence),
      [1, 2, 2, 2, 3, 4],
    );
    assert.deepEqual(bodies[1], { sequence: 2, positionMs: 10_000, playedMs: 10_000, state: "PAUSED" });
    assert.deepEqual(bodies[4], { sequence: 3, positionMs: 10_000, playedMs: 0, state: "PLAYING" });
    // The 10 s of the dropped report are gone; the next report only carries its own 2 s.
    assert.deepEqual(bodies[5], { sequence: 4, positionMs: 12_000, playedMs: 2000, state: "PAUSED" });
  });

  test("a dropped report is not retried further and the following report uses a fresh sequence", async () => {
    const { clock, client, reporter } = setup((_call, index) => {
      if (index < 3) {
        throw networkError();
      }
      return { accepted: true };
    });
    const first = reporter.observe("PLAYING", 0);
    await clock.advance(RETRY_DELAYS_MS[0] + RETRY_DELAYS_MS[1]);
    assert.equal(await first, "dropped");
    assert.equal(client.calls.length, 3);
    await clock.advance(1000);
    await reporter.observe("PAUSED", 1000);
    assert.equal(client.calls.length, 4);
    // Playing continued while the first report was failing, so the PAUSED report carries 5 s.
    assert.deepEqual(client.calls[3].body, { sequence: 2, positionMs: 1000, playedMs: 5000, state: "PAUSED" });
  });

  test("no sequence is ever reused for a different body", async () => {
    const { clock, client, reporter } = setup((_call, index) => {
      if (index === 1) {
        throw networkError();
      }
      return { accepted: true };
    });
    await reporter.observe("PLAYING", 0);
    await clock.advance(1000);
    void reporter.observe("PAUSED", 1000);
    await clock.advance(5000);
    void reporter.observe("PLAYING", 1000);
    await clock.advance(2000);
    await reporter.observe("PAUSED", 3000);
    const bySequence = new Map<number, string>();
    for (const call of client.calls) {
      const body = call.body as { sequence: number };
      const text = JSON.stringify(call.body);
      assert.equal(bySequence.get(body.sequence) ?? text, text, `sequence ${body.sequence} was reused with another body`);
      bySequence.set(body.sequence, text);
    }
    assert.deepEqual([...bySequence.keys()], [1, 2, 3, 4]);
  });

  test("a non-transient client error (400) is dropped without retry and is not terminal", async () => {
    const { client, reporter, terminals } = setup(() => {
      throw problem(400, "INVALID_REQUEST");
    });
    assert.equal(await reporter.observe("PLAYING", 0), "dropped");
    assert.equal(client.calls.length, 1);
    assert.equal(terminals.length, 0);
  });

  test("an unrelated 409 is dropped, not treated as the end of the session", async () => {
    const { reporter, terminals } = setup(() => {
      throw problem(409, "SOMETHING_ELSE");
    });
    assert.equal(await reporter.observe("PLAYING", 0), "dropped");
    assert.equal(terminals.length, 0);
  });
});

describe("ProgressReporter terminal answers", () => {
  const terminalCases: Array<[number, string | null]> = [
    [409, "PLAYBACK_EXPIRED"],
    [409, "PLAYBACK_ENDED"],
    [409, "MEDIA_BINDING_CHANGED"],
    [409, "PLAYBACK_EXPIRING"],
    [403, "PREMIUM_REQUIRED"],
    [403, "EMAIL_VERIFICATION_REQUIRED"],
    [403, "ACCESS_DENIED"],
    [403, null],
    [404, "NOT_FOUND"],
    [401, "AUTHENTICATION_REQUIRED"],
  ];
  for (const [status, code] of terminalCases) {
    test(`${status} ${code ?? "(no code)"} stops the reporter and calls onTerminal once`, async () => {
      const { clock, client, reporter, terminals } = setup(() => {
        throw problem(status, code);
      });
      assert.equal(await reporter.observe("PLAYING", 0), "terminal");
      assert.equal(terminals.length, 1);
      assert.equal(terminals[0].status, status);
      assert.equal(terminals[0].code, code);
      assert.equal(client.calls.length, 1);
      assert.equal(clock.pending(), 0);
      assert.equal(await reporter.observe("PAUSED", 1000), "closed");
      assert.equal(await reporter.end(1000), "closed");
      await clock.advance(120_000);
      assert.equal(client.calls.length, 1);
      assert.equal(terminals.length, 1);
    });
  }

  test("a terminal answer discards reports that were queued behind it", async () => {
    let release: (value: unknown) => void = () => undefined;
    const { client, reporter, terminals } = setup((_call, index) => {
      if (index === 0) {
        return new Promise((resolve) => (release = resolve)).then(() => {
          throw problem(409, "PLAYBACK_EXPIRED");
        });
      }
      return { accepted: true };
    });
    void reporter.observe("PLAYING", 0);
    const queued = reporter.observe("PAUSED", 100);
    await flush();
    release(undefined);
    await flush();
    assert.equal(await queued, "closed");
    assert.equal(client.calls.length, 1);
    assert.equal(terminals.length, 1);
  });
});

describe("ProgressReporter close and flushOnHide", () => {
  test("nothing is sent after close(): no observe, no heartbeat, no pending retry", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    reporter.close();
    assert.equal(clock.pending(), 0);
    await clock.advance(120_000);
    assert.equal(await reporter.observe("PAUSED", 1000), "closed");
    reporter.flushOnHide(1000);
    assert.equal(client.calls.length, 1);
  });

  test("close() cancels a retry that is waiting", async () => {
    const { clock, client, reporter } = setup(() => {
      throw networkError();
    });
    const sent = reporter.observe("PLAYING", 0);
    await flush();
    assert.equal(client.calls.length, 1);
    reporter.close();
    await flush();
    assert.equal(await sent, "closed");
    await clock.advance(10_000);
    assert.equal(client.calls.length, 1);
    assert.equal(clock.pending(), 0);
  });

  test("close() drops queued reports but the explicit end() is still delivered", async () => {
    let release: (value: unknown) => void = () => undefined;
    const { client, reporter } = setup((_call, index) =>
      index === 0 ? new Promise((resolve) => (release = resolve)) : { accepted: true },
    );
    void reporter.observe("PLAYING", 0);
    const queued = reporter.observe("PAUSED", 100);
    await flush();
    reporter.close();
    const ended = reporter.end(200);
    release({ accepted: true });
    await flush();
    assert.equal(await queued, "closed");
    assert.equal(await ended, "sent");
    assert.deepEqual(
      client.calls.map((c) => [c.path, (c.body as { sequence: number }).sequence]),
      [
        [PROGRESS, 1],
        [END, 3],
      ],
    );
  });

  test("flushOnHide while playing sends a keepalive PAUSED with the folded accrual and cached CSRF only", async () => {
    const { clock, client, reporter } = setup();
    await reporter.observe("PLAYING", 0);
    await clock.advance(8000);
    reporter.flushOnHide(8000);
    await flush();
    assert.equal(client.calls.length, 2);
    assert.deepEqual(client.calls[1].body, { sequence: 2, positionMs: 8000, playedMs: 8000, state: "PAUSED" });
    assert.equal(client.calls[1].path, PROGRESS);
    assert.equal(client.calls[1].options.keepalive, true);
    assert.equal(client.calls[1].options.auth, false);
    assert.equal(client.calls[1].options.cachedCsrfOnly, true);
    await clock.advance(60_000);
    assert.equal(client.calls.length, 2);
    // Still playing after the page came back: the next PLAYING carries no stale accrual.
    await reporter.observe("PLAYING", 9000);
    assert.deepEqual(client.calls[2].body, { sequence: 3, positionMs: 9000, playedMs: 0, state: "PLAYING" });
  });

  test("flushOnHide sends nothing when not playing or without a cached CSRF token", async () => {
    const { clock, client, reporter } = setup();
    reporter.flushOnHide(0);
    assert.equal(client.calls.length, 0);
    await reporter.observe("PLAYING", 0);
    client.csrf = null;
    await clock.advance(5000);
    reporter.flushOnHide(5000);
    await flush();
    assert.equal(client.calls.length, 1);
  });

  test("flushOnHide does not wait for the response and survives a failure", async () => {
    const { client, reporter } = setup((_call, index) => {
      if (index === 1) {
        return new Promise(() => undefined); // never settles
      }
      return { accepted: true };
    });
    await reporter.observe("PLAYING", 0);
    reporter.flushOnHide(1000);
    await flush();
    assert.equal(client.calls.length, 2);
  });
});

describe("ProgressReporter after close", () => {
  test("a forced end() being retried keeps its 1 s backoff when close() is called, and retries with auth:false", async () => {
    const { clock, client, reporter } = setup((call, index) => {
      if (call.path === END && index < 3) {
        throw networkError();
      }
      return { accepted: true };
    });
    await reporter.observe("PLAYING", 0);
    const ended = reporter.end(500);
    await flush();
    assert.equal(client.to(END).length, 1);
    assert.equal(client.to(END)[0].options.auth, undefined);
    reporter.close();
    await flush();
    assert.equal(client.to(END).length, 1, "close() must not wake the backoff");
    await clock.advance(999);
    assert.equal(client.to(END).length, 1);
    await clock.advance(1);
    assert.equal(client.to(END).length, 2);
    assert.equal(client.to(END)[1].options.auth, false);
    await clock.advance(RETRY_DELAYS_MS[1]);
    assert.equal(client.to(END).length, 3);
    assert.equal(client.to(END)[2].options.auth, false);
    assert.equal(await ended, "sent");
    assert.deepEqual(client.to(END)[0].body, client.to(END)[2].body);
  });

  test("every request sent after close() uses auth:false, including a first attempt", async () => {
    let release: (value: unknown) => void = () => undefined;
    const { client, reporter } = setup((_call, index) =>
      index === 0 ? new Promise((resolve) => (release = resolve)) : { accepted: true },
    );
    void reporter.observe("PLAYING", 0);
    await flush();
    reporter.close();
    const ended = reporter.end(100);
    release({ accepted: true });
    await flush();
    assert.equal(await ended, "sent");
    assert.equal(client.calls[0].options.auth, undefined);
    assert.equal(client.to(END)[0].options.auth, false);
  });

  test("a terminal answer to the retried end() stops everything", async () => {
    const { clock, client, reporter, terminals } = setup((call, index) => {
      if (call.path === END && index === 1) {
        throw networkError();
      }
      if (call.path === END) {
        throw problem(401, "AUTHENTICATION_REQUIRED");
      }
      return { accepted: true };
    });
    await reporter.observe("PLAYING", 0);
    const ended = reporter.end(500);
    await flush();
    reporter.close();
    await clock.advance(1000);
    assert.equal(await ended, "terminal");
    assert.equal(terminals.length, 1);
    await clock.advance(60_000);
    assert.equal(client.to(END).length, 2);
    assert.equal(clock.pending(), 0);
  });
});
