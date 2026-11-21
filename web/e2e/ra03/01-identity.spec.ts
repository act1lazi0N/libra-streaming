import { expect, test } from "@playwright/test";
import {
  Traffic, apiSession, chooseProfile, loginThroughUi, newContext, premiumProfiles, recordPass, resetLedger,
  seed, sleep, switchProfile, ui,
} from "./support";

// RA03.1: identity, concurrent refresh and profile switching through the product UI. Real Core behind the ingress.

test.describe.configure({ mode: "serial" });
test.setTimeout(120_000);

test.beforeAll(() => {
  resetLedger();
});

test("1 login through /login, account from /me, both profiles listed and one picked", async ({ browser }) => {
  const viewer = seed().viewers.premium_viewer;
  const { a } = premiumProfiles();
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    await loginThroughUi(page, viewer);
    // With two profiles nothing is chosen yet: the page asks.
    await page.waitForFunction(() => location.pathname === "/profiles", undefined, { timeout: 30_000 });
    const options = page.getByTestId("profile-option");
    await ui(options).toHaveCount(viewer.profiles.length);
    for (const profile of viewer.profiles) {
      await ui(page.locator(`[data-testid="profile-option"][data-profile-id="${profile.id}"]`)).toContainText(profile.name);
    }
    await chooseProfile(page, a.id);
    await ui(page.getByTestId("app-header")).toContainText(viewer.email);
    await ui(page.getByTestId("header-profile")).toContainText(a.name);
    await traffic.settle();
    expect(traffic.entries.some((e) => e.method === "GET" && e.template === "/api/core/v1/me" && e.status === 200)).toBe(true);
    expect(traffic.entries.some((e) => e.method === "GET" && e.template === "/api/core/v1/profiles" && e.status === 200)).toBe(true);
    recordPass(1, "login through /login shows the account from /me; /profiles lists both profiles; one is picked", [traffic]);
  } finally {
    await context.close();
  }
});

test("2 two tabs with an expired access credential cause exactly one refresh", async ({ browser }) => {
  const viewer = seed().viewers.premium_viewer;
  const { a } = premiumProfiles();
  const context = await newContext(browser);
  const traffic = new Traffic();
  try {
    const first = await context.newPage();
    traffic.attach(first);
    await loginThroughUi(first, viewer, a.id);
    const second = await context.newPage();
    traffic.attach(second);
    await second.goto("/");
    // The profile is chosen per tab: the second tab asks for one too.
    await chooseProfile(second, a.id);
    await ui(first.getByTestId("home")).toBeVisible();
    await ui(second.getByTestId("home")).toBeVisible();
    await traffic.settle();

    // Only the access credential disappears (as when it expires); the refresh credential stays.
    const names = (await context.cookies()).map((c) => c.name).filter((n) => n.endsWith("LIBRA_ACCESS"));
    expect(names.length).toBeGreaterThan(0);
    for (const name of names) {
      await context.clearCookies({ name });
    }
    const mark = traffic.mark();
    await Promise.all([first.reload(), second.reload()]);
    await ui(first.getByTestId("home")).toBeVisible();
    await ui(second.getByTestId("home")).toBeVisible();
    await ui(first.getByTestId("header-profile")).toBeVisible();
    await ui(second.getByTestId("header-profile")).toBeVisible();
    await traffic.settle();
    const refreshes = traffic.refreshes(mark);
    expect(refreshes.map((e) => e.status), "POST /auth/refresh across both tabs").toEqual([200]);
    // The refresh family survived: a later read is still authenticated.
    const me = await first.request.get("/api/core/v1/me");
    expect(me.status()).toBe(200);
    recordPass(2, "with the access credential cleared, reloading two tabs at once made exactly one POST /auth/refresh; "
      + "both rendered and /me stayed 200", [traffic]);
  } finally {
    await context.close();
  }
});

test("3 a late answer for the previous profile is never rendered after switching", async ({ browser }) => {
  const viewer = seed().viewers.premium_viewer;
  const { a, b } = premiumProfiles();
  const free = seed().contents.free_movie;
  // Setup through the API (not the behaviour under test): profile A has one continue-watching item, B has none.
  const api = await apiSession(browser, viewer.email, viewer.password);
  try {
    const session = await api.send("POST", "/playback/sessions", { profileId: a.id, contentId: free.id });
    expect(session.status).toBe(201);
    const id = (session.body as { sessionId: string }).sessionId;
    const report = await api.send("POST", `/playback/sessions/${id}/progress`,
      { sequence: 1, positionMs: 5000, playedMs: 0, state: "PAUSED" });
    expect(report.status).toBe(200);
    const listing = await api.get(`/profiles/${a.id}/continue-watching?limit=20`);
    expect((listing.body as { contentId: string }[]).some((i) => i.contentId === free.id)).toBe(true);
    const other = await api.get(`/profiles/${b.id}/continue-watching?limit=20`);
    expect(other.body).toEqual([]);
  } finally {
    await api.dispose();
  }

  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  let delayed = 0;
  await page.route(`**/api/core/v1/profiles/${a.id}/continue-watching*`, async (route) => {
    delayed += 1;
    await sleep(3_000);
    await route.continue();
  });
  try {
    await loginThroughUi(page, viewer);
    await page.waitForFunction(() => location.pathname === "/profiles", undefined, { timeout: 30_000 });
    // Pick A: its continue-watching request is now held for 3 s. Switch to B during the delay.
    await page.locator(`[data-testid="profile-option"][data-profile-id="${a.id}"]`).click();
    await ui(page.getByTestId("header-switch-profile")).toBeVisible();
    await switchProfile(page, b.id);
    await ui(page.getByTestId("header-profile")).toContainText(b.name);
    const item = page.locator('[data-testid="continue-item"]');
    const deadline = Date.now() + 6_000;
    while (Date.now() < deadline) {
      expect(await item.count(), "continue-item while B is selected").toBe(0);
      await sleep(100);
    }
    await traffic.settle();
    expect(delayed, "A's continue-watching request was held").toBeGreaterThan(0);
    // The app aborts A's request on the switch; a 200 that arrived anyway must simply never have been rendered.
    const late = traffic.entries.find((e) => e.template === "/api/core/v1/profiles/{id}/continue-watching" && e.ids[0] === a.id);
    expect(late, "A's continue-watching request was made").toBeDefined();
    const outcome = late?.failed || late?.status === null ? "aborted" : `answered ${late?.status}`;
    recordPass(3, `profile A's continue-watching request, delayed 3 s, was ${outcome} after switching to B and was never shown (no item for 6 s)`,
      [traffic], { delayed: ["GET /profiles/{id}/continue-watching for profile A held 3 s before reaching Core"] });
  } finally {
    await context.close();
  }
});
