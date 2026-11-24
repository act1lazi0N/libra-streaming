"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState, type FormEvent, type ReactNode } from "react";
import { DailyChart } from "@/components/statistics/DailyChart";
import { TopContentTable } from "@/components/statistics/TopContentTable";
import { cardClass, errorClass, inputClass, primaryButtonClass, secondaryButtonClass } from "@/components/ui";
import { useCoreClient, useSession } from "@/lib/session/session-context";
import { useCoreResource } from "@/lib/session/use-core-resource";
import {
  fetchReport,
  failureOf,
  formatUtc,
  formatWatched,
  lastDays,
  urlRange,
  utcToday,
  validateRange,
  type DateRange,
  type RangeError,
  type ReportView,
  viewOf,
} from "@/lib/statistics/report";

const RANGE_MESSAGES: Record<RangeError | "SERVER", string> = {
  FORMAT: "Ngày không hợp lệ. Hãy chọn ngày theo dạng năm-tháng-ngày.",
  ORDER: "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.",
  TOO_LONG: "Khoảng thời gian tối đa là 31 ngày.",
  FUTURE: "Ngày kết thúc không được sau hôm nay (UTC).",
  SERVER: "Khoảng thời gian không hợp lệ. Hãy chọn tối đa 31 ngày, kết thúc không muộn hơn hôm nay (UTC).",
};

const REASON_LABELS: Record<string, string> = {
  DISABLED: "Analytics chưa được bật",
  UNAVAILABLE: "Không kết nối được Analytics",
  INVALID_RESPONSE: "Analytics trả về dữ liệu không hợp lệ",
};

function Shell({ children }: { children: ReactNode }) {
  return (
    <main className="mx-auto max-w-6xl space-y-6 px-4 py-10 sm:px-6">
      <header className="space-y-2">
        <h1 className="text-3xl font-semibold tracking-tight">Thống kê nền tảng</h1>
        <p data-testid="stats-note" className="text-sm text-zinc-400">
          Ngày tính theo UTC. Người xem được đếm theo tài khoản, không cộng dồn qua các ngày.
        </p>
      </header>
      {children}
    </main>
  );
}

function Forbidden() {
  return (
    <p data-testid="stats-forbidden" role="alert" className={errorClass}>
      Bạn không có quyền xem thống kê.
    </p>
  );
}

function LoginNeeded() {
  return (
    <p data-testid="stats-login" className="text-zinc-300">
      Hãy{" "}
      <Link href="/login" className="text-amber-400 hover:underline">
        đăng nhập
      </Link>{" "}
      bằng tài khoản quản trị để xem thống kê.
    </p>
  );
}

export default function StatisticsPage() {
  const { state } = useSession();
  if (state.status === "loading") {
    return (
      <Shell>
        <p role="status" className="text-zinc-400">
          Đang tải...
        </p>
      </Shell>
    );
  }
  if (state.status === "anonymous") {
    return (
      <Shell>
        <LoginNeeded />
      </Shell>
    );
  }
  // A hint only: Core checks the live ADMIN role on every request and answers 403 otherwise.
  if (state.account.role !== "ADMIN") {
    return (
      <Shell>
        <Forbidden />
      </Shell>
    );
  }
  return (
    <Shell>
      <Suspense
        fallback={
          <p role="status" className="text-zinc-400">
            Đang tải...
          </p>
        }
      >
        <Dashboard />
      </Suspense>
    </Shell>
  );
}

function Dashboard() {
  const client = useCoreClient();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const today = utcToday(new Date());
  const { range: applied, replaced } = urlRange(params, today);
  const report = useCoreResource((signal) => fetchReport(client, applied, signal), [applied.from, applied.to]);

  function apply(range: DateRange) {
    router.replace(`${pathname}?${new URLSearchParams({ from: range.from, to: range.to }).toString()}`);
    if (range.from === applied.from && range.to === applied.to) {
      report.reload();
    }
  }

  let body: ReactNode;
  if (report.status === "loading") {
    body = (
      <p data-testid="stats-loading" role="status" className="text-zinc-400">
        Đang tải số liệu...
      </p>
    );
  } else if (report.status === "error" || !report.data) {
    const failure = failureOf(report.error);
    if (failure === "LOGIN") {
      body = <LoginNeeded />;
    } else if (failure === "FORBIDDEN") {
      body = <Forbidden />;
    } else if (failure === "INVALID_RANGE") {
      body = (
        <p data-testid="stats-range-error" role="alert" className={errorClass}>
          {RANGE_MESSAGES.SERVER}
        </p>
      );
    } else {
      body = (
        <div data-testid="stats-error" className="space-y-3">
          <p role="alert" className={errorClass}>
            Không thể tải thống kê. Vui lòng thử lại.
          </p>
          <button type="button" onClick={report.reload} className={secondaryButtonClass}>
            Thử lại
          </button>
        </div>
      );
    }
  } else {
    body = <ReportBody view={viewOf(report.data)} range={applied} onReload={report.reload} />;
  }

  return (
    <>
      <RangeForm key={`${applied.from}|${applied.to}`} applied={applied} today={today} onApply={apply} />
      {replaced ? (
        <p data-testid="stats-url-replaced" role="status" className="text-sm text-amber-200">
          Khoảng thời gian trong địa chỉ không hợp lệ; đang hiển thị {applied.from} – {applied.to} (UTC).
        </p>
      ) : null}
      {body}
    </>
  );
}

function RangeForm({ applied, today, onApply }: { applied: DateRange; today: string; onApply(range: DateRange): void }) {
  const [draft, setDraft] = useState<DateRange>(applied);
  const [error, setError] = useState<RangeError | null>(null);

  function submit(range: DateRange) {
    const problem = validateRange(range, today);
    setError(problem);
    if (problem === null) {
      onApply(range);
    }
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    submit(draft);
  }

  function preset(days: number) {
    const range = lastDays(today, days);
    setDraft(range);
    submit(range);
  }

  return (
    <form data-testid="stats-range-form" noValidate onSubmit={onSubmit} className={`${cardClass} space-y-3 p-4`}>
      <div className="flex flex-wrap items-end gap-3">
        <label className="space-y-1 text-sm">
          <span className="text-zinc-300">Từ ngày (UTC)</span>
          <input
            data-testid="stats-from"
            type="date"
            value={draft.from}
            max={today}
            onChange={(event) => setDraft({ ...draft, from: event.target.value })}
            className={inputClass}
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="text-zinc-300">Đến ngày (UTC)</span>
          <input
            data-testid="stats-to"
            type="date"
            value={draft.to}
            max={today}
            onChange={(event) => setDraft({ ...draft, to: event.target.value })}
            className={inputClass}
          />
        </label>
        <button data-testid="stats-apply" type="submit" className={primaryButtonClass}>
          Xem
        </button>
        <button data-testid="stats-preset-7" type="button" onClick={() => preset(7)} className={secondaryButtonClass}>
          7 ngày
        </button>
        <button data-testid="stats-preset-30" type="button" onClick={() => preset(30)} className={secondaryButtonClass}>
          30 ngày
        </button>
      </div>
      {error ? (
        <p data-testid="stats-range-error" role="alert" className={errorClass}>
          {RANGE_MESSAGES[error]}
        </p>
      ) : null}
    </form>
  );
}

function ReportBody({ view, range, onReload }: { view: ReportView; range: DateRange; onReload(): void }) {
  if (view.kind === "unavailable") {
    // No number at all: an unavailable answer is never shown as zeros.
    return (
      <div data-testid="stats-unavailable" role="alert" className={`${errorClass} space-y-2`}>
        <p>Dữ liệu thống kê hiện không khả dụng.</p>
        {view.reason ? (
          <p className="text-xs text-red-300/80">
            {REASON_LABELS[view.reason] ?? "Lỗi không xác định"} ({view.reason})
          </p>
        ) : null}
        <button type="button" onClick={onReload} className={secondaryButtonClass}>
          Thử lại
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {view.stale ? (
        <p
          data-testid="stats-stale"
          role="status"
          className="rounded-lg border border-amber-900 bg-amber-950/50 px-3 py-2 text-sm text-amber-200"
        >
          Số liệu có thể đã cũ — cập nhật lần cuối {formatUtc(view.updatedAt)}
        </p>
      ) : (
        <p data-testid="stats-updated" className="text-sm text-zinc-400">
          Cập nhật lúc {formatUtc(view.updatedAt)}
        </p>
      )}
      {view.empty ? (
        <p data-testid="stats-empty" className={`${cardClass} p-6 text-zinc-300`}>
          Chưa có lượt xem nào trong khoảng {range.from} – {range.to} (UTC).
        </p>
      ) : (
        <>
          <section className="grid gap-4 sm:grid-cols-3">
            <Total testId="stats-total-views" label="Lượt xem hợp lệ" value={view.totals.qualifiedViews.toLocaleString("vi-VN")} />
            <Total testId="stats-total-viewers" label="Người xem (tài khoản)" value={view.totals.uniqueViewers.toLocaleString("vi-VN")} />
            <Total testId="stats-total-watched" label="Thời gian xem" value={formatWatched(view.totals.acceptedWatchedMs)} />
          </section>
          <section className={`${cardClass} space-y-3 p-4`}>
            <h2 className="text-lg font-semibold">Theo ngày</h2>
            <DailyChart days={view.days} />
          </section>
          <section className={`${cardClass} space-y-3 p-4`}>
            <h2 className="text-lg font-semibold">Nội dung xem nhiều nhất</h2>
            <TopContentTable titles={view.topContent} />
          </section>
        </>
      )}
    </div>
  );
}

function Total({ testId, label, value }: { testId: string; label: string; value: string }) {
  return (
    <div className={`${cardClass} p-4`}>
      <p className="text-sm text-zinc-400">{label}</p>
      <p data-testid={testId} className="mt-1 text-3xl font-semibold tabular-nums">
        {value}
      </p>
    </div>
  );
}
