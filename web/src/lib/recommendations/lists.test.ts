import assert from "node:assert/strict";
import { describe, test } from "node:test";
import type { PublicContent } from "../core/types.ts";
import {
  fetchList,
  listPath,
  REASON_LABELS,
  type RecommendationList,
  rowView,
  ROW_LIMIT,
} from "./lists.ts";

const PROFILE = "4a4a4a4a-4444-4444-8444-444444444444";
const SEED = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

function content(id: string): PublicContent {
  return {
    id,
    kind: "MOVIE",
    parentId: null,
    ordinal: null,
    revision: 1,
    metadata: {
      title: id,
      description: "",
      genres: ["drama"],
      releaseYear: 2020,
      language: "vi",
      credits: [],
      imageReferences: [],
      accessTier: "PREMIUM",
    },
    mediaReady: true,
    durationSeconds: 60,
    publishedAt: "2026-11-01T00:00:00Z",
  };
}

function list(overrides: Partial<RecommendationList> = {}): RecommendationList {
  return {
    source: "ANALYTICS",
    reason: null,
    list: "personal",
    algorithmVersion: "rules-v1",
    updatedAt: "2026-11-23T08:00:00Z",
    items: [{ content: content("m1"), reason: "GENRE_INTEREST" }, { content: content("m2"), reason: "POPULAR" }],
    ...overrides,
  };
}

describe("request", () => {
  test("paths per list; only similar carries the seed", () => {
    assert.equal(listPath(PROFILE, "personal"), `/profiles/${PROFILE}/recommendations/lists/personal?limit=${ROW_LIMIT}`);
    assert.equal(listPath(PROFILE, "new", undefined, 5), `/profiles/${PROFILE}/recommendations/lists/new?limit=5`);
    assert.equal(listPath(PROFILE, "popular", SEED), `/profiles/${PROFILE}/recommendations/lists/popular?limit=${ROW_LIMIT}`);
    assert.equal(listPath(PROFILE, "similar", SEED),
      `/profiles/${PROFILE}/recommendations/lists/similar?limit=${ROW_LIMIT}&contentId=${SEED}`);
    assert.throws(() => listPath(PROFILE, "similar"));
  });

  test("fetch is a GET with the abort signal", async () => {
    const calls: unknown[][] = [];
    const signal = new AbortController().signal;
    const body = list();
    const result = await fetchList({
      request: async <T,>(...args: unknown[]): Promise<T> => {
        calls.push(args);
        return body as T;
      },
    }, PROFILE, "personal", undefined, signal);
    assert.equal(result, body);
    assert.deepEqual(calls, [["GET", listPath(PROFILE, "personal"), { signal }]]);
  });
});

describe("row view", () => {
  test("analytics items keep their reasons and order", () => {
    const view = rowView(list());
    assert.equal(view.kind, "items");
    if (view.kind === "items") {
      assert.equal(view.fallback, false);
      assert.deepEqual(view.items.map((item) => [item.content.id, item.reason]), [["m1", "GENRE_INTEREST"], ["m2", "POPULAR"]]);
    }
  });

  test("a fallback is shown as such", () => {
    const view = rowView(list({ source: "FALLBACK", reason: "UNAVAILABLE", algorithmVersion: null, updatedAt: null,
      items: [{ content: content("n1"), reason: "NEWLY_PUBLISHED" }] }));
    assert.equal(view.kind === "items" && view.fallback, true);
  });

  test("an empty list is hidden, also as a fallback", () => {
    assert.deepEqual(rowView(list({ items: [] })), { kind: "hidden" });
    assert.deepEqual(rowView(list({ source: "FALLBACK", reason: "UNAVAILABLE", items: [] })), { kind: "hidden" });
  });

  test("an unknown reason is never shown", () => {
    const odd = { content: content("x"), reason: "SPONSORED" } as unknown as RecommendationList["items"][number];
    const view = rowView(list({ items: [odd, { content: content("m1"), reason: "SIMILAR_GENRES" }] }));
    assert.equal(view.kind === "items" && view.items.length, 1);
    assert.deepEqual(rowView(list({ items: [odd] })), { kind: "hidden" });
    const inherited = { content: content("y"), reason: "toString" } as unknown as RecommendationList["items"][number];
    assert.deepEqual(rowView(list({ items: [inherited] })), { kind: "hidden" });
  });

  test("every reason has a label", () => {
    assert.deepEqual(Object.keys(REASON_LABELS).sort(), ["GENRE_INTEREST", "NEWLY_PUBLISHED", "POPULAR", "SIMILAR_GENRES"]);
  });
});
