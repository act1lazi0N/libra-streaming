import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { startScoped, type ScopedResult } from "./scoped.ts";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const flush = () => new Promise<void>((resolve) => setImmediate(resolve));

describe("startScoped", () => {
  test("delivers the result while the generation is unchanged", async () => {
    const d = deferred<string>();
    const results: ScopedResult<string>[] = [];
    startScoped({
      profileId: "p1",
      generation: 1,
      currentGeneration: () => 1,
      fetcher: () => d.promise,
      onResult: (r) => results.push(r),
    });
    d.resolve("ok");
    await flush();
    assert.deepEqual(results, [{ status: "success", data: "ok" }]);
  });

  test("passes the captured profile id and an abort signal to the fetcher", () => {
    let seen: { id: string; signal: AbortSignal } | null = null;
    startScoped({
      profileId: "p9",
      generation: 0,
      currentGeneration: () => 0,
      fetcher: (id, signal) => {
        seen = { id, signal };
        return new Promise(() => undefined);
      },
      onResult: () => undefined,
    });
    assert.equal(seen!.id, "p9");
    assert.equal(seen!.signal.aborted, false);
  });

  test("drops a late response from the old profile after a switch", async () => {
    const d = deferred<string>();
    let generation = 1;
    const results: ScopedResult<string>[] = [];
    startScoped({
      profileId: "old",
      generation,
      currentGeneration: () => generation,
      fetcher: () => d.promise,
      onResult: (r) => results.push(r),
    });
    generation = 2;
    d.resolve("old-profile-data");
    await flush();
    assert.deepEqual(results, []);
  });

  test("drops a late error from the old profile too", async () => {
    const d = deferred<string>();
    let generation = 1;
    const results: ScopedResult<string>[] = [];
    startScoped({
      profileId: "old",
      generation,
      currentGeneration: () => generation,
      fetcher: () => d.promise,
      onResult: (r) => results.push(r),
    });
    generation = 2;
    d.reject(new Error("late"));
    await flush();
    assert.deepEqual(results, []);
  });

  test("cancel aborts the in-flight request and suppresses its result", async () => {
    const d = deferred<string>();
    let signal!: AbortSignal;
    const results: ScopedResult<string>[] = [];
    const cancel = startScoped({
      profileId: "p",
      generation: 1,
      currentGeneration: () => 1,
      fetcher: (_id, s) => {
        signal = s;
        return d.promise;
      },
      onResult: (r) => results.push(r),
    });
    cancel();
    assert.equal(signal.aborted, true);
    d.resolve("late");
    await flush();
    assert.deepEqual(results, []);
  });

  test("reports genuine errors but ignores abort errors", async () => {
    const results: ScopedResult<string>[] = [];
    startScoped({
      profileId: "p",
      generation: 1,
      currentGeneration: () => 1,
      fetcher: () => Promise.reject(new Error("boom")),
      onResult: (r) => results.push(r),
    });
    startScoped({
      profileId: "p",
      generation: 1,
      currentGeneration: () => 1,
      fetcher: () => Promise.reject(new DOMException("x", "AbortError")),
      onResult: (r) => results.push(r),
    });
    await flush();
    assert.equal(results.length, 1);
    assert.equal(results[0].status, "error");
  });
});
