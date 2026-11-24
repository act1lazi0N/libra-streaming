import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { apiSession, loginThroughUi, newContext, poll, seed, todayUtc, ui, type SeedViewer } from "../ra03/support";

/** GET through the administrator's own browser session: no second login (Core allows 10 per email per 15 min). */
async function adminGet(route: string) {
  const response = await page.request.get("/api/core/v1" + route);
  const type = response.headers()["content-type"] ?? "";
  return { status: response.status(), body: type.includes("json") ? await response.json() : null };
}
import { recordRa04Pass, resetRa04Ledger, writeRa04EvidenceWhenComplete } from "./support";

// RA04.3/RA04.4: the administrator statistics dashboard against the real stack, after the RA03 suite produced a
// qualified view of the free movie through the real player. Real throughout except test 23 (MOCKED, named).

test.describe.configure({ mode: "serial" });
test.setTimeout(240_000);

const REPORT = "/api/core/v1/admin/statistics/report";
let context: BrowserContext;
let page: Page;
const reportRequests: string[] = [];
const reportBodies: string[] = [];
/** Bodies captured before test 23 mocks the report: only these came from Core. */
let realBodies = 0;

type Counts = { qualifiedViews: number; uniqueViewers: number; acceptedWatchedMs: number };
type Report = {
  status: string;
  updatedAt: string | null;
  totals: Counts | null;
  days: (Counts & { day: string })[] | null;
  topContent: (Counts & { contentId: string; title: string | null })[] | null;
};

function digits(text: string | null): number {
  return Number((text ?? "").replace(/[^0-9]/g, ""));
}

function track(target: Page) {
  target.on("request", (request) => {
    if (new URL(request.url()).pathname === REPORT) {
      reportRequests.push(new URL(request.url()).search);
    }
  });
  target.on("response", async (response) => {
    if (new URL(response.url()).pathname === REPORT && response.status() === 200) {
      // In memory only, for the privacy check of test 24; never written out.
      reportBodies.push(await response.text().catch(() => ""));
    }
  });
}

test.beforeAll(async ({ browser }) => {
  resetRa04Ledger();
  context = await newContext(browser);
  page = await context.newPage();
  track(page);
});

test.afterAll(async ({ browser }) => {
  await context?.close();
  writeRa04EvidenceWhenComplete(browser.version());
});

test("19 the administrator reaches the dashboard and sees the view the RA03 player produced", async () => {
  const { admin, contents } = seed();
  await loginThroughUi(page, { ...admin, displayName: "admin", verified: true, premium: false, profiles: [] } as SeedViewer);
  // The administrator has chosen no viewing profile: the dashboard must not need one.
  await ui(page.getByTestId("header-statistics")).toBeVisible();
  await page.getByTestId("header-statistics").click();
  await page.waitForURL("**/admin/statistics**");
  await ui(page.getByTestId("stats-note")).toContainText("UTC");
  await ui(page.getByTestId("stats-from")).toHaveValue(todayUtc(-6));
  await ui(page.getByTestId("stats-to")).toHaveValue(todayUtc());
  expect(new URL(page.url()).pathname, "the dashboard must not send the administrator to the profile picker")
    .toBe("/admin/statistics");
  await poll(async () => {
    await page.reload();
    await page.getByTestId("stats-range-form").waitFor();
    await page.waitForSelector('[data-testid="stats-updated"], [data-testid="stats-stale"], [data-testid="stats-unavailable"], [data-testid="stats-empty"]');
    if (!(await page.getByTestId("stats-updated").isVisible())) {
      return false;
    }
    const views = digits(await page.getByTestId("stats-total-views").textContent().catch(() => null));
    const row = page.locator(`[data-testid="stats-top-row"][data-content-id="${contents.free_movie.id}"]`);
    return views >= 1 && (await row.count()) === 1;
  }, "The dashboard never showed the free movie's qualified view as fresh data", 180, 3000);
  await ui(page.locator(`[data-testid="stats-top-row"][data-content-id="${contents.free_movie.id}"]`))
    .toContainText(contents.free_movie.title);
  const today = page.locator(`[data-testid="stats-days"] tr[data-day="${todayUtc()}"] td`);
  expect(digits(await today.nth(1).textContent())).toBeGreaterThanOrEqual(1);
  await ui(page.locator(`[data-testid="stats-bar"][data-day="${todayUtc()}"]`)).toHaveCount(1);
  recordRa04Pass(19, "the administrator opened the dashboard from the header; the default range was the last 7 UTC "
    + "days without choosing a viewing profile; fresh data showed the qualified view on today's bar and row and the free movie, named from Core's "
    + "catalog, in the top table");
});

test("20 the page shows exactly what Core reports, and the report totals equal the V1 totals", async () => {
  const api = { get: adminGet };
  {
    const range = `from=${todayUtc(-6)}&to=${todayUtc()}`;
    await page.reload();
    await ui(page.getByTestId("stats-updated")).toBeVisible();
    const report = (await api.get(`/admin/statistics/report?${range}&top=10`)).body as Report;
    const v1 = (await api.get(`/admin/statistics?${range}`)).body as Counts & { status: string };
    expect(report.status).toBe("AVAILABLE");
    expect(v1.status).toBe("AVAILABLE");
    const totals = report.totals!;
    expect({ views: v1.qualifiedViews, viewers: v1.uniqueViewers, watched: v1.acceptedWatchedMs })
      .toEqual({ views: totals.qualifiedViews, viewers: totals.uniqueViewers, watched: totals.acceptedWatchedMs });
    expect(digits(await page.getByTestId("stats-total-views").textContent())).toBe(totals.qualifiedViews);
    expect(digits(await page.getByTestId("stats-total-viewers").textContent())).toBe(totals.uniqueViewers);
    expect(report.days!.map((d) => d.day)).toEqual(Array.from({ length: 7 }, (_, i) => todayUtc(i - 6)));
    for (const day of report.days!) {
      const cells = page.locator(`[data-testid="stats-days"] tr[data-day="${day.day}"] td`);
      expect(digits(await cells.nth(1).textContent()), day.day).toBe(day.qualifiedViews);
      expect(digits(await cells.nth(2).textContent()), day.day).toBe(day.uniqueViewers);
    }
    expect(report.days!.reduce((sum, d) => sum + d.qualifiedViews, 0)).toBe(totals.qualifiedViews);
    const rows = await page.getByTestId("stats-top-row").evaluateAll((els) => els.map((e) => e.getAttribute("data-content-id")));
    expect(rows).toEqual(report.topContent!.map((t) => t.contentId));
  }
  recordRa04Pass(20, "the page's totals, every day's views and viewers and the order of the top table equal Core's "
    + "report for the same range; the report's totals equal the V1 statistics and its days add up to them");
});

test("21 range form: presets, client-side refusals without a request, URL state, and Core's own 400", async () => {
  await page.getByTestId("stats-preset-30").click();
  await ui(page.getByTestId("stats-from")).toHaveValue(todayUtc(-29));
  await page.waitForURL(new RegExp(`from=${todayUtc(-29)}&to=${todayUtc()}`));
  await ui(page.getByTestId("stats-updated")).toBeVisible();

  const before = reportRequests.length;
  await page.getByTestId("stats-from").fill(todayUtc(-40));
  await page.getByTestId("stats-apply").click();
  await ui(page.getByTestId("stats-range-error")).toBeVisible();
  await page.getByTestId("stats-from").fill(todayUtc(-1));
  await page.getByTestId("stats-to").fill(todayUtc(1));
  await page.getByTestId("stats-apply").click();
  await ui(page.getByTestId("stats-range-error")).toContainText("UTC");
  await page.waitForTimeout(1000);
  expect(reportRequests.length, "an invalid range must not reach Core").toBe(before);

  await page.getByTestId("stats-to").fill(todayUtc());
  await page.getByTestId("stats-from").fill(todayUtc(-2));
  await page.getByTestId("stats-apply").click();
  await page.waitForURL(new RegExp(`from=${todayUtc(-2)}&to=${todayUtc()}`));
  await page.reload();
  await ui(page.getByTestId("stats-from")).toHaveValue(todayUtc(-2));
  await ui(page.getByTestId("stats-updated")).toBeVisible();
  expect(reportRequests.at(-1)).toContain(`from=${todayUtc(-2)}`);

  await page.goto(`/admin/statistics?from=${todayUtc(-60)}&to=${todayUtc()}`);
  await ui(page.getByTestId("stats-from")).toHaveValue(todayUtc(-6));
  await ui(page.getByTestId("stats-url-replaced")).toBeVisible();
  expect(reportRequests.at(-1)).toContain(`from=${todayUtc(-6)}`);

  {
    const api = { get: adminGet };
    const tooLong = await api.get(`/admin/statistics/report?from=${todayUtc(-40)}&to=${todayUtc()}`);
    const tooMany = await api.get(`/admin/statistics/report?from=${todayUtc(-1)}&to=${todayUtc()}&top=51`);
    const future = await api.get(`/admin/statistics/report?from=${todayUtc()}&to=${todayUtc(1)}`);
    for (const result of [tooLong, tooMany, future]) {
      expect(result.status).toBe(400);
      expect((result.body as { code?: string }).code).toBe("INVALID_REQUEST");
    }
  }
  recordRa04Pass(21, "the 30-day preset applied today-29..today; a 41-day or future range was refused in the form "
    + "with no request to Core; an applied range survived a reload through the URL; an invalid URL range fell back "
    + "to 7 days with a notice; Core itself answered 400 INVALID_REQUEST to 41 days, top=51 and a future end");
});

test("22 a viewer sees no statistics and Core refuses them; anonymous visitors are asked to log in", async ({ browser }) => {
  const { free_viewer: viewer } = seed().viewers;
  const viewerContext = await newContext(browser);
  const anonymousContext = await newContext(browser);
  try {
    const viewerPage = await viewerContext.newPage();
    track(viewerPage);
    const before = reportRequests.length;
    await loginThroughUi(viewerPage, viewer);
    await ui(viewerPage.getByTestId("header-account")).toBeVisible();
    await expect(viewerPage.getByTestId("header-statistics")).toHaveCount(0);
    await viewerPage.goto("/admin/statistics");
    await ui(viewerPage.getByTestId("stats-forbidden")).toBeVisible();
    await viewerPage.waitForTimeout(1000);
    expect(reportRequests.length, "a viewer's page must not ask for the report").toBe(before);

    const api = await apiSession(browser, viewer.email, viewer.password);
    try {
      const denied = await api.get(`/admin/statistics/report?from=${todayUtc(-6)}&to=${todayUtc()}`);
      expect(denied.status).toBe(403);
      expect(JSON.stringify(denied.body)).not.toContain("qualifiedViews");
    } finally {
      await api.dispose();
    }

    const anonymousPage = await anonymousContext.newPage();
    await anonymousPage.goto("/admin/statistics");
    await ui(anonymousPage.getByTestId("stats-login")).toBeVisible();
    const anonymous = await anonymousContext.request.get(`${REPORT}?from=${todayUtc(-6)}&to=${todayUtc()}`);
    expect(anonymous.status()).toBe(401);
  } finally {
    await viewerContext.close();
    await anonymousContext.close();
  }
  recordRa04Pass(22, "a verified viewer had no statistics link, saw the access-denied message without a report "
    + "request and got 403 from Core; an anonymous visitor was asked to log in and got 401");
});

test("23 MOCKED report bodies: stale data keeps its numbers with a warning, unavailable shows none, zero is empty", async () => {
  realBodies = reportBodies.length;
  const real = JSON.parse(reportBodies.at(-1) ?? "null") as Report | null;
  expect(real?.totals).toBeTruthy();
  const bodies: Record<string, Report> = {
    stale: { ...real!, status: "STALE", updatedAt: new Date(Date.now() - 3_600_000).toISOString() },
    unavailable: { ...real!, status: "UNAVAILABLE", updatedAt: null, totals: null, days: null, topContent: null },
    empty: {
      ...real!,
      totals: { qualifiedViews: 0, uniqueViewers: 0, acceptedWatchedMs: 0 },
      days: real!.days!.map((d) => ({ ...d, qualifiedViews: 0, uniqueViewers: 0, acceptedWatchedMs: 0 })),
      topContent: [],
    },
  };
  let current = "stale";
  await page.route(`**${REPORT}**`, (route) => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ ...bodies[current], reason: current === "stale" ? "STALE" : current === "unavailable" ? "UNAVAILABLE" : null }),
  }));
  try {
    await page.goto("/admin/statistics");
    await ui(page.getByTestId("stats-stale")).toContainText("UTC");
    expect(digits(await page.getByTestId("stats-total-views").textContent())).toBe(real!.totals!.qualifiedViews);

    current = "unavailable";
    await page.reload();
    await ui(page.getByTestId("stats-unavailable")).toBeVisible();
    await expect(page.getByTestId("stats-total-views")).toHaveCount(0);
    await expect(page.getByTestId("stats-bar")).toHaveCount(0);
    await expect(page.getByTestId("stats-top")).toHaveCount(0);

    current = "empty";
    await page.reload();
    await ui(page.getByTestId("stats-empty")).toBeVisible();
    await ui(page.getByTestId("stats-updated")).toBeVisible();
    await expect(page.getByTestId("stats-bar")).toHaveCount(0);
  } finally {
    await page.unroute(`**${REPORT}**`);
  }
  recordRa04Pass(23, "with the report request answered by canned bodies built from the real one: STALE showed a "
    + "warning with the UTC time and kept the numbers; UNAVAILABLE showed no number, bar or table; zero totals showed "
    + "the empty state", ["RA04 test 23: three report responses (STALE, UNAVAILABLE, zero totals) fulfilled by the test"]);
});

test("24 no report the dashboard received carries a viewer's identity", async () => {
  const viewers = Object.values(seed().viewers);
  const secrets = viewers.flatMap((v) => [v.email, ...v.profiles.map((p) => p.id)]);
  const real = reportBodies.slice(0, realBodies);
  expect(real.length).toBeGreaterThan(0);
  for (const body of real) {
    for (const secret of secrets) {
      expect(body.includes(secret), "a report body carried a viewer identifier or email").toBe(false);
    }
    const parsed = JSON.parse(body) as Record<string, unknown>;
    expect(Object.keys(parsed).sort()).toEqual(["days", "from", "reason", "status", "to", "topContent", "totals", "updatedAt"]);
  }
  recordRa04Pass(24, `${real.length} report bodies received by the dashboard held only the contract's fields and no `
    + "viewer email or profile identifier");
});
