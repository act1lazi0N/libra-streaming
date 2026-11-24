import { expect, test, type Browser, type Page } from "@playwright/test";
import {
  apiSession, loginThroughUi, newContext, poll, premiumProfiles, seed, switchProfile, ui, type Api,
} from "../ra03/support";
import { recordRa05Pass, resetRa05Ledger, writeRa05EvidenceWhenComplete } from "./support";

// RA05.3/RA05.4: recommendation lists with reasons on the real stack, after the RA03 suite produced playback.
// Every list the page shows is compared with Core's own answer for the same profile. Real throughout except test 29
// (MOCKED, named).

test.describe.configure({ mode: "serial" });
test.setTimeout(240_000);

const LISTS = /\/api\/core\/v1\/profiles\/([0-9a-f-]{36})\/recommendations\/lists\/(personal|similar|popular|new)/;

type Item = { content: { id: string; kind: string }; reason: string };
type List = { source: string; reason: string | null; list: string; algorithmVersion: string | null; items: Item[] };

// Core allows 10 logins per email per 15 minutes and the RA03/RA04 suites already used some (see the budget table in
// ../ra03/support.ts): the free viewer logs in once through the UI and once through the API for tests 25-27 and 29.
// Login budget added by RA05: free_viewer 2 (total 6), premium_viewer 2 (total 7).
let shared: { session: Awaited<ReturnType<typeof viewer>>; api: Api; profileId: string } | null = null;

async function freeViewer(browser: Browser) {
  if (!shared) {
    const session = await viewer(browser, "free_viewer");
    const profileId = session.account.profiles[0].id;
    await loginThroughUi(session.page, session.account, profileId);
    const api = await apiSession(browser, session.account.email, session.account.password);
    shared = { session, api, profileId };
  }
  return shared;
}

test.beforeAll(() => resetRa05Ledger());
test.afterAll(async ({ browser }) => {
  await shared?.api.dispose();
  await shared?.session.context.close();
  writeRa05EvidenceWhenComplete(browser.version());
});

/** The cards of one row, in order, as [contentId, reason]. */
async function row(page: Page, list: string) {
  return page.getByTestId(`rec-row-${list}`).getByTestId("rec-card").evaluateAll((cards) =>
    cards.map((card) => [card.getAttribute("data-content-id"), card.getAttribute("data-reason")]));
}

async function coreList(api: Api, profileId: string, list: string, seedId?: string): Promise<List> {
  const query = seedId ? `?limit=12&contentId=${seedId}` : "?limit=12";
  const result = await api.get(`/profiles/${profileId}/recommendations/lists/${list}${query}`);
  expect(result.status, `${list} list`).toBe(200);
  return result.body as List;
}

async function viewer(browser: Browser, key: "free_viewer" | "premium_viewer") {
  const account = seed().viewers[key];
  const context = await newContext(browser);
  const page = await context.newPage();
  const lists: { profileId: string; list: string; cacheControl: string | undefined }[] = [];
  const sent: { profileId: string; at: number }[] = [];
  page.on("request", (request) => {
    const match = new URL(request.url()).pathname.match(LISTS);
    if (match) {
      sent.push({ profileId: match[1], at: Date.now() });
    }
  });
  page.on("response", (response) => {
    const match = new URL(response.url()).pathname.match(LISTS);
    if (match) {
      lists.push({ profileId: match[1], list: match[2], cacheControl: response.headers()["cache-control"] });
    }
  });
  return { account, context, page, lists, sent };
}

test("25 the home rows show what Core answers for the profile, with the reason Analytics' rule gave", async ({ browser }) => {
  const { contents } = seed();
  const { session: { page, lists }, api, profileId } = await freeViewer(browser);
  {
    await page.goto("/");
    // The popular list needs the RA03 view to reach Analytics' checkpoint.
    let popular: List | null = null;
    await poll(async () => {
      popular = await coreList(api, profileId, "popular");
      return popular.source === "ANALYTICS" && popular.items.some((item) => item.content.id === contents.free_movie.id);
    }, () => `Popular never listed the free movie; last: ${JSON.stringify(popular)}`, 180, 3000);
    await page.reload();
    for (const list of ["personal", "popular", "new"]) {
      const expected = await coreList(api, profileId, list);
      expect(expected.source, list).toBe("ANALYTICS");
      expect(expected.algorithmVersion, list).toBe("rules-v1");
      if (expected.items.length === 0) {
        await expect(page.getByTestId(`rec-row-${list}`)).toHaveCount(0);
        continue;
      }
      await ui(page.getByTestId(`rec-row-${list}`)).toBeVisible();
      await poll(async () => JSON.stringify(await row(page, list)) ===
        JSON.stringify(expected.items.map((item) => [item.content.id, item.reason])),
      () => `${list} row differs from Core's answer`, 20, 500);
      for (const item of expected.items) {
        expect(["MOVIE", "SERIES"], list).toContain(item.content.kind);
        expect(item.content.id, list).not.toBe(contents.hidden_movie.id);
      }
    }
    const popularCard = page.getByTestId("rec-row-popular")
      .locator(`[data-testid="rec-card"][data-content-id="${contents.free_movie.id}"]`);
    await ui(popularCard).toHaveAttribute("data-reason", "POPULAR");
    await ui(popularCard.getByTestId("rec-reason")).toHaveText("Nhiều người xem tuần này");
    expect(lists.length).toBeGreaterThanOrEqual(3);
    for (const call of lists) {
      expect(call.profileId).toBe(profileId);
      expect(call.cacheControl ?? "").toContain("no-store");
    }
  }
  recordRa05Pass(25, "for the free viewer the personal, popular and new rows equal Core's lists (titles, order and "
    + "reasons, algorithm rules-v1); popular holds the free movie the RA03 player qualified, labelled with its "
    + "popularity reason; only movies and series, never the hidden title; every list response was no-store and asked "
    + "for the selected profile");
});

test("26 a title page shows similar titles only, never itself, and Core refuses a hidden seed", async ({ browser }) => {
  const { contents } = seed();
  const { session: { page }, api, profileId } = await freeViewer(browser);
  {
    const expected = await coreList(api, profileId, "similar", contents.free_movie.id);
    expect(expected.source).toBe("ANALYTICS");
    expect(expected.items.length).toBeGreaterThan(0);
    expect(expected.items.every((item) => item.reason === "SIMILAR_GENRES")).toBe(true);
    expect(expected.items.map((item) => item.content.id)).not.toContain(contents.free_movie.id);
    await page.goto(`/title/${contents.free_movie.id}`);
    await ui(page.getByTestId("rec-row-similar")).toBeVisible();
    expect(await row(page, "similar")).toEqual(expected.items.map((item) => [item.content.id, item.reason]));
    await ui(page.getByTestId("rec-row-similar").getByTestId("rec-reason").first()).toHaveText("Cùng thể loại");

    const hidden = await api.get(`/profiles/${profileId}/recommendations/lists/similar?contentId=${contents.hidden_movie.id}`);
    expect(hidden.status).toBe(404);
    const episode = await api.get(`/profiles/${profileId}/recommendations/lists/similar?contentId=${contents.episode_1.id}`);
    expect(episode.status).toBe(404);
    const stray = await api.get(`/profiles/${profileId}/recommendations/lists/new?contentId=${contents.free_movie.id}`);
    expect(stray.status).toBe(400);
  }
  recordRa05Pass(26, "the free movie's page showed Core's similar list, every card labelled with the shared-genre "
    + "reason and the movie itself absent; Core answered 404 to a hidden or episode seed and 400 to a seed on another list");
});

test("27 a recommended Premium title is still refused to a free viewer", async ({ browser }) => {
  const { contents } = seed();
  const { session: { page } } = await freeViewer(browser);
  {
    await page.goto("/");
    const card = page.locator(`[data-testid="rec-card"][data-content-id="${contents.premium_movie.id}"]`).first();
    await ui(card).toBeVisible();
    await expect(card.getByRole("button")).toHaveCount(0);
    await card.click();
    await page.waitForURL(`**/title/${contents.premium_movie.id}`);
    await ui(page.getByTestId("play-denied")).toBeVisible();
    await expect(page.getByTestId("play-button")).toHaveCount(0);
  }
  recordRa05Pass(27, "the free viewer saw the Premium movie recommended, the card offered no playback, and its page "
    + "still showed the Premium denial from the entitlement check");
});

test("28 after a profile switch the rows are the new profile's, never the previous one's", async ({ browser }) => {
  const { account, context, page, sent } = await viewer(browser, "premium_viewer");
  const { a, b } = premiumProfiles("premium_viewer");
  const api = await apiSession(browser, account.email, account.password);
  try {
    await loginThroughUi(page, account, a.id);
    await page.goto("/");
    await ui(page.getByTestId("rec-row-new")).toBeVisible();
    const switched = Date.now();
    await switchProfile(page, b.id);
    await page.goto("/");
    await ui(page.getByTestId("rec-row-new")).toBeVisible();
    const after = sent.filter((call) => call.at > switched);
    expect(after.length).toBeGreaterThan(0);
    expect(after.every((call) => call.profileId === b.id), "a request sent after the switch named profile A").toBe(true);
    for (const list of ["personal", "new"]) {
      const expected = await coreList(api, b.id, list);
      await poll(async () => JSON.stringify(await row(page, list)) ===
        JSON.stringify(expected.items.map((item) => [item.content.id, item.reason])),
      () => `${list} row is not profile B's`, 20, 500);
    }
  } finally {
    await api.dispose();
    await context.close();
  }
  recordRa05Pass(28, "after switching from profile A to B every list request named B and the personal and new rows "
    + "equal Core's lists for B");
});

test("29 MOCKED: a fallback says so and an empty list is hidden", async ({ browser }) => {
  const { session: { page }, profileId } = await freeViewer(browser);
  const mocked = ["RA05 test 29: the personal list answered FALLBACK/UNAVAILABLE with one real title",
    "RA05 test 29: the popular list answered FALLBACK/UNAVAILABLE with no item"];
  try {
    await page.goto("/");
    await ui(page.getByTestId("rec-row-new")).toBeVisible();
    const newest = await page.getByTestId("rec-row-new").getByTestId("rec-card").first().getAttribute("data-content-id");
    const realNew = await (await page.request.get(`/api/core/v1/profiles/${profileId}/recommendations/lists/new?limit=12`)).json() as List;
    const item = realNew.items.find((entry) => entry.content.id === newest);
    expect(item, "the new row's first card is in Core's new list").toBeDefined();
    await page.route("**/recommendations/lists/personal**", (route) => route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ source: "FALLBACK", reason: "UNAVAILABLE", list: "personal", algorithmVersion: null,
        updatedAt: null, items: [{ content: item!.content, reason: "NEWLY_PUBLISHED" }] }),
    }));
    await page.route("**/recommendations/lists/popular**", (route) => route.fulfill({
      status: 200, contentType: "application/json",
      body: JSON.stringify({ source: "FALLBACK", reason: "UNAVAILABLE", list: "popular", algorithmVersion: null,
        updatedAt: null, items: [] }),
    }));
    await page.reload();
    await ui(page.getByTestId("rec-fallback-personal")).toBeVisible();
    await ui(page.getByTestId("rec-row-personal").getByTestId("rec-reason").first()).toHaveText("Mới phát hành");
    await expect(page.getByTestId("rec-row-popular")).toHaveCount(0);
    await ui(page.getByTestId("catalog-card").first()).toBeVisible();
  } finally {
    await page.unrouteAll({ behavior: "ignoreErrors" });
  }
  recordRa05Pass(29, "a FALLBACK personal list showed the unavailable note with Core's newest title labelled as new, "
    + "an empty fallback popular list was hidden, and the catalog still rendered", mocked);
});
