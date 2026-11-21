import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import {
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Page,
  type Request,
} from "@playwright/test";

// RA03 browser acceptance support. Run by infra/smoke/check-web-ra03.ps1 against its disposable stack: the real web
// image, Core, Media (real HLS), Analytics, Kafka and PostgreSQL behind the repository ingress. Nothing printed or
// recorded here carries a password, ticket, cookie value, signed URL or response body: traffic is reduced to method,
// path template (UUIDs become {id}), status and a few parsed progress fields.

const LOOPBACK = /^http:\/\/127\.0\.0\.1:[0-9]+$/;
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g;
export const EXPECTED_TESTS = 18;

function setting(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set; run infra/smoke/check-web-ra03.ps1`);
  }
  return value;
}

export const baseUrl = setting("LIBRA_RA03_BASE_URL");
export const evidencePath = setting("LIBRA_RA03_EVIDENCE");
const seedPath = setting("LIBRA_RA03_SEED");
const runId = process.env.LIBRA_RA03_RUN_ID || undefined;
if (!LOOPBACK.test(baseUrl)) {
  throw new Error("RA03 addresses must be loopback");
}

// ---------------------------------------------------------------------------------------------------------------
// Seed

export type SeedContent = {
  id: string;
  title: string;
  kind: string;
  tier: string;
  durationSeconds: number | null;
  parentKey: string | null;
  ordinal: number | null;
};
export type SeedViewer = {
  email: string;
  password: string;
  displayName: string;
  verified: boolean;
  premium: boolean;
  profiles: { id: string; name: string }[];
};
export type Seed = {
  admin: { email: string; password: string };
  contents: Record<"free_movie" | "premium_movie" | "series" | "season" | "episode_1" | "episode_2" | "hidden_movie", SeedContent>;
  viewers: Record<"premium_viewer" | "premium_viewer_2" | "premium_viewer_3" | "free_viewer" | "unverified_viewer", SeedViewer>;
};

let cachedSeed: Seed | null = null;
export function seed(): Seed {
  cachedSeed ??= JSON.parse(readFileSync(seedPath, "utf-8")) as Seed;
  return cachedSeed;
}

/** The premium viewer's two profiles: A is the default one, B is the one the seed named "Thứ hai". */
export function premiumProfiles(key: "premium_viewer" | "premium_viewer_2" | "premium_viewer_3" = "premium_viewer") {
  const { profiles } = seed().viewers[key];
  const b = profiles.find((p) => p.name === "Thứ hai") ?? profiles[1];
  const a = profiles.find((p) => p.id !== b.id) ?? profiles[0];
  return { a, b };
}

// ---------------------------------------------------------------------------------------------------------------
// Login budget. Core limits logins to 10 per email per 15 minutes (and 30 per minute per address), so no account may
// log in more than 8 times in one run. Logins counted: loginThroughUi and apiSession (UI or API).
//
//   account            tests (logins each)                                   total
//   premium_viewer     1 (1), 2 (1), 3 (2: API setup + UI), 5 (1)            5
//   premium_viewer_2   6 (1), 9 (1), 10 (1), 11 (1), 12 (1)                  5
//   premium_viewer_3   13 (1), 14 (2: UI + API history), 15 (1), 16 (1),     6
//                      18 (1)
//   free_viewer        5 (1), 17 (1)                                         2
//   unverified_viewer  17 (1)                                                1
//   admin              seed: 1 + 5 upload threads (seed side, not counted    7
//                      here), test 8 (1, one session for the file)
//
// Registrations in the seed: 5 (limit 10 per address per 15 minutes).

const LOGIN_LIMIT = 8;
const logins = new Map<string, number>();
function countLogin(email: string) {
  const n = (logins.get(email) ?? 0) + 1;
  logins.set(email, n);
  if (n > LOGIN_LIMIT) {
    throw new Error(`Login budget exceeded: one account logged in ${n} times (limit ${LOGIN_LIMIT}); spread the tests ` +
      "over the seeded accounts (see the table in support.ts)");
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Small utilities

export const ui = expect.configure({ timeout: 20_000 });

export function sleep(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function poll<T>(probe: () => Promise<T | null | undefined | false>, message: string | (() => string),
  seconds: number, intervalMs = 500): Promise<T> {
  const deadline = Date.now() + seconds * 1000;
  for (;;) {
    const value = await probe();
    if (value !== null && value !== undefined && value !== false) {
      return value;
    }
    if (Date.now() > deadline) {
      throw new Error(typeof message === "string" ? message : message());
    }
    await sleep(intervalMs);
  }
}

export async function newContext(browser: Browser): Promise<BrowserContext> {
  const context = await browser.newContext({ baseURL: baseUrl, serviceWorkers: "block", acceptDownloads: false });
  context.setDefaultTimeout(30_000);
  context.setDefaultNavigationTimeout(45_000);
  return context;
}

// ---------------------------------------------------------------------------------------------------------------
// Traffic: what the page asked the ingress for, reduced to what the assertions and the evidence may keep.

export type ProgressBody = { sequence: number; state: string; positionMs: number; playedMs: number };
export type ProgressResult = {
  accepted?: boolean;
  sequence?: number;
  state?: string;
  positionMs?: number;
  watchedMs?: number;
  qualified?: boolean;
};
export type Entry = {
  n: number;
  at: number;
  method: string;
  /** Path with UUIDs replaced by {id} and segment numbers by {n}; never a query string. */
  template: string;
  /** The UUIDs the path held, in order. Kept in memory for matching only; never written out. */
  ids: string[];
  /** The raw path (no query), in memory only: used to tell repeated requests for one resource apart. */
  path: string;
  status: number | null;
  failed: boolean;
  req?: ProgressBody;
  res?: ProgressResult;
  /** Problem code of a 4xx/5xx Core answer. */
  code?: string;
  sessionId?: string;
};

const ADMISSION = "/api/core/v1/playback/sessions";
const SESSION_CALL = /^\/api\/core\/v1\/playback\/sessions\/\{id\}\/(progress|end|renew)$/;

export class Traffic {
  readonly entries: Entry[] = [];
  private readonly pending = new Map<Request, Entry>();
  private readonly jobs: Promise<void>[] = [];

  attach(page: Page) {
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.origin !== baseUrl || !url.pathname.startsWith("/api/")) {
        return;
      }
      const ids = url.pathname.match(UUID) ?? [];
      const entry: Entry = {
        n: this.entries.length,
        at: Date.now(),
        method: request.method(),
        template: url.pathname.replace(UUID, "{id}").replace(/segment-[0-9]+\.ts$/, "segment-{n}.ts"),
        ids,
        path: url.pathname,
        status: null,
        failed: false,
      };
      if (/\/(progress|end)$/.test(url.pathname) && request.method() === "POST") {
        try {
          const body = JSON.parse(request.postData() ?? "{}");
          entry.req = {
            sequence: Number(body.sequence),
            state: String(body.state),
            positionMs: Number(body.positionMs),
            playedMs: Number(body.playedMs),
          };
        } catch {
          // A body that is not JSON is simply not recorded.
        }
      }
      this.entries.push(entry);
      this.pending.set(request, entry);
    });
    page.on("response", (response) => {
      const entry = this.pending.get(response.request());
      if (!entry) {
        return;
      }
      entry.status = response.status();
      const isCore = entry.template.startsWith("/api/core/v1/");
      // Never the body of an authentication call: only these templates, and only whitelisted fields.
      const readable = isCore && !entry.template.startsWith("/api/core/v1/auth/") &&
        (entry.template === ADMISSION || SESSION_CALL.test(entry.template) ||
          entry.template.includes("/entitlements/") || entry.status >= 400);
      if (readable) {
        this.jobs.push(response.json().then((body) => {
          if (entry.status !== null && entry.status >= 400) {
            if (typeof body?.code === "string") {
              entry.code = body.code;
            }
          } else if (entry.template === ADMISSION) {
            entry.sessionId = typeof body?.sessionId === "string" ? body.sessionId : undefined;
          } else if (SESSION_CALL.test(entry.template)) {
            entry.res = {
              accepted: body?.accepted,
              sequence: body?.sequence,
              state: body?.state,
              positionMs: body?.positionMs,
              watchedMs: body?.watchedMs,
              qualified: body?.qualified,
            };
          }
        }).catch(() => undefined));
      }
    });
    page.on("requestfailed", (request) => {
      const entry = this.pending.get(request);
      if (entry) {
        entry.failed = true;
      }
    });
  }

  async settle() {
    await Promise.all(this.jobs);
  }

  mark() {
    return this.entries.length;
  }

  since(mark: number) {
    return this.entries.slice(mark);
  }

  admissions(mark = 0) {
    return this.since(mark).filter((e) => e.method === "POST" && e.template === ADMISSION);
  }

  /** C1 regression guard: a Core POST answered 403 ACCESS_DENIED means the CSRF credential was lost. */
  csrfRejections(mark = 0) {
    return this.since(mark).filter((e) => e.method === "POST" && e.template.startsWith("/api/core/v1/")
      && e.status === 403 && e.code === "ACCESS_DENIED");
  }

  /** Progress/end reports must have non-decreasing sequences; a repeated one must be an identical retry. */
  sequenceProblems(mark = 0) {
    const problems: string[] = [];
    // Sequences are per playback session: each new admission starts again at 1.
    const sessions = new Map<string, { seen: Map<number, string>; previous: number }>();
    for (const e of this.reports(mark)) {
      if (!e.req) {
        continue;
      }
      const sessionId = e.ids[0] ?? "";
      const state = sessions.get(sessionId) ?? { seen: new Map<number, string>(), previous: -1 };
      sessions.set(sessionId, state);
      const body = JSON.stringify(e.req);
      if (e.req.sequence < state.previous) {
        problems.push(`sequence ${e.req.sequence} after ${state.previous} in one session`);
      }
      const same = state.seen.get(e.req.sequence);
      if (same !== undefined && same !== body) {
        problems.push(`sequence ${e.req.sequence} repeated with a different body`);
      }
      state.seen.set(e.req.sequence, body);
      state.previous = Math.max(state.previous, e.req.sequence);
    }
    return problems;
  }

  refreshes(mark = 0) {
    return this.since(mark).filter((e) => e.method === "POST" && e.template === "/api/core/v1/auth/refresh");
  }

  reports(mark = 0) {
    return this.since(mark).filter((e) => e.method === "POST" && /\/(progress|end)$/.test(e.template));
  }

  sessionCalls(mark = 0) {
    return this.since(mark).filter((e) => e.method === "POST" && SESSION_CALL.test(e.template));
  }

  /** Progress and end reports the server accepted (200), in order. */
  accepted(mark = 0) {
    return this.reports(mark).filter((e) => e.status === 200 && e.res?.accepted === true);
  }

  streamResponses(mark = 0) {
    return this.since(mark).filter((e) => e.template.startsWith("/api/media/v1/streams/"));
  }

  summary() {
    return {
      admissions: this.admissions().length,
      refreshes: this.refreshes().length,
      progressReports: this.reports().filter((e) => e.status === 200).length,
    };
  }
}

// ---------------------------------------------------------------------------------------------------------------
// Driving the product UI

export async function loginThroughUi(page: Page, viewer: SeedViewer, profileId?: string) {
  countLogin(viewer.email);
  await page.goto("/login");
  await page.getByTestId("login-email").fill(viewer.email);
  await page.getByTestId("login-password").fill(viewer.password);
  await page.getByTestId("login-submit").click();
  await page.waitForFunction(() => !location.pathname.startsWith("/login"), undefined, { timeout: 30_000 });
  if (profileId) {
    await chooseProfile(page, profileId);
  }
}

/** Picks a profile when the page asks for one (/profiles), then waits for the header to show it. */
export async function chooseProfile(page: Page, profileId: string) {
  await page.waitForFunction(
    () => location.pathname === "/profiles" || !!document.querySelector('[data-testid="header-profile"]'),
    undefined, { timeout: 30_000 });
  if (new URL(page.url()).pathname === "/profiles") {
    await pickProfileOption(page, profileId);
  }
  await ui(page.getByTestId("header-profile")).toBeVisible();
}

async function pickProfileOption(page: Page, profileId: string) {
  await page.locator(`[data-testid="profile-option"][data-profile-id="${profileId}"]`).click();
  await page.waitForFunction(() => location.pathname !== "/profiles", undefined, { timeout: 30_000 });
}

export async function switchProfile(page: Page, profileId: string) {
  await page.getByTestId("header-switch-profile").click();
  await page.waitForFunction(() => location.pathname === "/profiles", undefined, { timeout: 30_000 });
  await pickProfileOption(page, profileId);
}

export async function videoState(page: Page) {
  return page.evaluate(() => {
    const v = document.querySelector<HTMLVideoElement>('[data-testid="video"]');
    return v ? { t: v.currentTime, paused: v.paused, seeking: v.seeking, ready: v.readyState, ended: v.ended, duration: v.duration } : null;
  });
}

/** Waits until the video is really playing: not paused, enough data, and the clock past `atLeastSeconds`. */
export async function waitPlaying(page: Page, atLeastSeconds = 2, seconds = 60) {
  let last: Awaited<ReturnType<typeof videoState>> = null;
  let nudged = false;
  const started = Date.now();
  return poll(async () => {
    last = await videoState(page);
    if (last && last.paused && !last.ended && !nudged && Date.now() - started > 8_000) {
      // The page did not start by itself: press Play the way a viewer would.
      nudged = true;
      await page.evaluate(() => document.querySelector<HTMLVideoElement>('[data-testid="video"]')?.play().catch(() => undefined));
    }
    return last && !last.paused && last.ready >= 3 && last.t >= atLeastSeconds ? last : null;
  }, () => `Video did not play: ${JSON.stringify(last)}`, seconds, 250);
}

/** Wall-clock time the video really advanced, sampled from the page: the yardstick for "watch time not inflated". */
export class PlayMeter {
  totalMs = 0;
  private last: { t: number; at: number } | null = null;
  private timer: NodeJS.Timeout | null = null;
  private busy = false;

  start(page: Page) {
    this.timer = setInterval(() => {
      if (this.busy) {
        return;
      }
      this.busy = true;
      videoState(page).then((s) => {
        const now = Date.now();
        if (s && !s.paused && !s.seeking && !s.ended && s.ready >= 3) {
          if (this.last && s.t > this.last.t) {
            this.totalMs += now - this.last.at;
          }
          this.last = { t: s.t, at: now };
        } else {
          this.last = null;
        }
      }).catch(() => {
        this.last = null;
      }).finally(() => {
        this.busy = false;
      });
    }, 200);
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    return this.totalMs;
  }
}

// ---------------------------------------------------------------------------------------------------------------
// API sessions (assertions and administrator setup only, never the behaviour under test). They run in a browser
// context so the browser's own cookie rules apply: the Secure identity cookies reach http://127.0.0.1 there.

export type Api = {
  get(route: string): Promise<{ status: number; body: unknown }>;
  send(method: "POST" | "PUT" | "DELETE", route: string, body?: unknown): Promise<{ status: number; body: unknown }>;
  dispose(): Promise<void>;
};

export async function apiSession(browser: Browser, email: string, password: string): Promise<Api> {
  countLogin(email);
  const context = await newContext(browser);
  const request: APIRequestContext = context.request;
  const csrfHeader = async () => {
    const token = await (await request.get("/api/core/v1/auth/csrf")).json();
    return { [token.headerName as string]: token.token as string };
  };
  const unpack = async (response: Awaited<ReturnType<APIRequestContext["get"]>>) => {
    const type = response.headers()["content-type"] ?? "";
    return { status: response.status(), body: type.includes("json") ? await response.json() : null };
  };
  const api: Api = {
    get: async (route) => unpack(await request.get("/api/core/v1" + route)),
    send: async (method, route, body) => unpack(await request.fetch("/api/core/v1" + route, {
      method,
      headers: await csrfHeader(),
      data: body,
    })),
    dispose: () => context.close(),
  };
  const login = await api.send("POST", "/auth/login", { email, password });
  if (login.status !== 200) {
    await context.close();
    throw new Error(`API login failed with ${login.status}`);
  }
  return api;
}

// ---------------------------------------------------------------------------------------------------------------
// Evidence: every test appends one line to a ledger next to the seed; the last spec writes the evidence file only
// when all tests of the suite are on it.

const ledgerPath = path.join(path.dirname(seedPath), "ledger.jsonl");

type Ledger = {
  test: number;
  line: string;
  admissions: number;
  refreshes: number;
  progressReports: number;
  mocked: string[];
  delayed: string[];
  dropped: string[];
  hls?: string | null;
};

export function resetLedger() {
  mkdirSync(path.dirname(ledgerPath), { recursive: true });
  rmSync(ledgerPath, { force: true });
}

export function recordPass(test: number, line: string, traffic: Traffic[], extra: Partial<Ledger> = {}) {
  const counts = { admissions: 0, refreshes: 0, progressReports: 0 };
  for (const t of traffic) {
    const s = t.summary();
    counts.admissions += s.admissions;
    counts.refreshes += s.refreshes;
    counts.progressReports += s.progressReports;
  }
  const entry: Ledger = { test, line, mocked: [], delayed: [], dropped: [], ...counts, ...extra };
  appendFileSync(ledgerPath, JSON.stringify(entry) + "\n");
  console.log(`PASS: RA03 test ${test}: ${line}`);
}

export async function hlsVersion(page: Page): Promise<string | null> {
  const exposed = await page.evaluate(() => (window as unknown as { Hls?: { version?: string } }).Hls?.version ?? null)
    .catch(() => null);
  if (exposed) {
    return exposed;
  }
  try {
    return JSON.parse(readFileSync(path.join(process.cwd(), "package.json"), "utf-8")).dependencies?.["hls.js"] ?? null;
  } catch {
    return null;
  }
}

export function writeEvidenceWhenComplete(browserVersion: string) {
  if (!existsSync(ledgerPath)) {
    return false;
  }
  const entries: Ledger[] = readFileSync(ledgerPath, "utf-8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  const numbers = new Set(entries.map((e) => e.test));
  if (entries.length !== EXPECTED_TESTS || numbers.size !== EXPECTED_TESTS) {
    return false;
  }
  entries.sort((a, b) => a.test - b.test);
  const sum = (pick: (e: Ledger) => number) => entries.reduce((total, e) => total + pick(e), 0);
  const evidence = {
    result: "WEB_RA03_BROWSER_PASS",
    ...(runId ? { runId } : {}),
    checkedAt: new Date().toISOString(),
    browser: `Google Chrome ${browserVersion} (headless, Playwright)`,
    hlsJs: entries.find((e) => e.hls)?.hls ?? null,
    checks: entries.map((e) => `RA03 test ${e.test}: ${e.line}`),
    counts: {
      tests: entries.length,
      admissions: sum((e) => e.admissions),
      refreshes: sum((e) => e.refreshes),
      progressReports: sum((e) => e.progressReports),
    },
    mockedSteps: entries.flatMap((e) => e.mocked),
    delayedSteps: entries.flatMap((e) => e.delayed),
    droppedSteps: entries.flatMap((e) => e.dropped),
    boundary: "Local disposable Compose: the real web image (Next.js standalone build), Core, Media with real FFmpeg HLS " +
      "output, Analytics, Kafka, PostgreSQL, Mailpit and SeaweedFS behind the repository Nginx on a loopback port; real " +
      "Google Chrome with hls.js; every step is real except those listed under mockedSteps, which answer one request " +
      "with a canned problem response while everything else stays real; delayedSteps and droppedSteps only slow or " +
      "abort a request.",
  };
  mkdirSync(path.dirname(evidencePath), { recursive: true });
  writeFileSync(evidencePath, JSON.stringify(evidence, null, 2) + "\n", "utf-8");
  return true;
}

export function todayUtc(offsetDays = 0) {
  return new Date(Date.now() + offsetDays * 86_400_000).toISOString().slice(0, 10);
}

export function problem(status: number, title: string, code: string) {
  return JSON.stringify({
    type: "about:blank", title, status, detail: title + ".", code,
    correlationId: "00000000-0000-4000-8000-000000000000",
  });
}
