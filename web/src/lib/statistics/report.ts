import type { CoreClient } from "../core/client.ts";
import { CoreNetworkError, isProblem } from "../core/errors.ts";
import type { ContentKind } from "../core/types.ts";

// GET /v1/admin/statistics/report (contracts/http/core-analytics.v1.json 1.1.0, RA04.3).

export const MAX_RANGE_DAYS = 31;
export const DEFAULT_RANGE_DAYS = 7;
export const DEFAULT_TOP = 10;
const MS_PER_DAY = 86_400_000;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export type ReportStatus = "AVAILABLE" | "STALE" | "UNAVAILABLE";
export type ReportReason = "DISABLED" | "UNAVAILABLE" | "INVALID_RESPONSE" | "STALE";

export interface ReportCounts {
  qualifiedViews: number;
  uniqueViewers: number;
  acceptedWatchedMs: number;
}

export interface ReportDay extends ReportCounts {
  day: string;
}

export interface ReportedTitle extends ReportCounts {
  contentId: string;
  kind: ContentKind | null;
  title: string | null;
  published: boolean;
}

export interface StatisticsReport {
  status: ReportStatus;
  reason: ReportReason | null;
  from: string;
  to: string;
  updatedAt: string | null;
  totals: ReportCounts | null;
  days: ReportDay[] | null;
  topContent: ReportedTitle[] | null;
}

export interface DateRange {
  from: string;
  to: string;
}

/** Statistics days are UTC calendar dates, whatever the browser's time zone. */
export function utcToday(now: Date): string {
  return now.toISOString().slice(0, 10);
}

/** A real calendar date in YYYY-MM-DD form (rejects 2026-02-30, which Date would roll over). */
export function isIsoDate(value: string): boolean {
  if (!ISO_DATE.test(value)) {
    return false;
  }
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

export function addDays(day: string, days: number): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) + days * MS_PER_DAY).toISOString().slice(0, 10);
}

/** Number of dates in the inclusive range. */
export function rangeLength(range: DateRange): number {
  return Math.round((Date.parse(`${range.to}T00:00:00Z`) - Date.parse(`${range.from}T00:00:00Z`)) / MS_PER_DAY) + 1;
}

/** The `days` dates ending today (UTC), today included. */
export function lastDays(today: string, days: number): DateRange {
  return { from: addDays(today, -(days - 1)), to: today };
}

export type RangeError = "FORMAT" | "ORDER" | "TOO_LONG" | "FUTURE";

/** Mirrors Core's checks so the form can explain them; Core still decides (400 INVALID_REQUEST). */
export function validateRange(range: DateRange, today: string): RangeError | null {
  if (!isIsoDate(range.from) || !isIsoDate(range.to)) {
    return "FORMAT";
  }
  if (range.from > range.to) {
    return "ORDER";
  }
  if (rangeLength(range) > MAX_RANGE_DAYS) {
    return "TOO_LONG";
  }
  if (range.to > today) {
    return "FUTURE";
  }
  return null;
}

/** The applied range from the page URL; missing or invalid values fall back to the default 7 days. */
export function rangeFromSearch(params: { get(name: string): string | null }, today: string): DateRange {
  return urlRange(params, today).range;
}

/** Like rangeFromSearch, and says whether a range the URL did carry was replaced (so the page can tell the user). */
export function urlRange(params: { get(name: string): string | null }, today: string): { range: DateRange; replaced: boolean } {
  const from = params.get("from");
  const to = params.get("to");
  const range = { from: from ?? "", to: to ?? "" };
  if (validateRange(range, today) === null) {
    return { range, replaced: false };
  }
  return { range: lastDays(today, DEFAULT_RANGE_DAYS), replaced: from !== null || to !== null };
}

export function reportPath(range: DateRange, top: number = DEFAULT_TOP): string {
  const params = new URLSearchParams({ from: range.from, to: range.to, top: String(top) });
  return `/admin/statistics/report?${params.toString()}`;
}

export function fetchReport(
  client: Pick<CoreClient, "request">,
  range: DateRange,
  signal?: AbortSignal,
): Promise<StatisticsReport> {
  return client.request<StatisticsReport>("GET", reportPath(range), { signal });
}

/** What the dashboard shows. Numbers exist only in the "data" view; an unavailable answer never becomes zeros. */
export type ReportView =
  | { kind: "unavailable"; reason: ReportReason | null }
  | {
      kind: "data";
      stale: boolean;
      updatedAt: string;
      empty: boolean;
      totals: ReportCounts;
      days: ReportDay[];
      topContent: ReportedTitle[];
    };

export function viewOf(report: StatisticsReport): ReportView {
  const { totals, days, topContent, updatedAt } = report;
  if (report.status === "UNAVAILABLE" || !totals || !days || !topContent || !updatedAt) {
    return { kind: "unavailable", reason: report.reason };
  }
  const empty = totals.qualifiedViews === 0 && totals.acceptedWatchedMs === 0;
  return { kind: "data", stale: report.status === "STALE", updatedAt, empty, totals, days, topContent };
}

export type LoadFailure = "LOGIN" | "FORBIDDEN" | "INVALID_RANGE" | "NETWORK" | "ERROR";

export function failureOf(error: unknown): LoadFailure {
  if (isProblem(error, 401)) {
    return "LOGIN";
  }
  if (isProblem(error, 403)) {
    return "FORBIDDEN";
  }
  if (isProblem(error, 400)) {
    return "INVALID_RANGE";
  }
  return error instanceof CoreNetworkError ? "NETWORK" : "ERROR";
}

/** "2 giờ 05 phút", "45 phút", "0 phút"; seconds are dropped (rounded down). */
export function formatWatched(ms: number): string {
  const minutes = Math.floor(ms / 60_000);
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return hours > 0 ? `${hours} giờ ${String(rest).padStart(2, "0")} phút` : `${rest} phút`;
}

/** "2026-11-23 08:05 UTC": the instant in UTC, never the browser's zone. */
export function formatUtc(instant: string): string {
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return instant;
  }
  return `${date.toISOString().slice(0, 16).replace("T", " ")} UTC`;
}
