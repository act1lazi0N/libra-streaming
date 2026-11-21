import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { CoreNetworkError, CoreProblem } from "../core/errors.ts";
import type { ContentKind, PublicContent } from "../core/types.ts";
import { catalogPath, fetchCatalog, fetchChildren, fetchEntitlement, formatDuration, PAGE_SIZE } from "./catalog.ts";
import { describeDenial } from "./playability.ts";

function item(id: string, kind: ContentKind, publishedAt: string, ordinal: number | null = null): PublicContent {
  return {
    id,
    kind,
    parentId: null,
    ordinal,
    revision: 1,
    metadata: {
      title: id,
      description: "",
      genres: [],
      releaseYear: 2020,
      language: "vi",
      credits: [],
      imageReferences: [],
      accessTier: "FREE",
    },
    mediaReady: true,
    durationSeconds: 60,
    publishedAt,
  };
}

function fakeClient(responder: (path: string) => PublicContent[]) {
  const paths: string[] = [];
  return {
    paths,
    client: {
      request: async <T,>(_method: string, path: string): Promise<T> => {
        paths.push(path);
        return responder(path) as T;
      },
    },
  };
}

describe("catalog queries", () => {
  test("builds the query string with trimmed q, kind, limit and offset", () => {
    assert.equal(catalogPath(" matrix ", "MOVIE", 24), "/catalog?q=matrix&kind=MOVIE&limit=24&offset=24");
    assert.equal(catalogPath("   ", "SERIES", 0), "/catalog?kind=SERIES&limit=24&offset=0");
  });

  test("ALL queries MOVIE and SERIES separately and merges newest first", async () => {
    const f = fakeClient((path) =>
      path.includes("kind=MOVIE")
        ? [item("m1", "MOVIE", "2024-01-01T00:00:00Z")]
        : [item("s1", "SERIES", "2025-01-01T00:00:00Z")],
    );
    const result = await fetchCatalog(f.client, { q: "", kind: "ALL", pages: 1 });
    assert.deepEqual(result.items.map((i) => i.id), ["s1", "m1"]);
    assert.equal(f.paths.length, 2);
    assert.equal(result.hasMore, false);
  });

  test("never lists seasons or episodes even if Core returns them", async () => {
    const f = fakeClient(() => [
      item("e", "EPISODE", "2024-01-01T00:00:00Z"),
      item("s", "SEASON", "2024-01-01T00:00:00Z"),
      item("m", "MOVIE", "2024-01-01T00:00:00Z"),
    ]);
    const result = await fetchCatalog(f.client, { q: "", kind: "MOVIE", pages: 1 });
    assert.deepEqual(result.items.map((i) => i.id), ["m"]);
  });

  test("a full page signals more results and further pages use the next offset", async () => {
    const full = Array.from({ length: PAGE_SIZE }, (_, i) => item(`m${i}`, "MOVIE", "2024-01-01T00:00:00Z"));
    const f = fakeClient((path) => (path.includes("offset=0") ? full : [item("last", "MOVIE", "2023-01-01T00:00:00Z")]));
    const one = await fetchCatalog(f.client, { q: "", kind: "MOVIE", pages: 1 });
    assert.equal(one.hasMore, true);
    const two = await fetchCatalog(f.client, { q: "", kind: "MOVIE", pages: 2 });
    assert.equal(two.items.length, PAGE_SIZE + 1);
    assert.equal(two.hasMore, false);
    assert.ok(f.paths.some((p) => p.includes("offset=24")));
  });

  test("children come back ordered by ordinal and request limit 100", async () => {
    const f = fakeClient(() => [item("b", "EPISODE", "x", 2), item("a", "EPISODE", "x", 1)]);
    const children = await fetchChildren(f.client, "series id");
    assert.deepEqual(children.map((c) => c.id), ["a", "b"]);
    assert.equal(f.paths[0], "/catalog/series%20id/children?limit=100");
  });

  test("entitlement checks hit the profile-scoped endpoint every time", async () => {
    const f = fakeClient(() => []);
    await fetchEntitlement(f.client, "p1", "c1");
    await fetchEntitlement(f.client, "p1", "c1");
    assert.deepEqual(f.paths, ["/profiles/p1/entitlements/c1", "/profiles/p1/entitlements/c1"]);
  });

  test("formats durations in Vietnamese", () => {
    assert.equal(formatDuration(null), null);
    assert.equal(formatDuration(2700), "45 phút");
    assert.equal(formatDuration(7200), "2 giờ");
    assert.equal(formatDuration(7500), "2 giờ 5 phút");
  });
});

describe("playability mapping", () => {
  const problem = (status: number, code: string | null) => new CoreProblem(status, code, null, null);

  test("maps entitlement denials to viewer messages", () => {
    assert.deepEqual(describeDenial(problem(403, "PREMIUM_REQUIRED")), {
      kind: "denied",
      code: "PREMIUM_REQUIRED",
      message: "Cần gói Premium",
    });
    assert.equal(describeDenial(problem(403, "EMAIL_VERIFICATION_REQUIRED")).message, "Cần xác minh email");
    assert.equal(describeDenial(problem(409, "MEDIA_NOT_READY")).message, "Chưa sẵn sàng");
    assert.equal(describeDenial(problem(404, "NOT_FOUND")).kind, "denied");
    assert.equal(describeDenial(problem(409, "NOT_PLAYABLE_CONTENT")).kind, "denied");
    assert.equal(describeDenial(problem(403, "SOMETHING_ELSE")).kind, "denied");
  });

  test("transient and unknown failures are not presented as denials", () => {
    assert.equal(describeDenial(problem(503, null)).kind, "error");
    assert.equal(describeDenial(problem(409, "OTHER")).kind, "error");
    assert.equal(describeDenial(new CoreNetworkError()).kind, "error");
    assert.equal(describeDenial(new Error("x")).kind, "error");
  });
});
