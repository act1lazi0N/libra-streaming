import { expect, test, type Page } from "@playwright/test";
import {
  Traffic, apiSession, loginThroughUi, newContext, poll, premiumProfiles, problem, recordPass, seed, sleep,
  switchProfile, ui, videoState, waitPlaying, writeEvidenceWhenComplete,
} from "./support";

// RA03.5: hardening against slow networks, duplicate actions, tab close, expiry, logout elsewhere, denials and
// profile switching. Real stack throughout except the one step marked MOCKED.

test.describe.configure({ mode: "serial" });
test.setTimeout(180_000);

async function openTitleAndPlay(page: Page, contentId: string) {
  await page.goto(`/title/${contentId}`);
  await ui(page.getByTestId("play-button").first()).toBeEnabled();
  await page.getByTestId("play-button").first().click();
  await page.waitForFunction(() => location.pathname.startsWith("/watch/"), undefined, { timeout: 30_000 });
}

test.afterAll(async ({ browser }) => {
  // The evidence exists only when every test of the suite recorded its pass.
  writeEvidenceWhenComplete(browser.version());
});

test("12 on a slow network playback still advances, with one admission and no request loop", async ({ browser }) => {
  const { premium_viewer_2: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a } = premiumProfiles("premium_viewer_2");
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    await loginThroughUi(page, viewer, a.id);
    await page.goto(`/title/${free.id}`);
    await ui(page.getByTestId("play-button")).toBeEnabled();
    const cdp = await context.newCDPSession(page);
    await cdp.send("Network.enable");
    await cdp.send("Network.emulateNetworkConditions", {
      offline: false, latency: 400, downloadThroughput: (1.5 * 1024 * 1024) / 8, uploadThroughput: (750 * 1024) / 8,
    });
    const mark = traffic.mark();
    await page.getByTestId("play-button").click();
    await page.waitForFunction(() => location.pathname.startsWith("/watch/"), undefined, { timeout: 60_000 });
    await waitPlaying(page, 5, 120);
    await traffic.settle();
    expect(traffic.admissions(mark), "admissions on the slow network").toHaveLength(1);
    expect(traffic.csrfRejections(mark), "403 ACCESS_DENIED on a Core POST (C1)").toEqual([]);
    const counts = new Map<string, number>();
    for (const entry of traffic.since(mark)) {
      const key = `${entry.method} ${entry.path}`;
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    const repeated = [...counts].filter(([key, n]) => n > (key.includes("/api/media/") ? 3 : 15));
    expect(repeated.map(([key, n]) => `${key.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, "{id}")} x${n}`), "requests repeated beyond expectation")
      .toEqual([]);
    recordPass(12, "with 400 ms latency and about 1.5 Mbps down, playback advanced at least 5 s after exactly one admission "
      + "and no endpoint was requested in a loop", [traffic],
    { delayed: ["all page traffic throttled by Chrome DevTools network emulation (400 ms, about 1.5 Mbps down)"] });
  } finally {
    await context.close();
  }
});

test("13 a double click on play makes exactly one admission", async ({ browser }) => {
  const { premium_viewer_3: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a } = premiumProfiles("premium_viewer_3");
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    await loginThroughUi(page, viewer, a.id);
    await page.goto(`/title/${free.id}`);
    await ui(page.getByTestId("play-button")).toBeEnabled();
    const mark = traffic.mark();
    await page.getByTestId("play-button").dblclick({ delay: 20 });
    await page.waitForFunction(() => location.pathname.startsWith("/watch/"), undefined, { timeout: 30_000 });
    await waitPlaying(page, 1);
    await sleep(3_000);
    await traffic.settle();
    expect(traffic.admissions(mark)).toHaveLength(1);
    recordPass(13, "double-clicking the play button made exactly one admission and playback started", [traffic]);
  } finally {
    await context.close();
  }
});

test("14 closing the tab while playing saves the position", async ({ browser }) => {
  const { premium_viewer_3: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a } = premiumProfiles("premium_viewer_3");
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  let observed = 0;
  try {
    await loginThroughUi(page, viewer, a.id);
    await openTitleAndPlay(page, free.id);
    await waitPlaying(page, 6);
    observed = (await videoState(page))?.t ?? 0;
    await page.close({ runBeforeUnload: true });
    await traffic.settle();
  } finally {
    await context.close();
  }
  const api = await apiSession(browser, viewer.email, viewer.password);
  try {
    let last = "";
    await poll(async () => {
      const history = await api.get(`/profiles/${a.id}/history?limit=20`);
      const item = (history.body as { contentId: string; positionMs: number }[]).find((i) => i.contentId === free.id);
      last = String(item?.positionMs);
      return item !== undefined && Math.abs(item.positionMs / 1000 - observed) <= 3;
    }, () => `History position ${last} ms is not within 3 s of ${observed.toFixed(1)} s`, 20, 1000);
  } finally {
    await api.dispose();
  }
  recordPass(14, "closing the tab while playing left the profile's history within 3 s of the last observed position",
    [traffic]);
});

test("15 Premium ending mid-playback stops the player and nothing is requested afterwards", async ({ browser }) => {
  const { premium_viewer_3: viewer } = seed().viewers;
  const premium = seed().contents.premium_movie;
  const { a } = premiumProfiles("premium_viewer_3");
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    await loginThroughUi(page, viewer, a.id);
    await openTitleAndPlay(page, premium.id);
    await waitPlaying(page, 2);
    let answered = false;
    await page.route("**/api/core/v1/playback/sessions/*/progress", async (route) => {
      if (!answered) {
        answered = true;
        // MOCKED: the account's Premium ends; Core answers the next report the way it does then.
        await route.fulfill({ status: 403, contentType: "application/problem+json",
          body: problem(403, "Forbidden", "PREMIUM_REQUIRED") });
      } else {
        await route.continue();
      }
    });
    await page.evaluate(() => document.querySelector<HTMLVideoElement>('[data-testid="video"]')?.pause());
    await poll(async () => answered || null, "No progress report to answer", 20);
    await ui(page.getByTestId("player-denied")).toBeVisible();
    await traffic.settle();
    const after = traffic.mark();
    await sleep(20_000);
    const later = traffic.since(after).filter((e) => e.method === "POST"
      && (e.template === "/api/core/v1/playback/sessions" || /\/(progress|end|renew)$/.test(e.template)));
    expect(later.map((e) => e.template), "playback requests after the denial").toEqual([]);
    recordPass(15, "after Core answered a progress report with 403 PREMIUM_REQUIRED the player showed player-denied and "
      + "made no admission, progress or renewal request for 20 s", [traffic],
    { mocked: ["POST /playback/sessions/{id}/progress answered once with 403 PREMIUM_REQUIRED (the page's reaction is real)"] });
  } finally {
    await context.close();
  }
});

test("16 logging out in another tab stops playback without a refresh storm", async ({ browser }) => {
  const { premium_viewer_3: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a } = premiumProfiles("premium_viewer_3");
  const context = await newContext(browser);
  const traffic = new Traffic();
  try {
    const first = await context.newPage();
    traffic.attach(first);
    await loginThroughUi(first, viewer, a.id);
    await openTitleAndPlay(first, free.id);
    await waitPlaying(first, 3);
    const second = await context.newPage();
    traffic.attach(second);
    await second.goto("/");
    await ui(second.getByTestId("header-logout")).toBeVisible();
    const mark = traffic.mark();
    await second.getByTestId("header-logout").click();
    await poll(async () => {
      const denied = (await first.getByTestId("player-denied").count()) > 0;
      return denied || new URL(first.url()).pathname.startsWith("/login") || null;
    }, "The playing tab did not stop after logout", 20, 250);
    await sleep(6_000);
    await traffic.settle();
    expect(traffic.refreshes(mark).length, "refresh calls after the logout").toBeLessThanOrEqual(1);
    const calls = traffic.sessionCalls(mark);
    const firstUnauthorized = calls.findIndex((e) => e.status === 401);
    if (firstUnauthorized >= 0) {
      expect(calls.slice(firstUnauthorized + 1).map((e) => e.template), "session calls after the first 401").toEqual([]);
    }
    recordPass(16, "after another tab logged out the playing tab stopped within 20 s with at most one POST /auth/refresh "
      + "and no progress request after the first 401", [traffic]);
  } finally {
    await context.close();
  }
});

test("17 playback is denied to an unverified account and to a free account on Premium, one admission each", async ({ browser }) => {
  const { unverified_viewer: unverified, free_viewer: freeViewer } = seed().viewers;
  const { free_movie: free, premium_movie: premium } = seed().contents;
  const traffics: Traffic[] = [];
  for (const [viewer, content, code, banner] of [
    [unverified, free, "EMAIL_VERIFICATION_REQUIRED", true],
    [freeViewer, premium, "PREMIUM_REQUIRED", false],
  ] as const) {
    const context = await newContext(browser);
    const page = await context.newPage();
    const traffic = new Traffic();
    traffic.attach(page);
    traffics.push(traffic);
    try {
      await loginThroughUi(page, viewer, viewer.profiles[0].id);
      if (banner) {
        await ui(page.getByTestId("email-unverified")).toBeVisible();
      }
      const mark = traffic.mark();
      await page.goto(`/watch/${content.id}`);
      await ui(page.getByTestId("player-denied")).toBeVisible();
      await sleep(3_000);
      await traffic.settle();
      const admissions = traffic.admissions(mark);
      expect(admissions, "admission requests").toHaveLength(1);
      expect(admissions[0].status).toBe(403);
      expect(admissions[0].code).toBe(code);
      const names = (await context.cookies()).map((c) => c.name);
      expect(names.filter((n) => n.includes("LIBRA_PLAYBACK")), "ticket cookies after a denial").toEqual([]);
    } finally {
      await context.close();
    }
  }
  recordPass(17, "the unverified account was denied (EMAIL_VERIFICATION_REQUIRED) on a free title and the free account on a "
    + "Premium title (PREMIUM_REQUIRED): player-denied, one admission each and no ticket cookie", traffics);
});

test("18 switching profile while playing stops the player for the old profile", async ({ browser }) => {
  const { premium_viewer_3: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a, b } = premiumProfiles("premium_viewer_3");
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    await loginThroughUi(page, viewer, a.id);
    await openTitleAndPlay(page, free.id);
    await waitPlaying(page, 4);
    await traffic.settle();
    const sessionId = traffic.admissions()[0]?.sessionId;
    expect(sessionId).toBeTruthy();
    const mark = traffic.mark();
    await switchProfile(page, b.id);
    await ui(page.getByTestId("header-profile")).toContainText(b.name);
    await sleep(8_000);
    await traffic.settle();
    const forOld = traffic.sessionCalls(mark).filter((e) => e.ids[0] === sessionId);
    expect(forOld.length, "calls for the old profile's session after the switch").toBeLessThanOrEqual(1);
    for (const call of forOld) {
      expect(call.req?.state ?? "PAUSED").toBe("PAUSED");
    }
    await expect(page.locator(`[data-testid="continue-item"][data-content-id="${free.id}"]`)).toHaveCount(0);
    expect(await videoState(page), "no player left on the page").toBeNull();
    recordPass(18, "switching to profile B stopped the player: at most one PAUSED flush for profile A's session, nothing "
      + "else, and B's continue-watching does not list the movie", [traffic]);
  } finally {
    await context.close();
  }
});
