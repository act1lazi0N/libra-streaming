import { expect, test } from "@playwright/test";
import { Traffic, loginThroughUi, newContext, premiumProfiles, recordPass, seed, ui } from "./support";

// RA03.2: catalog grid, detail, entitlement-driven play affordance. Real Core and real published content.

test.describe.configure({ mode: "serial" });
test.setTimeout(120_000);

test("4 the grid lists free, premium and series but not the hidden title; its page says not found", async ({ browser }) => {
  const { free_movie: free, premium_movie: premium, series, hidden_movie: hidden, episode_1: episode } = seed().contents;
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    // The catalog is public: no sign-in needed to browse.
    await page.goto("/");
    const card = (id: string) => page.locator(`[data-testid="catalog-card"][data-content-id="${id}"]`);
    for (const item of [free, premium, series]) {
      await ui(card(item.id)).toBeVisible();
    }
    await expect(card(hidden.id)).toHaveCount(0);
    // Only movies and series are cards; episodes are reached through their series.
    await expect(card(episode.id)).toHaveCount(0);
    await page.goto(`/title/${hidden.id}`);
    await ui(page.getByTestId("title-not-found")).toBeVisible();
    await traffic.settle();
    recordPass(4, "grid shows the free movie, the Premium movie and the series, not the hidden title or an episode; "
      + "the hidden title's page shows title-not-found", [traffic]);
  } finally {
    await context.close();
  }
});

test("5 detail pages: tier badge versus real entitlement, and the series structure", async ({ browser }) => {
  const { premium_movie: premium, series, season, episode_1: first, episode_2: second } = seed().contents;
  const { free_viewer: freeViewer, premium_viewer: premiumViewer } = seed().viewers;

  // A viewer without Premium: the badge is informational; the entitlement decides.
  const freeContext = await newContext(browser);
  const freePage = await freeContext.newPage();
  const freeTraffic = new Traffic();
  freeTraffic.attach(freePage);
  try {
    await loginThroughUi(freePage, freeViewer, freeViewer.profiles[0].id);
    await freePage.locator(`[data-testid="catalog-card"][data-content-id="${premium.id}"]`).click();
    await ui(freePage.getByTestId("title-detail")).toBeVisible();
    await ui(freePage.getByTestId("tier-badge")).toContainText("Premium");
    await ui(freePage.getByTestId("play-denied")).toBeVisible();
    await expect(freePage.getByTestId("play-button")).toHaveCount(0);
    await freeTraffic.settle();
    const denial = freeTraffic.since(0).find((e) => e.template.includes("/entitlements/") && e.ids[1] === premium.id);
    expect(denial?.status, "entitlement answer for the Premium title").toBe(403);
    expect(denial?.code).toBe("PREMIUM_REQUIRED");
  } finally {
    await freeContext.close();
  }

  const premiumContext = await newContext(browser);
  const premiumPage = await premiumContext.newPage();
  const premiumTraffic = new Traffic();
  premiumTraffic.attach(premiumPage);
  try {
    const { a } = premiumProfiles();
    await loginThroughUi(premiumPage, premiumViewer, a.id);
    await premiumPage.goto(`/title/${premium.id}`);
    await ui(premiumPage.getByTestId("tier-badge")).toContainText("Premium");
    await ui(premiumPage.getByTestId("play-button")).toBeEnabled();
    await expect(premiumPage.getByTestId("play-denied")).toHaveCount(0);

    await premiumPage.goto(`/title/${series.id}`);
    const seasonRow = premiumPage.locator(`[data-testid="season"][data-content-id="${season.id}"]`);
    await ui(seasonRow).toBeVisible();
    const episodes = premiumPage.locator('[data-testid="episode"]');
    await ui(episodes).toHaveCount(2);
    const ids = await episodes.evaluateAll((nodes) => nodes.map((n) => n.getAttribute("data-content-id")));
    expect(ids, "episodes of season 1 in ordinal order").toEqual([first.id, second.id]);
    await premiumTraffic.settle();
    recordPass(5, "free viewer: Premium badge plus play-denied (PREMIUM_REQUIRED) and no play button; Premium viewer: "
      + "enabled play button; the series lists season 1 with episodes 1 and 2 in order", [freeTraffic, premiumTraffic]);
  } finally {
    await premiumContext.close();
  }
});
