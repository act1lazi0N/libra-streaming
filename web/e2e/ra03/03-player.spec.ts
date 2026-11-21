import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import {
  PlayMeter, Traffic, apiSession, hlsVersion, loginThroughUi, newContext, poll, premiumProfiles, recordPass, seed,
  todayUtc, ui, videoState, waitPlaying,
} from "./support";

// RA03.3: the player against real HLS. One premium viewer, profile A, one playback session across tests 6 and 7.

test.describe.configure({ mode: "serial" });
test.setTimeout(180_000);

let context: BrowserContext;
let page: Page;
const traffic = new Traffic();
const meter = new PlayMeter();
let hls: string | null = null;

test.beforeAll(async ({ browser }) => {
  context = await newContext(browser);
  page = await context.newPage();
  traffic.attach(page);
});

test.afterAll(async () => {
  meter.stop();
  await context?.close();
});

test("6 play the free movie from the detail page: real HLS, one admission, increasing progress, first PLAYING", async () => {
  const { premium_viewer_2: viewer } = seed().viewers;
  const free = seed().contents.free_movie;
  const { a } = premiumProfiles("premium_viewer_2");
  await loginThroughUi(page, viewer, a.id);
  await page.goto(`/title/${free.id}`);
  await ui(page.getByTestId("play-button")).toBeEnabled();
  const mark = traffic.mark();
  await page.getByTestId("play-button").click();
  await page.waitForFunction(() => location.pathname.startsWith("/watch/"), undefined, { timeout: 30_000 });
  meter.start(page);
  const state = await waitPlaying(page, 3);
  expect(state.ready).toBeGreaterThanOrEqual(3);
  await poll(async () => {
    await traffic.settle();
    return traffic.accepted(mark).length >= 1;
  }, "No accepted progress report", 30);
  await traffic.settle();

  const streams = traffic.streamResponses(mark);
  expect(streams.length, "stream responses seen").toBeGreaterThan(3);
  expect(streams.filter((e) => e.status !== 200).map((e) => `${e.template} ${e.status}`), "non-200 stream responses").toEqual([]);
  expect(traffic.admissions(mark), "admission requests").toHaveLength(1);
  const reports = traffic.reports(mark);
  const sequences = reports.map((e) => e.req?.sequence ?? 0);
  expect(sequences.length).toBeGreaterThan(0);
  expect(traffic.sequenceProblems(mark), "sequence order; a repeat must be an identical retry").toEqual([]);
  expect(traffic.csrfRejections(mark), "403 ACCESS_DENIED on a Core POST (C1)").toEqual([]);
  expect(reports[0].req?.state, "first progress report").toBe("PLAYING");
  hls = await hlsVersion(page);
  recordPass(6, "the free movie played from real HLS (every stream response 200) after one admission; progress sequences "
    + "strictly increase and the first report is PLAYING", [traffic], { hls });
});

test("7 seek is not watch time; the watch time stays within what really played; the view qualifies at 30 s", async () => {
  const mark = traffic.mark();
  const before = await videoState(page);
  expect(before).not.toBeNull();
  // A viewer's seek forward (the clip is 40 s; a 5 s jump leaves 33 s to play, enough for 30 s of watch time).
  await page.evaluate(() => {
    const video = document.querySelector<HTMLVideoElement>('[data-testid="video"]');
    if (video) {
      video.currentTime += 5;
    }
  });
  await poll(async () => {
    await traffic.settle();
    return traffic.reports(mark).some((e) => e.req?.state === "SEEKING" && e.status === 200);
  }, "No SEEKING report after the seek", 30);

  const qualified = await poll(async () => {
    await traffic.settle();
    const accepted = traffic.accepted(0);
    const last = accepted[accepted.length - 1];
    return last?.res?.qualified === true ? last : null;
  }, () => `View did not qualify; last: ${JSON.stringify(traffic.accepted(0).slice(-1)[0]?.res)}`, 90, 1000);
  const playedMs = meter.stop();
  expect(qualified.res?.watchedMs ?? 0, "Core's watchedMs against measured playing time").toBeLessThanOrEqual(playedMs + 1500);
  expect(qualified.res?.watchedMs ?? 0).toBeGreaterThanOrEqual(30_000);
  const seeking = traffic.reports(mark).find((e) => e.req?.state === "SEEKING");
  expect(seeking?.res?.accepted).toBe(true);
  // Leave the page: the player stops and flushes.
  await page.goto("/");
  await traffic.settle();
  expect(traffic.csrfRejections(), "403 ACCESS_DENIED on a Core POST (C1)").toEqual([]);
  recordPass(7, "a viewer's seek produced an accepted SEEKING report; Core's watchedMs never exceeded the measured "
    + "playing time plus 1.5 s and the latest accepted response reported qualified at 30 s", [traffic]);
});

test("8 the statistics reached Analytics through Kafka", async ({ browser }) => {
  const { admin } = seed();
  const api = await apiSession(browser, admin.email, admin.password);
  try {
    let last = "";
    await poll(async () => {
      const result = await api.get(`/admin/statistics?from=${todayUtc(-1)}&to=${todayUtc()}`);
      const stats = result.body as { status?: string; reason?: string | null; qualifiedViews?: number | null; acceptedWatchedMs?: number | null };
      last = `${result.status} ${stats?.status} ${stats?.reason}`;
      return result.status === 200 && (stats.status === "AVAILABLE" || stats.status === "STALE")
        && (stats.acceptedWatchedMs ?? 0) > 0 && (stats.qualifiedViews ?? 0) >= 1;
    }, () => `Statistics never showed the view; last: ${last}`, 180, 3000);
  } finally {
    await api.dispose();
  }
  recordPass(8, "the administrator statistics became AVAILABLE or STALE with accepted watch time and a qualified view, "
    + "so the progress reached Analytics through Kafka", [traffic]);
});
