import assert from "node:assert/strict";
import { describe, test } from "node:test";
import type { CoreProblem } from "../core/errors.ts";
import { expiryInClientTime } from "./clock.ts";
import { renewalDelayMs, RenewalScheduler } from "./renewal.ts";
import { FakeClient, FakeClock, makeSession, networkError, problem, SESSION_ID } from "./test-kit.ts";

const RENEW = `/playback/sessions/${SESSION_ID}/renew`;

function setup(lifetimeMs: number, responder: ConstructorParameters<typeof FakeClient>[0]) {
  const clock = new FakeClock();
  const client = new FakeClient(responder);
  const terminals: CoreProblem[] = [];
  const session = makeSession({ expiresAt: new Date(clock.current + lifetimeMs).toISOString() }, clock.current);
  const scheduler = new RenewalScheduler(session, {
    client,
    now: clock.now,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout,
    onTerminal: (p) => terminals.push(p),
  });
  return { clock, client, scheduler, terminals };
}

function renewedAt(clock: FakeClock, lifetimeMs: number) {
  return makeSession({ expiresAt: new Date(clock.current + lifetimeMs).toISOString() }, clock.current);
}

describe("renewalDelayMs", () => {
  test("renews 60 s before expiry when more than 120 s remain", () => {
    assert.equal(renewalDelayMs(300_000, 0), 240_000);
    assert.equal(renewalDelayMs(121_000, 0), 61_000);
  });
  test("renews at half of the remaining lifetime when 120 s or less remain", () => {
    assert.equal(renewalDelayMs(120_000, 0), 60_000);
    assert.equal(renewalDelayMs(100_000, 0), 50_000);
    assert.equal(renewalDelayMs(10_000, 0), 5000);
  });
  test("never sooner than 1 s, even when already expired", () => {
    assert.equal(renewalDelayMs(1500, 0), 1000);
    assert.equal(renewalDelayMs(0, 5000), 1000);
  });
});

describe("RenewalScheduler", () => {
  test("renews at expiresAt - 60 s, adopts the new expiry and reschedules", async () => {
    const { clock, client, scheduler } = setup(300_000, () => renewedAt(clock, 300_000));
    scheduler.start();
    await clock.advance(239_999);
    assert.equal(client.calls.length, 0);
    await clock.advance(1);
    assert.deepEqual(
      client.calls.map((c) => [c.method, c.path, c.body]),
      [["POST", RENEW, undefined]],
    );
    // New expiry is 300 s after the renewal (t = 240 s), so the next renewal is at t = 480 s.
    await clock.advance(239_999);
    assert.equal(client.calls.length, 1);
    await clock.advance(1);
    assert.equal(client.calls.length, 2);
  });

  test("a short remaining lifetime renews at half of it", async () => {
    const { clock, client, scheduler } = setup(100_000, () => renewedAt(clock, 300_000));
    scheduler.start();
    await clock.advance(49_999);
    assert.equal(client.calls.length, 0);
    await clock.advance(1);
    assert.equal(client.calls.length, 1);
  });

  test("network errors and 5xx retry with 2 s, 4 s, 8 s backoff before expiry", async () => {
    const { clock, client, scheduler, terminals } = setup(300_000, (_call, index) => {
      if (index === 0) {
        throw networkError();
      }
      if (index === 1) {
        throw problem(503, "UNAVAILABLE");
      }
      if (index === 2) {
        throw networkError();
      }
      return renewedAt(clock, 300_000);
    });
    scheduler.start();
    await clock.advance(240_000);
    assert.equal(client.calls.length, 1);
    await clock.advance(1999);
    assert.equal(client.calls.length, 1);
    await clock.advance(1);
    assert.equal(client.calls.length, 2);
    await clock.advance(3999);
    assert.equal(client.calls.length, 2);
    await clock.advance(1);
    assert.equal(client.calls.length, 3);
    await clock.advance(7999);
    assert.equal(client.calls.length, 3);
    await clock.advance(1);
    assert.equal(client.calls.length, 4);
    assert.equal(terminals.length, 0);
    assert.ok(client.calls.every((c) => c.path === RENEW));
  });

  test("a successful renewal resets the backoff", async () => {
    const { clock, client, scheduler } = setup(300_000, (_call, index) => {
      if (index === 0 || index === 2) {
        throw networkError();
      }
      return renewedAt(clock, 300_000);
    });
    scheduler.start();
    await clock.advance(240_000); // call 0 fails
    await clock.advance(2000); // call 1 succeeds -> next in 240 s
    assert.equal(client.calls.length, 2);
    await clock.advance(240_000); // call 2 fails
    await clock.advance(1999);
    assert.equal(client.calls.length, 3);
    await clock.advance(1); // backoff restarted at 2 s
    assert.equal(client.calls.length, 4);
  });

  test("429 is retried like a transient failure", async () => {
    const { clock, client, scheduler, terminals } = setup(300_000, (_call, index) => {
      if (index === 0) {
        throw problem(429, "RATE_LIMITED");
      }
      return renewedAt(clock, 300_000);
    });
    scheduler.start();
    await clock.advance(242_000);
    assert.equal(client.calls.length, 2);
    assert.equal(terminals.length, 0);
  });

  test("gives up retrying once the session has expired and asks for a new admission", async () => {
    const { clock, client, scheduler, terminals } = setup(90_000, () => {
      throw networkError();
    });
    scheduler.start();
    await clock.advance(200_000);
    assert.ok(client.calls.length >= 2);
    assert.equal(terminals.length, 1);
    assert.equal(terminals[0].status, 409);
    assert.equal(terminals[0].code, "PLAYBACK_EXPIRED");
    const calls = client.calls.length;
    await clock.advance(600_000);
    assert.equal(client.calls.length, calls);
    assert.equal(clock.pending(), 0);
  });

  const terminalCases: Array<[number, string]> = [
    [409, "PLAYBACK_EXPIRED"],
    [409, "PLAYBACK_ENDED"],
    [409, "MEDIA_BINDING_CHANGED"],
    [409, "PLAYBACK_EXPIRING"],
    [409, "MEDIA_NOT_READY"],
    [403, "PREMIUM_REQUIRED"],
    [403, "EMAIL_VERIFICATION_REQUIRED"],
    [404, "NOT_FOUND"],
  ];
  for (const [status, code] of terminalCases) {
    test(`${status} ${code} is terminal: onTerminal once, no retry, no further timers`, async () => {
      const { clock, client, scheduler, terminals } = setup(300_000, () => {
        throw problem(status, code);
      });
      scheduler.start();
      await clock.advance(240_000);
      assert.equal(client.calls.length, 1);
      assert.equal(terminals.length, 1);
      assert.equal(terminals[0].status, status);
      assert.equal(terminals[0].code, code);
      assert.equal(clock.pending(), 0);
      await clock.advance(600_000);
      assert.equal(client.calls.length, 1);
    });
  }

  test("renewNow single-flights, reports renewed and reschedules from the new expiry", async () => {
    const { clock, client, scheduler } = setup(300_000, () => renewedAt(clock, 300_000));
    scheduler.start();
    const [a, b] = await Promise.all([scheduler.renewNow(), scheduler.renewNow()]);
    assert.equal(a, "renewed");
    assert.equal(b, "renewed");
    assert.equal(client.calls.length, 1);
    assert.equal(clock.pending(), 1);
    await clock.advance(239_999);
    assert.equal(client.calls.length, 1);
    await clock.advance(1);
    assert.equal(client.calls.length, 2);
  });

  test("renewNow reports failed on a transient error without retrying by itself", async () => {
    const { clock, client, scheduler, terminals } = setup(300_000, () => {
      throw networkError();
    });
    assert.equal(await scheduler.renewNow(), "failed");
    assert.equal(client.calls.length, 1);
    assert.equal(terminals.length, 0);
    assert.equal(clock.pending(), 0);
  });

  test("renewNow reports terminal and calls onTerminal on a definitive answer", async () => {
    const { client, scheduler, terminals } = setup(300_000, () => {
      throw problem(403, "PREMIUM_REQUIRED");
    });
    assert.equal(await scheduler.renewNow(), "terminal");
    assert.equal(terminals.length, 1);
    assert.equal(client.calls.length, 1);
    assert.equal(await scheduler.renewNow(), "closed");
    assert.equal(client.calls.length, 1);
  });

  test("close() cancels the timer and nothing is sent afterwards", async () => {
    const { clock, client, scheduler } = setup(300_000, () => renewedAt(clock, 300_000));
    scheduler.start();
    scheduler.close();
    assert.equal(clock.pending(), 0);
    await clock.advance(1_000_000);
    assert.equal(client.calls.length, 0);
    assert.equal(await scheduler.renewNow(), "closed");
    assert.equal(client.calls.length, 0);
  });
});

describe("RenewalScheduler with a wrong client clock", () => {
  const SERVER_START = Date.parse("2030-01-01T00:00:00Z");

  /** A server whose clock is `skewMs` behind the client's (positive skew = client ahead). */
  function skewed(skewMs: number) {
    const clock = new FakeClock(SERVER_START + skewMs);
    const serverNow = () => clock.current - skewMs;
    const answer = () => makeSession({ expiresAt: new Date(serverNow() + 300_000).toISOString() });
    const client = new FakeClient(() => answer());
    client.headers = () => ({ Date: new Date(serverNow()).toUTCString() });
    const terminals: CoreProblem[] = [];
    const first = answer();
    const scheduler = new RenewalScheduler(
      first,
      {
        client,
        now: clock.now,
        setTimeout: clock.setTimeout,
        clearTimeout: clock.clearTimeout,
        onTerminal: (p) => terminals.push(p),
      },
      expiryInClientTime(first.expiresAt, new Date(serverNow()).toUTCString(), clock.current),
    );
    return { clock, client, scheduler, terminals };
  }

  for (const [label, skew] of [
    ["+7 h", 7 * 3_600_000],
    ["-10 min", -10 * 60_000],
  ] as const) {
    test(`client clock ${label} vs server: renews at server-remaining minus 60 s, with no loop over several renewals`, async () => {
      const { clock, client, scheduler, terminals } = skewed(skew);
      scheduler.start();
      await clock.advance(239_999);
      assert.equal(client.calls.length, 0);
      await clock.advance(1);
      assert.equal(client.calls.length, 1);
      for (let round = 2; round <= 4; round += 1) {
        await clock.advance(239_999);
        assert.equal(client.calls.length, round - 1);
        await clock.advance(1);
        assert.equal(client.calls.length, round);
      }
      assert.equal(terminals.length, 0);
    });
  }

  test("without a Date header the raw expiry is used", async () => {
    const clock = new FakeClock(SERVER_START);
    const client = new FakeClient(() => makeSession({ expiresAt: new Date(clock.current + 300_000).toISOString() }));
    const scheduler = new RenewalScheduler(
      makeSession({ expiresAt: new Date(clock.current + 300_000).toISOString() }),
      { client, now: clock.now, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout, onTerminal: () => undefined },
    );
    scheduler.start();
    await clock.advance(239_999);
    assert.equal(client.calls.length, 0);
    await clock.advance(1);
    assert.equal(client.calls.length, 1);
  });

  test("an unparsable Date header falls back to the raw expiry", () => {
    assert.equal(expiryInClientTime("2030-01-01T00:05:00Z", "garbage", 5), Date.parse("2030-01-01T00:05:00Z"));
    assert.equal(expiryInClientTime("2030-01-01T00:05:00Z", null, 5), Date.parse("2030-01-01T00:05:00Z"));
  });

  test("expiryInClientTime counts the server-relative lifetime from the moment of receipt", () => {
    assert.equal(expiryInClientTime("2030-01-01T00:05:00Z", "Tue, 01 Jan 2030 00:00:00 GMT", 1_000), 301_000);
  });

  test("the delay never drops below 1 s and is half of a short remaining lifetime", () => {
    assert.equal(renewalDelayMs(1000, 0), 1000);
    assert.equal(renewalDelayMs(-5000, 0), 1000);
    // remaining 100 s: renew at half
    assert.equal(renewalDelayMs(100_000, 0), 50_000);
    // a bogus expiry only seconds away still waits a full second
    assert.equal(renewalDelayMs(2000, 0), 1000);
  });
});
