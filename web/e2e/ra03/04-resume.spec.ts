import { expect, test, type Page } from "@playwright/test";
import {
  Traffic, chooseProfile, loginThroughUi, newContext, poll, premiumProfiles, problem, recordPass, seed, sleep, ui,
  videoState, waitPlaying,
} from "./support";

// RA03.4: resume from the server's last accepted position, next episode, and binding change recovery.

test.describe.configure({ mode: "serial" });
test.setTimeout(180_000);

async function openTitleAndPlay(page: Page, contentId: string) {
  await page.goto(`/title/${contentId}`);
  await ui(page.getByTestId("play-button").first()).toBeEnabled();
  await page.getByTestId("play-button").first().click();
  await page.waitForFunction(() => location.pathname.startsWith("/watch/"), undefined, { timeout: 30_000 });
}

test("9 resume continues from the last accepted position, not from dropped reports", async ({ browser }) => {
  const { premium_viewer_2: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a } = premiumProfiles("premium_viewer_2");
  const context = await newContext(browser);
  const traffic = new Traffic();
  const first = await context.newPage();
  traffic.attach(first);
  try {
    await loginThroughUi(first, viewer, a.id);
    await openTitleAndPlay(first, free.id);
    await waitPlaying(first, 8);
    await first.evaluate(() => document.querySelector<HTMLVideoElement>('[data-testid="video"]')?.pause());
    const paused = await poll(async () => {
      await traffic.settle();
      return traffic.accepted(0).filter((e) => e.req?.state === "PAUSED").pop() ?? null;
    }, "The PAUSED report was not accepted", 30);
    const accepted = (paused.res?.positionMs ?? 0) / 1000;
    expect(accepted).toBeGreaterThan(5);

    // From here on the network is gone for this browser context: heartbeats, the PAUSED flush and the keepalive
    // flush sent while the tab closes cannot reach Core (a page.route abort does not catch keepalive requests sent
    // during unload). The clip is short enough to be buffered, so playback continues locally meanwhile.
    const droppedMark = traffic.mark();
    await context.setOffline(true);
    await first.evaluate(() => document.querySelector<HTMLVideoElement>('[data-testid="video"]')?.play());
    await waitPlaying(first, accepted + 4);
    await sleep(1_500);
    await first.close({ runBeforeUnload: true });
    await sleep(1_500);
    await traffic.settle();
    const dropped = traffic.reports(droppedMark);
    expect(dropped.every((e) => e.status === null || e.failed), "no report after the cut reached Core").toBe(true);
    await context.setOffline(false);

    // Reopen in a new tab: the server resumes from the last accepted position.
    const second = await context.newPage();
    traffic.attach(second);
    await second.goto("/"); // a new tab has no selected profile yet (per-tab sessionStorage)
    await chooseProfile(second, a.id);
    const reopenMark = traffic.mark();
    await second.goto(`/watch/${free.id}`);
    await ui(second.getByTestId("resume-from")).toBeVisible();
    const started = await waitPlaying(second, 0.2);
    expect(Math.abs(started.t - accepted), "start position against the last accepted position (s)").toBeLessThanOrEqual(2.5);
    await traffic.settle();
    expect(traffic.admissions(reopenMark)[0]?.status).toBe(201);

    // Home lists the title under continue watching.
    await second.goto("/");
    await ui(second.locator(`[data-testid="continue-item"][data-content-id="${free.id}"]`)).toBeVisible();
    recordPass(9, "after a PAUSED report was accepted the context went offline, played on and the tab closed, so no later "
      + "report (keepalive flush included) reached Core; reopening resumed within "
      + "2.5 s of the accepted position and showed resume-from; Home lists the title under continue watching",
    [traffic], { dropped: ["browser context offline after the accepted PAUSED report: heartbeats and the unload flush never left"] });
  } finally {
    await context.close();
  }
});

test("10 the next episode is offered at the end and plays", async ({ browser }) => {
  const { premium_viewer_2: viewer } = seed().viewers;
  const { series, episode_1: one, episode_2: two } = seed().contents;
  const { a } = premiumProfiles("premium_viewer_2");
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    await loginThroughUi(page, viewer, a.id);
    await page.goto(`/title/${series.id}`);
    const row = page.locator(`[data-testid="episode"][data-content-id="${one.id}"]`);
    await ui(row.getByTestId("play-button")).toBeEnabled();
    await row.getByTestId("play-button").click();
    await page.waitForFunction(() => location.pathname.startsWith(`/watch/`), undefined, { timeout: 30_000 });
    const state = await waitPlaying(page, 1);
    // Seek to just before the end and let it finish.
    await page.evaluate(() => {
      const video = document.querySelector<HTMLVideoElement>('[data-testid="video"]');
      if (video) {
        video.currentTime = Math.max(0, video.duration - 1.2);
      }
    });
    expect(state.duration).toBeGreaterThan(5);
    await ui(page.getByTestId("next-episode")).toBeVisible();
    expect(new URL(page.url()).pathname).toBe(`/watch/${one.id}`);
    await page.getByTestId("next-episode-play").click();
    await page.waitForFunction((id) => location.pathname === `/watch/${id}`, two.id, { timeout: 30_000 });
    await waitPlaying(page, 1);
    await traffic.settle();
    expect(traffic.admissions()).toHaveLength(2);
    expect(traffic.csrfRejections(), "403 ACCESS_DENIED on a Core POST (C1)").toEqual([]);
    expect(traffic.sequenceProblems(), "sequence order; a repeat must be an identical retry").toEqual([]);
    recordPass(10, "seeking to just before the end of episode 1 offered next-episode, whose play button opened episode 2 "
      + "and played it", [traffic]);
  } finally {
    await context.close();
  }
});

test("11 a changed media binding is recovered with one new admission and playback continues", async ({ browser }) => {
  const { premium_viewer_2: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a } = premiumProfiles("premium_viewer_2");
  const context = await newContext(browser);
  const page = await context.newPage();
  const traffic = new Traffic();
  traffic.attach(page);
  try {
    await loginThroughUi(page, viewer, a.id);
    await openTitleAndPlay(page, free.id);
    await waitPlaying(page, 3);
    await traffic.settle();
    const firstSession = traffic.admissions()[0]?.sessionId;
    expect(firstSession).toBeTruthy();

    // MOCKED: Core cannot be made to change the active binding of a published title here (a second asset cannot be
    // uploaded for or bound to published content), so the next progress report is answered with the problem Core
    // sends in that case. The re-admission that follows is real.
    let answered = false;
    await page.route("**/api/core/v1/playback/sessions/*/progress", async (route) => {
      if (!answered) {
        answered = true;
        await route.fulfill({ status: 409, contentType: "application/problem+json",
          body: problem(409, "Conflict", "MEDIA_BINDING_CHANGED") });
      } else {
        await route.continue();
      }
    });
    const mark = traffic.mark();
    await page.evaluate(() => document.querySelector<HTMLVideoElement>('[data-testid="video"]')?.pause());
    await poll(async () => answered || null, "No progress request to answer", 20);
    await poll(async () => {
      await traffic.settle();
      return traffic.admissions(mark).length >= 1 || null;
    }, "The page did not admit again", 40);
    await sleep(4_000);
    await traffic.settle();
    const readmissions = traffic.admissions(mark);
    expect(readmissions, "new admissions after the binding change").toHaveLength(1);
    expect(readmissions[0].status).toBe(201);
    expect(readmissions[0].sessionId).toBeTruthy();
    expect(readmissions[0].sessionId).not.toBe(firstSession);
    await expect(page.getByTestId("player-error")).toHaveCount(0);

    // The new session plays: the manifest path names it, and the clock advances.
    const manifests = traffic.streamResponses(mark).filter((e) => e.template.endsWith("master.m3u8"));
    expect(manifests.some((e) => e.ids[0] === readmissions[0].sessionId && e.status === 200)).toBe(true);
    await page.evaluate(() => document.querySelector<HTMLVideoElement>('[data-testid="video"]')?.play());
    const resumed = await waitPlaying(page, 0.5);
    const t0 = resumed.t;
    await poll(async () => ((await videoState(page))?.t ?? 0) > t0 + 3 || null, "Playback did not continue", 30);
    await expect(page.getByTestId("player-error")).toHaveCount(0);
    recordPass(11, "after Core answered MEDIA_BINDING_CHANGED to a progress report the page made exactly one new admission "
      + "(a new session in the manifest path), kept playing and showed no player-error", [traffic],
    { mocked: ["POST /playback/sessions/{id}/progress answered once with 409 MEDIA_BINDING_CHANGED (the re-admission and playback after it are real)"] });
  } finally {
    await context.close();
  }
});
