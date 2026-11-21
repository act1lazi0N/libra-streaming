import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { fetchContinueWatching, fetchNextEpisode, progressFraction } from "./history.ts";
import { FakeClient } from "./test-kit.ts";

describe("continue watching and next episode requests", () => {
  test("continue watching asks for the profile's 20 most recent items", async () => {
    const client = new FakeClient(() => []);
    await fetchContinueWatching(client, "p/1");
    assert.deepEqual(
      client.calls.map((c) => [c.method, c.path]),
      [["GET", "/profiles/p%2F1/continue-watching?limit=20"]],
    );
  });

  test("next episode returns the id, or null when Core says there is none", async () => {
    const client = new FakeClient((_call, index) => (index === 0 ? { contentId: "e2" } : { contentId: null }));
    assert.equal(await fetchNextEpisode(client, "p1", "e1"), "e2");
    assert.equal(await fetchNextEpisode(client, "p1", "e2"), null);
    assert.deepEqual(
      client.calls.map((c) => c.path),
      ["/profiles/p1/next-episode/e1", "/profiles/p1/next-episode/e2"],
    );
  });

  test("progress fraction is position / duration clamped to [0, 1]", () => {
    assert.equal(progressFraction({ positionMs: 30_000, durationMs: 120_000 }), 0.25);
    assert.equal(progressFraction({ positionMs: 500_000, durationMs: 120_000 }), 1);
    assert.equal(progressFraction({ positionMs: -5, durationMs: 120_000 }), 0);
    assert.equal(progressFraction({ positionMs: 5, durationMs: 0 }), 0);
  });
});
