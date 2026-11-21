import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  createCoreClient,
  AUTH_HINT_KEY,
  REFRESHED_AT_KEY,
  type AuthMessage,
  type ChannelLike,
  type CoreClientDeps,
  type StorageLike,
} from "./client.ts";
import { CoreNetworkError, CoreProblem } from "./errors.ts";

interface Call {
  method: string;
  path: string;
  headers: Record<string, string>;
  body: string | undefined;
  init: RequestInit;
}

type Handler = (call: Call, index: number) => Response | Promise<Response>;

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function problem(status: number, code: string): Response {
  return json(status, { type: "about:blank", title: "t", status, detail: "secret-detail", code, correlationId: "c-1" }, {
    "Content-Type": "application/problem+json",
  });
}

function memoryStorage(initial: Record<string, string> = {}): StorageLike & { data: Record<string, string> } {
  const data = { ...initial };
  return {
    data,
    getItem: (key) => (key in data ? data[key] : null),
    setItem: (key, value) => {
      data[key] = value;
    },
    removeItem: (key) => {
      delete data[key];
    },
  };
}

function harness(handler: Handler, overrides: Partial<CoreClientDeps> = {}) {
  const calls: Call[] = [];
  let clock = 1000;
  let signedOut = 0;
  let csrfCounter = 0;
  const fetchFake = async (input: string, init: RequestInit): Promise<Response> => {
    const path = input.replace("/api/core/v1", "");
    const call: Call = {
      method: init.method ?? "GET",
      path,
      headers: (init.headers ?? {}) as Record<string, string>,
      body: init.body as string | undefined,
      init,
    };
    calls.push(call);
    // Yield so concurrent callers genuinely interleave.
    await Promise.resolve();
    await Promise.resolve();
    if (path === "/auth/csrf") {
      csrfCounter += 1;
      return json(200, { headerName: "X-CSRF-TOKEN", token: `tok-${csrfCounter}` });
    }
    return handler(call, calls.length - 1);
  };
  const client = createCoreClient({
    fetch: fetchFake,
    now: () => (clock += 10),
    onSignedOut: () => {
      signedOut += 1;
    },
    ...overrides,
  });
  return {
    client,
    calls,
    paths: () => calls.map((c) => `${c.method} ${c.path}`),
    signedOut: () => signedOut,
    setClock: (value: number) => {
      clock = value;
    },
  };
}

describe("core client CSRF handling", () => {
  test("single-flights concurrent token fetches", async () => {
    const h = harness(() => json(200, { ok: true }));
    await Promise.all([
      h.client.request("POST", "/profiles", { body: { name: "a" } }),
      h.client.request("POST", "/profiles", { body: { name: "b" } }),
      h.client.request("POST", "/profiles", { body: { name: "c" } }),
    ]);
    assert.equal(h.paths().filter((p) => p === "GET /auth/csrf").length, 1);
    assert.equal(h.calls.filter((c) => c.path === "/profiles").length, 3);
  });

  test("attaches the token only to unsafe methods", async () => {
    const h = harness(() => json(200, { ok: true }));
    await h.client.request("GET", "/profiles");
    assert.equal(h.paths().includes("GET /auth/csrf"), false);
    assert.equal(h.calls[0].headers["X-CSRF-TOKEN"], undefined);
    await h.client.request("POST", "/profiles", { body: { name: "x" } });
    const post = h.calls.find((c) => c.method === "POST");
    assert.equal(post?.headers["X-CSRF-TOKEN"], "tok-1");
    assert.equal(post?.headers["Content-Type"], "application/json");
    assert.equal(post?.body, JSON.stringify({ name: "x" }));
    await h.client.request("DELETE", "/profiles/1");
    const del = h.calls.find((c) => c.method === "DELETE");
    assert.equal(del?.headers["X-CSRF-TOKEN"], "tok-1");
    assert.equal(h.client.cachedCsrf()?.token, "tok-1");
  });

  test("always sends same-origin credentials, no-store and redirect error", async () => {
    const h = harness(() => json(200, {}));
    await h.client.request("GET", "/me");
    assert.equal(h.calls[0].init.credentials, "same-origin");
    assert.equal(h.calls[0].init.cache, "no-store");
    assert.equal(h.calls[0].init.redirect, "error");
  });

  test("retries once with a fresh token after 403 ACCESS_DENIED", async () => {
    let attempts = 0;
    const h = harness((call) => {
      if (call.path !== "/profiles") return json(200, {});
      attempts += 1;
      return attempts === 1 ? problem(403, "ACCESS_DENIED") : json(201, { id: "p" });
    });
    const result = await h.client.request<{ id: string }>("POST", "/profiles", { body: {} });
    assert.equal(result.id, "p");
    const posts = h.calls.filter((c) => c.path === "/profiles");
    assert.deepEqual(posts.map((c) => c.headers["X-CSRF-TOKEN"]), ["tok-1", "tok-2"]);
    assert.equal(h.paths().filter((p) => p === "GET /auth/csrf").length, 2);
  });

  test("does not retry a second stale-CSRF failure", async () => {
    const h = harness(() => problem(403, "ACCESS_DENIED"));
    await assert.rejects(h.client.request("POST", "/profiles", { body: {} }), (error: unknown) => {
      return error instanceof CoreProblem && error.status === 403 && error.code === "ACCESS_DENIED";
    });
    assert.equal(h.calls.filter((c) => c.path === "/profiles").length, 2);
  });

  test("does not treat other 403 codes or safe requests as stale CSRF", async () => {
    const h = harness(() => problem(403, "PREMIUM_REQUIRED"));
    await assert.rejects(h.client.request("POST", "/x", { body: {} }));
    assert.equal(h.calls.filter((c) => c.path === "/x").length, 1);
    const g = harness(() => problem(403, "ACCESS_DENIED"));
    await assert.rejects(g.client.request("GET", "/x"));
    assert.equal(g.calls.length, 1);
  });

  test("cachedCsrfOnly never fetches a token", async () => {
    const h = harness(() => json(204, null));
    await assert.rejects(
      h.client.request("POST", "/playback/sessions/1/progress", { cachedCsrfOnly: true, keepalive: true }),
      CoreNetworkError,
    );
    assert.equal(h.calls.length, 0);
  });

  test("a remote signed-out message invokes onRemoteSignedOut and never onSignedOut", () => {
    const channel: ChannelLike = { postMessage: () => undefined, onmessage: null };
    let remote = 0;
    const h = harness(() => json(200, {}), { channel, onRemoteSignedOut: () => void (remote += 1) });
    channel.onmessage?.({ data: { type: "signed-out" } });
    assert.equal(remote, 1);
    assert.equal(h.signedOut(), 0);
    assert.equal(h.calls.length, 0);
    channel.onmessage?.({ data: { type: "signed-out" } });
    assert.equal(remote, 2);
    assert.equal(h.signedOut(), 0);
  });

  test("a local refresh failure still ends the session via onSignedOut, not the remote handler", async () => {
    const channel: ChannelLike = { postMessage: () => undefined, onmessage: null };
    let remote = 0;
    const h = harness(
      (call) =>
        call.path === "/auth/refresh" ? problem(401, "INVALID_SESSION") : problem(401, "AUTHENTICATION_REQUIRED"),
      { channel, onRemoteSignedOut: () => void (remote += 1) },
    );
    await assert.rejects(h.client.request("GET", "/profiles"), CoreProblem);
    assert.equal(h.signedOut(), 1);
    assert.equal(remote, 0);
  });
});

describe("core client refresh", () => {
  function refreshingHandler(state: { rotated: boolean; refreshes: number }): Handler {
    return (call) => {
      if (call.path === "/auth/refresh") {
        state.refreshes += 1;
        state.rotated = true;
        return json(200, { accessExpiresAt: "x", sessionExpiresAt: "y" });
      }
      return state.rotated ? json(200, { path: call.path }) : problem(401, "AUTHENTICATION_REQUIRED");
    };
  }

  test("five concurrent 401s trigger exactly one refresh and each request is retried once", async () => {
    const state = { rotated: false, refreshes: 0 };
    const h = harness(refreshingHandler(state));
    const results = await Promise.all(
      ["/a", "/b", "/c", "/d", "/e"].map((p) => h.client.request<{ path: string }>("GET", p)),
    );
    assert.deepEqual(results.map((r) => r.path), ["/a", "/b", "/c", "/d", "/e"]);
    assert.equal(state.refreshes, 1);
    for (const p of ["/a", "/b", "/c", "/d", "/e"]) {
      assert.equal(h.calls.filter((c) => c.path === p).length, 2, `${p} sent twice`);
    }
    assert.equal(h.signedOut(), 0);
  });

  test("refresh and the retried request each use a freshly fetched CSRF token", async () => {
    const state = { rotated: false, refreshes: 0 };
    const h = harness(refreshingHandler(state));
    await h.client.request("POST", "/x", { body: {} });
    const tokens = h.calls
      .filter((c) => c.method === "POST")
      .map((c) => `${c.path}:${c.headers["X-CSRF-TOKEN"]}`);
    assert.deepEqual(tokens, ["/x:tok-1", "/auth/refresh:tok-2", "/x:tok-3"]);
  });

  test("skips refresh when another tab already rotated after the request started", async () => {
    const storage = memoryStorage();
    let first = true;
    const h = harness(
      (call) => {
        if (first) {
          first = false;
          // Simulate the sibling tab finishing its refresh while this request was in flight.
          storage.data[REFRESHED_AT_KEY] = "99999";
          return problem(401, "AUTHENTICATION_REQUIRED");
        }
        return json(200, { path: call.path });
      },
      { storage },
    );
    const result = await h.client.request<{ path: string }>("GET", "/me");
    assert.equal(result.path, "/me");
    assert.equal(h.paths().includes("POST /auth/refresh"), false);
    assert.equal(h.calls.length, 2);
  });

  test("refreshes when the stored timestamp is older than the request start", async () => {
    const storage = memoryStorage({ [REFRESHED_AT_KEY]: "1" });
    const state = { rotated: false, refreshes: 0 };
    const h = harness(refreshingHandler(state), { storage });
    await h.client.request("GET", "/me");
    assert.equal(state.refreshes, 1);
    assert.ok(Number(storage.data[REFRESHED_AT_KEY]) > 1000);
  });

  test("serializes refresh through the injected lock", async () => {
    const names: string[] = [];
    const state = { rotated: false, refreshes: 0 };
    const h = harness(refreshingHandler(state), {
      locks: {
        request: async (name, fn) => {
          names.push(name);
          return fn();
        },
      },
    });
    await h.client.request("GET", "/me");
    assert.deepEqual(names, ["libra-auth-refresh"]);
  });

  test("refresh 401 signs out once, broadcasts, throws the original problem and does not loop", async () => {
    const messages: AuthMessage[] = [];
    const channel: ChannelLike = { postMessage: (m) => void messages.push(m), onmessage: null };
    const h = harness(
      (call) => (call.path === "/auth/refresh" ? problem(401, "INVALID_SESSION") : problem(401, "AUTHENTICATION_REQUIRED")),
      { channel },
    );
    const outcomes = await Promise.allSettled([h.client.request("GET", "/a"), h.client.request("GET", "/b")]);
    for (const outcome of outcomes) {
      assert.equal(outcome.status, "rejected");
      const reason = (outcome as PromiseRejectedResult).reason;
      assert.ok(reason instanceof CoreProblem);
      assert.equal(reason.code, "AUTHENTICATION_REQUIRED");
    }
    assert.equal(h.signedOut(), 1);
    assert.deepEqual(messages, [{ type: "signed-out" }]);
    assert.equal(h.calls.filter((c) => c.path === "/auth/refresh").length, 1);
    assert.equal(h.calls.filter((c) => c.path === "/a").length, 1);
  });

  test("a retried request that still returns 401 is not refreshed again", async () => {
    const h = harness((call) =>
      call.path === "/auth/refresh" ? json(200, {}) : problem(401, "AUTHENTICATION_REQUIRED"),
    );
    await assert.rejects(h.client.request("GET", "/a"), (e: unknown) => e instanceof CoreProblem && e.status === 401);
    assert.equal(h.calls.filter((c) => c.path === "/auth/refresh").length, 1);
    assert.equal(h.calls.filter((c) => c.path === "/a").length, 2);
    assert.equal(h.signedOut(), 0);
  });

  test("a transient refresh failure does not sign the user out", async () => {
    const h = harness((call) =>
      call.path === "/auth/refresh" ? problem(503, "UNAVAILABLE") : problem(401, "AUTHENTICATION_REQUIRED"),
    );
    await assert.rejects(h.client.request("GET", "/a"), (e: unknown) => e instanceof CoreProblem && e.status === 503);
    assert.equal(h.signedOut(), 0);
  });

  test("login 401 and other /auth/ paths never refresh", async () => {
    const h = harness(() => problem(401, "INVALID_CREDENTIALS"));
    await assert.rejects(h.client.login("a@b.c", "pw"), (e: unknown) => e instanceof CoreProblem && e.code === "INVALID_CREDENTIALS");
    assert.equal(h.paths().filter((p) => p === "POST /auth/refresh").length, 0);
    await assert.rejects(h.client.logout());
    assert.equal(h.paths().filter((p) => p === "POST /auth/refresh").length, 0);
    assert.equal(h.signedOut(), 0);
  });

  test("auth:false and non-refreshable 401 codes never refresh", async () => {
    const h = harness(() => problem(401, "AUTHENTICATION_REQUIRED"));
    await assert.rejects(h.client.request("GET", "/me", { auth: false }));
    assert.equal(h.calls.length, 1);
    const g = harness(() => problem(401, "INVALID_SESSION"));
    await assert.rejects(g.client.request("GET", "/me"));
    assert.equal(g.calls.length, 1);
  });
});

describe("core client CSRF lifecycle and responses", () => {
  test("invalidates the cached token after login, logout and logout-all", async () => {
    const h = harness((call) => (call.path === "/auth/login" ? json(200, {}) : new Response(null, { status: 204 })));
    await h.client.login("a@b.c", "pw");
    assert.equal(h.client.cachedCsrf(), null);
    await h.client.logout();
    assert.equal(h.client.cachedCsrf(), null);
    await h.client.logoutAll();
    assert.equal(h.client.cachedCsrf(), null);
    // Three unsafe calls, each needed its own fresh token.
    assert.equal(h.paths().filter((p) => p === "GET /auth/csrf").length, 3);
  });

  test("invalidates the cached token even when logout fails", async () => {
    const h = harness(() => problem(500, "INTERNAL"));
    await assert.rejects(h.client.logout());
    assert.equal(h.client.cachedCsrf(), null);
  });

  test("invalidates the cached token after a successful refresh", async () => {
    const h = harness(() => json(200, {}));
    await h.client.request("POST", "/x", { body: {} });
    assert.equal(h.client.cachedCsrf()?.token, "tok-1");
    await h.client.refresh();
    assert.equal(h.client.cachedCsrf(), null);
  });

  test("maps problems without leaking bodies and handles non-JSON errors", async () => {
    const h = harness((call) =>
      call.path === "/html"
        ? new Response("<html>oops</html>", { status: 502, headers: { "Content-Type": "text/html" } })
        : json(429, { code: "RATE_LIMITED", correlationId: "abc" }, { "Retry-After": "7", "Content-Type": "application/problem+json" }),
    );
    await assert.rejects(h.client.request("GET", "/html"), (e: unknown) => {
      assert.ok(e instanceof CoreProblem);
      assert.equal(e.status, 502);
      assert.equal(e.code, null);
      assert.equal(e.message.includes("oops"), false);
      return true;
    });
    await assert.rejects(h.client.request("GET", "/limited"), (e: unknown) => {
      assert.ok(e instanceof CoreProblem);
      assert.equal(e.code, "RATE_LIMITED");
      assert.equal(e.correlationId, "abc");
      assert.equal(e.retryAfterSeconds, 7);
      return true;
    });
  });

  test("returns undefined for 204 and parses JSON otherwise; network failures become CoreNetworkError", async () => {
    const h = harness((call) => (call.path === "/none" ? new Response(null, { status: 204 }) : json(200, { a: 1 })));
    assert.equal(await h.client.request("GET", "/none"), undefined);
    assert.deepEqual(await h.client.request("GET", "/some"), { a: 1 });
    const failing = createCoreClient({
      fetch: () => Promise.reject(new TypeError("boom")),
      now: () => 1,
      onSignedOut: () => undefined,
    });
    await assert.rejects(failing.request("GET", "/me"), CoreNetworkError);
  });

  test("lets aborts through untouched", async () => {
    const abort = new DOMException("aborted", "AbortError");
    const client = createCoreClient({
      fetch: () => Promise.reject(abort),
      now: () => 1,
      onSignedOut: () => undefined,
    });
    await assert.rejects(client.request("GET", "/me"), (e: unknown) => e === abort);
  });
});

describe("core client broadcast channel", () => {
  test("signed-out messages invoke onSignedOut without network calls; signed-in invokes onSignedIn", () => {
    const channel: ChannelLike = { postMessage: () => undefined, onmessage: null };
    let signedIn = 0;
    const h = harness(() => json(200, {}), { channel, onSignedIn: () => void (signedIn += 1) });
    channel.onmessage?.({ data: { type: "signed-out" } });
    channel.onmessage?.({ data: { type: "unknown" } });
    channel.onmessage?.({ data: null });
    assert.equal(h.signedOut(), 1);
    channel.onmessage?.({ data: { type: "signed-in" } });
    assert.equal(signedIn, 1);
    assert.equal(h.calls.length, 0);
  });

  test("a remote signed-out message invokes onRemoteSignedOut and never onSignedOut", () => {
    const channel: ChannelLike = { postMessage: () => undefined, onmessage: null };
    let remote = 0;
    const h = harness(() => json(200, {}), { channel, onRemoteSignedOut: () => void (remote += 1) });
    channel.onmessage?.({ data: { type: "signed-out" } });
    assert.equal(remote, 1);
    assert.equal(h.signedOut(), 0);
    assert.equal(h.calls.length, 0);
    channel.onmessage?.({ data: { type: "signed-out" } });
    assert.equal(remote, 2);
    assert.equal(h.signedOut(), 0);
  });

  test("a local refresh failure still ends the session via onSignedOut, not the remote handler", async () => {
    const channel: ChannelLike = { postMessage: () => undefined, onmessage: null };
    let remote = 0;
    const h = harness(
      (call) =>
        call.path === "/auth/refresh" ? problem(401, "INVALID_SESSION") : problem(401, "AUTHENTICATION_REQUIRED"),
      { channel, onRemoteSignedOut: () => void (remote += 1) },
    );
    await assert.rejects(h.client.request("GET", "/profiles"), CoreProblem);
    assert.equal(h.signedOut(), 1);
    assert.equal(remote, 0);
  });
});

describe("core client response headers (onHeaders)", () => {
  test("receives the headers of a successful response only", async () => {
    const seen: Array<string | null> = [];
    const h = harness((call) =>
      call.path === "/ok" ? json(200, {}, { Date: "Tue, 01 Jan 2030 00:00:00 GMT" }) : problem(500, "BOOM"),
    );
    await h.client.request("GET", "/ok", { onHeaders: (headers) => seen.push(headers.get("Date")) });
    await assert.rejects(h.client.request("GET", "/bad", { onHeaders: (headers) => seen.push(headers.get("Date")) }));
    assert.deepEqual(seen, ["Tue, 01 Jan 2030 00:00:00 GMT"]);
  });

  test("a throwing observer does not fail the request", async () => {
    const h = harness(() => json(200, { ok: true }));
    const result = await h.client.request<{ ok: boolean }>("GET", "/x", {
      onHeaders: () => {
        throw new Error("observer");
      },
    });
    assert.deepEqual(result, { ok: true });
  });
});

describe("core client auth hint and anonymous /me", () => {
  const account = { id: "a", email: "e", displayName: "d", role: "USER", emailVerified: true };
  type TestClient = ReturnType<typeof harness>["client"];

  test("hasAuthHint is false without the hint, true with it, and true when storage cannot tell", () => {
    assert.equal(harness(() => json(200, {}), { storage: memoryStorage() }).client.hasAuthHint(), false);
    assert.equal(
      harness(() => json(200, {}), { storage: memoryStorage({ [AUTH_HINT_KEY]: "1" }) }).client.hasAuthHint(),
      true,
    );
    assert.equal(harness(() => json(200, {})).client.hasAuthHint(), true);
  });

  test("the hint is set after login, after an authenticated /me and after a manual refresh", async () => {
    const acts: Array<(client: TestClient) => Promise<unknown>> = [
      (client) => client.login("a@b.c", "pw"),
      (client) => client.me(),
      (client) => client.refresh(),
    ];
    for (const act of acts) {
      const storage = memoryStorage();
      const h = harness(() => json(200, account), { storage });
      await act(h.client);
      assert.equal(storage.data[AUTH_HINT_KEY], "1");
    }
  });

  test("the hint is set when an automatic refresh succeeds", async () => {
    const storage = memoryStorage();
    let first = true;
    const h = harness(
      (call) => {
        if (call.path === "/auth/refresh") {
          return json(200, {});
        }
        if (first) {
          first = false;
          return problem(401, "AUTHENTICATION_REQUIRED");
        }
        return json(200, account);
      },
      { storage },
    );
    await h.client.me();
    assert.equal(storage.data[AUTH_HINT_KEY], "1");
  });

  test("the hint is removed after logout, even when logout answers 401, and after logout-all", async () => {
    for (const respond of [() => new Response(null, { status: 204 }), () => problem(401, "AUTHENTICATION_REQUIRED")]) {
      const storage = memoryStorage({ [AUTH_HINT_KEY]: "1" });
      const h = harness(respond, { storage });
      await h.client.logout().catch(() => undefined);
      assert.equal(AUTH_HINT_KEY in storage.data, false);
    }
    const storage = memoryStorage({ [AUTH_HINT_KEY]: "1" });
    const h = harness(() => new Response(null, { status: 204 }), { storage });
    await h.client.logoutAll();
    assert.equal(AUTH_HINT_KEY in storage.data, false);
  });

  test("a failed logout (network) keeps the hint", async () => {
    const storage = memoryStorage({ [AUTH_HINT_KEY]: "1" });
    const h = harness(
      () => {
        throw new TypeError("offline");
      },
      { storage },
    );
    await assert.rejects(h.client.logout());
    assert.equal(storage.data[AUTH_HINT_KEY], "1");
  });

  test("the hint is removed on a refresh 401 (local sign-out) and on a remote signed-out message", async () => {
    const storage = memoryStorage({ [AUTH_HINT_KEY]: "1" });
    const h = harness(
      (call) =>
        call.path === "/auth/refresh" ? problem(401, "INVALID_SESSION") : problem(401, "AUTHENTICATION_REQUIRED"),
      { storage },
    );
    await assert.rejects(h.client.request("GET", "/profiles"));
    assert.equal(AUTH_HINT_KEY in storage.data, false);

    const remoteStorage = memoryStorage({ [AUTH_HINT_KEY]: "1" });
    const channel: ChannelLike = { postMessage: () => undefined, onmessage: null };
    harness(() => json(200, {}), { storage: remoteStorage, channel, onRemoteSignedOut: () => undefined });
    channel.onmessage?.({ data: { type: "signed-out" } });
    assert.equal(AUTH_HINT_KEY in remoteStorage.data, false);
  });

  test("an initial /me without the hint (refresh:false) never refreshes on 401", async () => {
    const h = harness(() => problem(401, "AUTHENTICATION_REQUIRED"), { storage: memoryStorage() });
    await assert.rejects(
      h.client.me({ refresh: h.client.hasAuthHint() }),
      (e) => e instanceof CoreProblem && e.status === 401,
    );
    assert.deepEqual(h.paths(), ["GET /me"]);
    assert.equal(h.signedOut(), 0);
  });

  test("with the hint the initial /me refreshes on 401 as before", async () => {
    const storage = memoryStorage({ [AUTH_HINT_KEY]: "1" });
    let meCalls = 0;
    const h = harness(
      (call) => {
        if (call.path === "/auth/refresh") {
          return json(200, {});
        }
        meCalls += 1;
        return meCalls === 1 ? problem(401, "AUTHENTICATION_REQUIRED") : json(200, account);
      },
      { storage },
    );
    await h.client.me({ refresh: h.client.hasAuthHint() });
    assert.deepEqual(h.paths(), ["GET /me", "GET /auth/csrf", "POST /auth/refresh", "GET /me"]);
  });

  test("a failed /me with refresh:false leaves a present hint untouched", async () => {
    const storage = memoryStorage({ [AUTH_HINT_KEY]: "1" });
    const h = harness(() => problem(401, "AUTHENTICATION_REQUIRED"), { storage });
    await assert.rejects(h.client.me({ refresh: false }));
    assert.equal(storage.data[AUTH_HINT_KEY], "1");
  });
});

describe("core client manual refresh", () => {
  test("goes through the lock and single-flight: concurrent calls share one /auth/refresh", async () => {
    const locked: string[] = [];
    const locks = {
      request: async <T,>(name: string, fn: () => Promise<T>): Promise<T> => {
        locked.push(name);
        return fn();
      },
    };
    const h = harness(() => json(200, {}), { locks });
    await Promise.all([h.client.refresh(), h.client.refresh(), h.client.refresh()]);
    assert.equal(h.paths().filter((p) => p === "POST /auth/refresh").length, 1);
    assert.deepEqual(locked, ["libra-auth-refresh"]);
  });

  test("a refresh 401 signs out once and throws", async () => {
    const h = harness(() => problem(401, "INVALID_SESSION"));
    await assert.rejects(h.client.refresh(), (e) => e instanceof CoreProblem && e.status === 401);
    assert.equal(h.signedOut(), 1);
  });
});
