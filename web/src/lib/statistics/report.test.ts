import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { CoreNetworkError, CoreProblem } from "../core/errors.ts";
import {
  addDays,
  DEFAULT_TOP,
  failureOf,
  fetchReport,
  formatUtc,
  formatWatched,
  isIsoDate,
  lastDays,
  rangeFromSearch,
  urlRange,
  rangeLength,
  reportPath,
  type StatisticsReport,
  utcToday,
  validateRange,
  viewOf,
} from "./report.ts";

const TODAY = "2026-11-23";

function report(overrides: Partial<StatisticsReport> = {}): StatisticsReport {
  return {
    status: "AVAILABLE",
    reason: null,
    from: "2026-11-22",
    to: TODAY,
    updatedAt: "2026-11-23T08:05:00Z",
    totals: { qualifiedViews: 2, uniqueViewers: 1, acceptedWatchedMs: 70_000 },
    days: [
      { day: "2026-11-22", qualifiedViews: 0, uniqueViewers: 0, acceptedWatchedMs: 0 },
      { day: TODAY, qualifiedViews: 2, uniqueViewers: 1, acceptedWatchedMs: 70_000 },
    ],
    topContent: [],
    ...overrides,
  };
}

function params(values: Record<string, string>) {
  const search = new URLSearchParams(values);
  return { get: (name: string) => search.get(name) };
}

describe("UTC dates", () => {
  test("today is the UTC date, not the local one", () => {
    assert.equal(utcToday(new Date("2026-11-23T23:30:00-05:00")), "2026-11-24");
    assert.equal(utcToday(new Date("2026-11-24T06:59:59+07:00")), "2026-11-23");
  });

  test("only real calendar dates are dates", () => {
    assert.equal(isIsoDate("2028-02-29"), true);
    assert.equal(isIsoDate("2026-02-29"), false);
    assert.equal(isIsoDate("2026-02-30"), false);
    assert.equal(isIsoDate("2026-1-05"), false);
    assert.equal(isIsoDate("2026-11-23T00:00:00Z"), false);
    assert.equal(isIsoDate(""), false);
  });

  test("days are added across months and years", () => {
    assert.equal(addDays("2026-12-31", 1), "2027-01-01");
    assert.equal(addDays("2026-03-01", -1), "2026-02-28");
    assert.equal(addDays(TODAY, 0), TODAY);
  });

  test("ranges are inclusive", () => {
    assert.equal(rangeLength({ from: TODAY, to: TODAY }), 1);
    assert.equal(rangeLength({ from: "2026-10-24", to: TODAY }), 31);
    assert.deepEqual(lastDays(TODAY, 7), { from: "2026-11-17", to: TODAY });
    assert.deepEqual(lastDays(TODAY, 1), { from: TODAY, to: TODAY });
  });
});

describe("range validation mirrors Core", () => {
  test("each rule", () => {
    assert.equal(validateRange({ from: "2026-10-24", to: TODAY }, TODAY), null);
    assert.equal(validateRange({ from: "2026-10-23", to: TODAY }, TODAY), "TOO_LONG");
    assert.equal(validateRange({ from: TODAY, to: "2026-11-22" }, TODAY), "ORDER");
    assert.equal(validateRange({ from: TODAY, to: "2026-11-24" }, TODAY), "FUTURE");
    assert.equal(validateRange({ from: "x", to: TODAY }, TODAY), "FORMAT");
    assert.equal(validateRange({ from: TODAY, to: "2026-02-30" }, TODAY), "FORMAT");
  });

  test("the URL range falls back to the default when missing or invalid", () => {
    const fallback = lastDays(TODAY, 7);
    assert.deepEqual(rangeFromSearch(params({}), TODAY), fallback);
    assert.deepEqual(rangeFromSearch(params({ from: "2026-01-01", to: "2026-03-01" }), TODAY), fallback);
    assert.deepEqual(rangeFromSearch(params({ from: TODAY, to: "2026-11-30" }), TODAY), fallback);
    assert.deepEqual(rangeFromSearch(params({ from: "2026-11-20", to: "2026-11-21" }), TODAY),
      { from: "2026-11-20", to: "2026-11-21" });
  });

  test("a replaced URL range is reported, a missing one is not", () => {
    assert.equal(urlRange(params({}), TODAY).replaced, false);
    assert.equal(urlRange(params({ from: "2026-01-01", to: TODAY }), TODAY).replaced, true);
    assert.equal(urlRange(params({ to: "2026-13-01" }), TODAY).replaced, true);
    assert.equal(urlRange(params({ from: "2026-11-20", to: "2026-11-21" }), TODAY).replaced, false);
  });
});

describe("request", () => {
  test("path carries the range and the top size", () => {
    assert.equal(reportPath({ from: "2026-11-17", to: TODAY }), `/admin/statistics/report?from=2026-11-17&to=${TODAY}&top=${DEFAULT_TOP}`);
    assert.equal(reportPath({ from: TODAY, to: TODAY }, 50), `/admin/statistics/report?from=${TODAY}&to=${TODAY}&top=50`);
  });

  test("fetch is a GET with the abort signal", async () => {
    const calls: unknown[][] = [];
    const signal = new AbortController().signal;
    const body = report();
    const result = await fetchReport({
      request: async <T,>(...args: unknown[]): Promise<T> => {
        calls.push(args);
        return body as T;
      },
    }, { from: "2026-11-22", to: TODAY }, signal);
    assert.equal(result, body);
    assert.deepEqual(calls, [["GET", `/admin/statistics/report?from=2026-11-22&to=${TODAY}&top=10`, { signal }]]);
  });
});

describe("view", () => {
  test("unavailable has no numbers, even if parts are present", () => {
    assert.deepEqual(viewOf(report({ status: "UNAVAILABLE", reason: "UNAVAILABLE", updatedAt: null, totals: null,
      days: null, topContent: null })), { kind: "unavailable", reason: "UNAVAILABLE" });
    assert.deepEqual(viewOf(report({ status: "UNAVAILABLE", reason: "INVALID_RESPONSE" })),
      { kind: "unavailable", reason: "INVALID_RESPONSE" });
    assert.equal(viewOf(report({ days: null })).kind, "unavailable");
    assert.equal(viewOf(report({ updatedAt: null })).kind, "unavailable");
  });

  test("available and stale keep the numbers", () => {
    const fresh = viewOf(report());
    assert.equal(fresh.kind, "data");
    if (fresh.kind === "data") {
      assert.equal(fresh.stale, false);
      assert.equal(fresh.empty, false);
      assert.equal(fresh.totals.qualifiedViews, 2);
    }
    const stale = viewOf(report({ status: "STALE", reason: "STALE" }));
    assert.equal(stale.kind === "data" && stale.stale, true);
  });

  test("empty means neither views nor watched time", () => {
    const zero = { qualifiedViews: 0, uniqueViewers: 0, acceptedWatchedMs: 0 };
    const empty = viewOf(report({ totals: zero }));
    assert.equal(empty.kind === "data" && empty.empty, true);
    const watchOnly = viewOf(report({ totals: { ...zero, acceptedWatchedMs: 1 } }));
    assert.equal(watchOnly.kind === "data" && watchOnly.empty, false);
  });
});

describe("failures and formatting", () => {
  test("failures map to what the page shows", () => {
    assert.equal(failureOf(new CoreProblem(401, "AUTHENTICATION_REQUIRED", null, null)), "LOGIN");
    assert.equal(failureOf(new CoreProblem(403, "ACCESS_DENIED", null, null)), "FORBIDDEN");
    assert.equal(failureOf(new CoreProblem(400, "INVALID_REQUEST", null, null)), "INVALID_RANGE");
    assert.equal(failureOf(new CoreProblem(500, null, null, null)), "ERROR");
    assert.equal(failureOf(new CoreNetworkError()), "NETWORK");
    assert.equal(failureOf(new Error("x")), "ERROR");
  });

  test("watched time in hours and minutes, rounded down", () => {
    assert.equal(formatWatched(0), "0 phút");
    assert.equal(formatWatched(59_999), "0 phút");
    assert.equal(formatWatched(60_000), "1 phút");
    assert.equal(formatWatched(3_660_000), "1 giờ 01 phút");
  });

  test("instants are shown in UTC", () => {
    assert.equal(formatUtc("2026-11-23T15:05:09+07:00"), "2026-11-23 08:05 UTC");
    assert.equal(formatUtc("not a date"), "not a date");
  });
});
