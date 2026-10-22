import { createHash, randomBytes, randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, request, test, type BrowserContext, type Page } from "@playwright/test";

// M33: one clip uploaded directly from a browser and played from the real generated HLS through the repository
// ingress. Run by infra/smoke/check-media-m29.ps1 -Milestone 33 against its disposable stack. No request is routed,
// mocked or fulfilled here; READY comes from Media's worker and Kafka, never from this test. Nothing printed or
// recorded carries a password, a ticket, a cookie value, a signed URL or a response body.

const TICKET_COOKIE = "__Secure-LIBRA_PLAYBACK";
const SESSION = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const LOOPBACK = /^http:\/\/127\.0\.0\.1:[0-9]+$/;
const CLIP_SECONDS = 12;

function setting(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set; run infra/smoke/check-media-m29.ps1 -Milestone 33`);
  }
  return value;
}

const baseUrl = setting("LIBRA_M33_BASE_URL");
const mailUrl = setting("LIBRA_M33_MAIL_URL");
const storageOrigin = setting("LIBRA_M33_STORAGE_ORIGIN");
const evidencePath = setting("LIBRA_M33_EVIDENCE");
const work = setting("LIBRA_M33_WORK");
for (const url of [baseUrl, mailUrl, storageOrigin]) {
  if (!LOOPBACK.test(url)) {
    throw new Error("M33 addresses must be loopback");
  }
}
const harnessUrl = baseUrl + "/m33/";

const checks: string[] = [];
function passed(message: string) {
  checks.push(message);
  console.log(`PASS: ${message}`);
}

function fingerprint(value: string) {
  return createHash("sha256").update(value).digest("hex");
}

// ticket: the SHA-256 fingerprint of the ticket cookie the browser sent, or null; never the value itself.
type StreamResponse = { name: string; status: number; ticket: string | null };

// File name, status and sent-ticket fingerprint of every stream response the page receives, in request order.
function watchStreams(page: Page) {
  const pending: Promise<StreamResponse>[] = [];
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (url.pathname.startsWith("/api/media/v1/streams/")) {
      pending.push(response.request().allHeaders().then((headers) => {
        const sent = (headers["cookie"] ?? "").split(/;\s*/).find((c) => c.startsWith(TICKET_COOKIE + "="));
        return {
          name: url.pathname.split("/").pop() ?? "",
          status: response.status(),
          ticket: sent ? fingerprint(sent.slice(TICKET_COOKIE.length + 1)) : null,
        };
      }));
    }
  });
  return () => Promise.all(pending);
}

async function openHarness(context: BrowserContext) {
  const page = await context.newPage();
  const response = await page.goto(harnessUrl);
  expect(response?.status(), "harness page").toBe(200);
  await page.waitForFunction(() => "m33" in window && "Hls" in window);
  return page;
}

async function core(page: Page, method: string, route: string, body?: unknown, headers?: Record<string, string>) {
  return page.evaluate(([m, r, b, h]) => window.m33.call(m, r, b ?? undefined, h ?? undefined),
    [method, route, body ?? null, headers ?? null] as const);
}

async function coreStatus(page: Page, method: string, route: string, body?: unknown, headers?: Record<string, string>) {
  return page.evaluate(([m, r, b, h]) => window.m33.status(m, r, b ?? undefined, h ?? undefined),
    [method, route, body ?? null, headers ?? null] as const);
}

async function poll<T>(probe: () => Promise<T | null>, message: string | (() => string), seconds: number): Promise<T> {
  const deadline = Date.now() + seconds * 1000;
  for (;;) {
    const value = await probe();
    if (value !== null) {
      return value;
    }
    if (Date.now() > deadline) {
      throw new Error(typeof message === "string" ? message : message());
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
}

async function verificationToken(email: string): Promise<string> {
  const mail = await request.newContext({ baseURL: mailUrl });
  try {
    return await poll(async () => {
      const listing = await (await mail.get("/api/v1/messages")).json();
      for (const message of listing.messages ?? []) {
        if ((message.To ?? []).some((to: { Address: string }) => to.Address === email)) {
          const text: string = (await (await mail.get(`/api/v1/message/${message.ID}`)).json()).Text ?? "";
          const match = /token=([A-Za-z0-9_-]{43})/.exec(text);
          if (match) {
            return match[1];
          }
        }
      }
      return null;
    }, "No verification mail", 60);
  } finally {
    await mail.dispose();
  }
}

type PlaybackState = Awaited<ReturnType<typeof window.m33.state>>;

async function playbackState(page: Page): Promise<PlaybackState> {
  return page.evaluate(() => window.m33.state());
}

async function waitForPlayback(page: Page, message: string, accept: (state: PlaybackState) => boolean) {
  let last: PlaybackState | null = null;
  return poll(async () => {
    last = await playbackState(page);
    if (last.errors.some((e) => e.fatal)) {
      throw new Error(`${message}: fatal player error ${JSON.stringify(last.errors)}`);
    }
    return accept(last) ? last : null;
  }, () => `${message}: ${JSON.stringify(last)}`, 60);
}

test("M33 browser upload, publication and protected HLS playback", async ({ browser }) => {
  const browserVersion = browser.version();
  rmSync(work, { recursive: true, force: true });
  mkdirSync(work, { recursive: true });
  const clip = path.join(work, "source.mp4");
  execFileSync("ffmpeg", ["-v", "error", "-nostdin", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", String(CLIP_SECONDS), "-c:v", "libx264",
    "-profile:v", "high", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "48k", "-shortest", "-movflags", "+faststart",
    clip], { stdio: "ignore", timeout: 120_000 });

  // Administrator: real login, catalog entry, upload chosen through the file input, direct PUT, completion.
  const adminContext = await browser.newContext();
  const admin = await openHarness(adminContext);
  expect(await coreStatus(admin, "POST", "/auth/login", {
    email: setting("LIBRA_M33_ADMIN_EMAIL"), password: setting("LIBRA_M33_ADMIN_PASSWORD"),
  }), "admin login").toBe(200);
  const created = await core(admin, "POST", "/admin/catalog", {
    kind: "MOVIE", metadata: {
      title: "M33 browser fixture", description: "Disposable browser playback", genres: ["drama"],
      releaseYear: 2026, language: "en", credits: [], imageReferences: [], accessTier: "PREMIUM",
    },
  });
  expect(created.status, "catalog creation").toBe(201);
  const contentId: string = created.body.id;
  await admin.locator("#source").setInputFiles(clip);
  const uploaded = await admin.evaluate(([id, version, origin]) => window.m33.upload(id, version, origin),
    [contentId, created.body.version as number, storageOrigin] as const);
  expect(uploaded, "browser upload").toMatchObject({ step: "complete", status: 202, assetState: "QUEUED" });
  const clipBytes = statSync(clip).size;
  passed("ADMIN logged in from the browser; the file input's clip went through Core's upload intent and grant, a "
    + "CORS preflight and a direct signed PUT to the storage gateway, then completion queued processing");

  // The bucket's CORS rule admits the page's exact origin for the signed PUT, and nothing wider. These preflights
  // come from the test, not a browser: a page cannot choose its Origin.
  const storage = await request.newContext({ baseURL: storageOrigin });
  try {
    const preflight = async (origin: string, method: string, headers: string) => {
      const response = await storage.fetch(`/libra-source/staging/${randomUUID()}/source.mp4`, {
        method: "OPTIONS", headers: { Origin: origin, "Access-Control-Request-Method": method,
          "Access-Control-Request-Headers": headers },
      });
      const allowOrigin = response.headers()["access-control-allow-origin"] ?? "";
      const allowMethods = (response.headers()["access-control-allow-methods"] ?? "").toUpperCase().split(/\s*,\s*/);
      // What a browser checks before it sends the request.
      return (allowOrigin === origin || allowOrigin === "*") && (allowMethods.includes(method) || allowMethods.includes("*"));
    };
    const signed = uploaded.signedHeaderNames!.join(",").toLowerCase();
    expect(await preflight(baseUrl, "PUT", signed), "PUT preflight from the page origin").toBe(true);
    for (const [origin, method] of [["http://localhost:" + new URL(baseUrl).port, "PUT"],
      ["http://127.0.0.1:1", "PUT"], [baseUrl, "DELETE"]] as const) {
      expect(await preflight(origin, method, signed), `${method} preflight from ${origin}`).toBe(false);
    }
  } finally {
    await storage.dispose();
  }
  passed("Storage CORS admits the page's exact origin for the signed PUT; another port, localhost and DELETE are "
    + "not admitted");

  await poll(async () => {
    const current = await core(admin, "GET", `/admin/uploads/${uploaded.uploadId}`);
    if (current.status >= 500) {
      return null;
    }
    expect(current.status).toBe(200);
    expect(current.body.failureCode ?? null, "processing failure").toBeNull();
    return current.body.assetState === "READY" ? true : null;
  }, "Media worker did not make the upload READY", 300);
  const projected = await poll(async () => {
    const current = await core(admin, "GET", `/admin/catalog/${contentId}`);
    return current.body?.candidate?.state === "READY" ? current.body : null;
  }, "Core did not project Media's READY event", 120);
  passed("Readiness polled from the browser: Media's worker made the upload READY and Core projected it from Kafka");

  const published = await core(admin, "POST", `/admin/catalog/${contentId}/publication`,
    { expectedVersion: projected.version });
  expect(published.status, "publication").toBe(200);
  await adminContext.close();
  passed("Explicit publication through Core from the administrator's browser session");

  // Viewer: registration, the emailed verification token, login, simulated Premium, admission.
  const viewerContext = await browser.newContext();
  const viewer = await openHarness(viewerContext);
  const email = `viewer-${randomUUID().replaceAll("-", "")}@m33.example.test`;
  const password = randomBytes(24).toString("base64url");
  expect(await coreStatus(viewer, "POST", "/auth/register",
    { email, displayName: "M33 viewer", password }), "registration").toBe(202);
  expect(await coreStatus(viewer, "POST", "/auth/login", { email, password }), "unverified login").toBe(200);
  const unverifiedProfiles = await core(viewer, "GET", "/profiles");
  const unverified = await core(viewer, "POST", "/playback/sessions",
    { profileId: unverifiedProfiles.body[0].id, contentId });
  expect({ status: unverified.status, code: unverified.body?.code }, "unverified admission")
    .toEqual({ status: 403, code: "EMAIL_VERIFICATION_REQUIRED" });
  expect((await viewerContext.cookies()).some((c) => c.name === TICKET_COOKIE), "refused ticket").toBe(false);
  expect(await coreStatus(viewer, "POST", "/auth/verify-email",
    { token: await verificationToken(email) }), "email verification").toBe(204);
  expect(await coreStatus(viewer, "POST", "/auth/login", { email, password }), "viewer login").toBe(200);
  expect(await coreStatus(viewer, "POST", "/subscriptions/simulate", { plan: "PREMIUM_30_DAYS" },
    { "Idempotency-Key": randomUUID() }), "simulated Premium").toBe(200);
  const catalog = await core(viewer, "GET", `/catalog/${contentId}`);
  expect(catalog.body?.mediaReady, "public catalog readiness").toBe(true);
  const profiles = await core(viewer, "GET", "/profiles");
  const admitted = await core(viewer, "POST", "/playback/sessions",
    { profileId: profiles.body[0].id, contentId });
  expect(admitted.status, "playback admission").toBe(201);
  const sessionId: string = admitted.body.sessionId;
  expect(sessionId).toMatch(SESSION);
  const streamPath = `/api/media/v1/streams/${sessionId}/`;
  expect(admitted.body.manifestPath).toBe(streamPath + "master.m3u8");
  const tickets = (await viewerContext.cookies(baseUrl + streamPath)).filter((c) => c.name === TICKET_COOKIE);
  expect(tickets.length, "one ticket cookie").toBe(1);
  expect({ path: tickets[0].path, secure: tickets[0].secure, httpOnly: tickets[0].httpOnly,
    sameSite: tickets[0].sameSite }).toEqual({ path: streamPath, secure: true, httpOnly: true, sameSite: "Lax" });
  const firstTicket = fingerprint(tickets[0].value);
  passed("Viewer registered in the browser and was refused admission (EMAIL_VERIFICATION_REQUIRED, no ticket) until "
    + "it verified the emailed token; after login and simulated Premium, admission stored the HttpOnly Secure SameSite=Lax ticket scoped to the session's stream path");

  // Playback: hls.js through the ingress with the browser's own cookie.
  const collectStreams = watchStreams(viewer);
  const started = await viewer.evaluate((manifest) => window.m33.play(manifest), admitted.body.manifestPath as string);
  expect(started.supported, "MSE playback support").toBe(true);
  const playing = await waitForPlayback(viewer, "Playback did not advance",
    (s) => s.currentTime > 3 && s.presentedFrames > 60 && s.readyState >= 3);
  expect(playing.levels).toEqual([expect.objectContaining({ height: 720 })]);
  expect(playing.levels[0].videoCodec).toMatch(/^avc1\./);
  expect(playing.levels[0].audioCodec).toMatch(/^mp4a\./);
  expect({ width: playing.videoWidth, height: playing.videoHeight }).toEqual({ width: 1280, height: 720 });
  expect(Math.abs(playing.duration - CLIP_SECONDS)).toBeLessThan(0.5);
  const frame = await viewer.evaluate(() => window.m33.sample());
  expect(frame.distinctColours, "decoded picture").toBeGreaterThan(32);
  passed(`hls.js ${started.version} played the master playlist through Nginx: 720p H.264 + AAC, playhead `
    + `${playing.currentTime.toFixed(1)} s, ${playing.presentedFrames} frames presented, ${frame.distinctColours} `
    + "distinct colours in the sampled frame");

  // Hold the presentation with the last segment unfetched and nothing in flight, renew through Core, then seek
  // into the last segment while paused: it can only be requested after renewal, and the request must carry the
  // renewed ticket.
  await viewer.evaluate(() => window.m33.hold());
  // Lets a response event of a load aborted by hold() reach the test before the renewal boundary is taken.
  await viewer.waitForTimeout(500);
  const beforeRenewal = await playbackState(viewer);
  const lastSegment = beforeRenewal.segments - 1;
  expect(beforeRenewal.segments, "segments in the rendition").toBeGreaterThan(1);
  expect(beforeRenewal.loadingSegments, "segment requests in flight at renewal").toBe(0);
  expect(beforeRenewal.loadedSegments, "last segment unfetched before renewal").not.toContain(lastSegment);
  expect(beforeRenewal.currentTime, "held before the seek target").toBeLessThan(CLIP_SECONDS - 6);
  const responsesBeforeRenewal = (await collectStreams()).length;
  expect(await viewer.evaluate((id) => window.m33.renew(id), sessionId), "renewal").toBe(200);
  const renewed = (await viewerContext.cookies(baseUrl + streamPath)).filter((c) => c.name === TICKET_COOKIE);
  expect(renewed.length, "one renewed ticket cookie").toBe(1);
  const renewedTicket = fingerprint(renewed[0].value);
  expect(renewedTicket, "renewed ticket").not.toBe(firstTicket);
  const target = CLIP_SECONDS - 4;
  await viewer.evaluate((seconds) => window.m33.resumeAt(seconds), target);
  const sought = await waitForPlayback(viewer, "Playback did not continue after the seek with the renewed ticket",
    (s) => s.seeked > beforeRenewal.seeked && !s.seeking && s.currentTime > target + 1
      && s.presentedFrames > beforeRenewal.presentedFrames + 20 && s.segmentsAfterRenewal.includes(lastSegment));
  expect(sought.seekedTo ?? 0, "seek landed at its target").toBeGreaterThanOrEqual(target - 0.1);
  passed(`Held at ${beforeRenewal.currentTime.toFixed(1)} s with segment ${lastSegment} unfetched and none in `
    + `flight; renewal through Core replaced the ticket cookie; a paused seek to ${target} s landed at `
    + `${sought.seekedTo?.toFixed(1)} s, fetched segment(s) ${sought.segmentsAfterRenewal.join(", ")} of `
    + `${sought.segments} and playback continued to ${sought.currentTime.toFixed(1)} s with new frames`);

  const ended = await waitForPlayback(viewer, "Playback did not reach the end", (s) => s.ended);
  const finalState = await playbackState(viewer);
  expect(finalState.errors.filter((e) => e.fatal)).toEqual([]);
  const streams = await collectStreams();
  expect(streams.filter((s) => s.status !== 200).map((s) => s.status), "stream responses").toEqual([]);
  expect(streams.filter((s) => s.name === "master.m3u8").length).toBe(1);
  // Fingerprints only: which ticket each request carried, never its value.
  const beforeTickets = streams.slice(0, responsesBeforeRenewal).map((s) => s.ticket === firstTicket);
  const afterRenewal = streams.slice(responsesBeforeRenewal);
  expect(beforeTickets.every(Boolean), "requests before renewal carried the first ticket").toBe(true);
  expect(afterRenewal.filter((s) => s.name.startsWith("segment-")).length, "segments after renewal")
    .toBeGreaterThan(0);
  expect(afterRenewal.every((s) => s.ticket === renewedTicket), "requests after renewal carried the renewed ticket")
    .toBe(true);
  const playedResponses = streams.length;
  passed(`Played to the end (${ended.currentTime.toFixed(1)} s, ${finalState.decodedFrames} decoded, `
    + `${finalState.droppedFrames} dropped); all ${playedResponses} stream responses were 200; the `
    + `${responsesBeforeRenewal} before renewal carried the first ticket and the ${afterRenewal.length} after it the `
    + "renewed one");

  // The same request from the same page without its cookies is refused.
  for (const name of ["master.m3u8", "rendition.m3u8", "segment-00000.ts"]) {
    expect(await viewer.evaluate((p) => window.m33.anonymousStream(p), streamPath + name))
      .toEqual({ status: 401, type: expect.stringMatching(/^application\/problem\+json/), code: "AUTHENTICATION_REQUIRED" });
  }
  const anonymous = (await collectStreams()).slice(playedResponses);
  expect(anonymous.map((s) => [s.status, s.ticket]), "refused stream responses")
    .toEqual([[401, null], [401, null], [401, null]]);
  passed("The page's own stream requests without cookies were refused 401");
  await viewerContext.close();

  mkdirSync(path.dirname(evidencePath), { recursive: true });
  writeFileSync(evidencePath, JSON.stringify({
    result: "MEDIA_M33_BROWSER_PASS",
    checkedAt: new Date().toISOString(),
    browser: { name: "Google Chrome", channel: "chrome", version: browserVersion, headless: true },
    player: { name: "hls.js", version: started.version },
    clip: { seconds: CLIP_SECONDS, bytes: clipBytes, height: 720 },
    upload: { signedHeaderNames: uploaded.signedHeaderNames },
    playback: {
      presentedFrames: finalState.presentedFrames, decodedFrames: finalState.decodedFrames,
      droppedFrames: finalState.droppedFrames, playerStreamResponses: playedResponses,
      responsesBeforeRenewal, responsesAfterRenewal: afterRenewal.length, refusedWithoutCookies: anonymous.length,
      heldAt: Number(beforeRenewal.currentTime.toFixed(2)), seekedTo: Number((sought.seekedTo ?? 0).toFixed(2)),
      segments: sought.segments, segmentsAfterRenewal: sought.segmentsAfterRenewal,
      sampledDistinctColours: frame.distinctColours,
    },
    checks,
    boundary: "Local disposable Compose: real compose.yaml and nginx.conf, packaged Core and Media JARs, PostgreSQL, "
      + "Kafka, Mailpit, SeaweedFS behind the storage gateway; a static stand-in for the web upstream serving only "
      + "the test-only harness; headless Google Chrome driven by Playwright; no request routed or fulfilled by the test",
  }, null, 2) + "\n", "utf-8");
});

declare global {
  interface Window {
    m33: {
      call(method: string, path: string, body?: unknown, headers?: Record<string, string>):
        // Core's JSON answers; the contracts under contracts/http define their shapes.
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        Promise<{ status: number; body: any }>;
      status(method: string, path: string, body?: unknown, headers?: Record<string, string>): Promise<number>;
      upload(contentId: string, expectedVersion: number, storageOrigin: string): Promise<{
        step: string; status: number; uploadId?: string; assetState?: string; byteLength?: number;
        sha256?: string; signedHeaderNames?: string[];
      }>;
      play(manifestPath: string): { supported: boolean; version?: string };
      sample(): { distinctColours: number; meanLuma: number };
      state(): {
        currentTime: number; duration: number; paused: boolean; ended: boolean; seeking: boolean; readyState: number;
        videoWidth: number; videoHeight: number; presentedFrames: number; decodedFrames: number;
        droppedFrames: number; segments: number; seeked: number; seekedTo: number | null; fragments: number;
        loadingSegments: number; loadedSegments: number[]; segmentsAfterRenewal: number[];
        errors: { type: string; details: string; fatal: boolean; status: number | null }[];
        levels: { height: number; videoCodec: string; audioCodec: string }[];
      };
      hold(): void;
      resumeAt(seconds: number): void;
      renew(sessionId: string): Promise<number>;
      anonymousStream(path: string): Promise<{ status: number; type: string; code: string | null }>;
    };
  }
}
