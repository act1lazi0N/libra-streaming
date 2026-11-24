import type { ContentKind } from "@/lib/core/types";
import { formatWatched, type ReportedTitle } from "@/lib/statistics/report";

const KIND_LABELS: Record<ContentKind, string> = {
  MOVIE: "Phim lẻ",
  SERIES: "Phim bộ",
  SEASON: "Mùa",
  EPISODE: "Tập",
};

export function TopContentTable({ titles }: { titles: ReportedTitle[] }) {
  return (
    <div className="overflow-x-auto">
      <table data-testid="stats-top" className="w-full text-left text-sm">
        <thead className="text-zinc-400">
          <tr>
            <th className="py-2 pr-4 font-medium">#</th>
            <th className="py-2 pr-4 font-medium">Nội dung</th>
            <th className="py-2 pr-4 font-medium">Loại</th>
            <th className="py-2 pr-4 text-right font-medium">Lượt xem</th>
            <th className="py-2 pr-4 text-right font-medium">Người xem</th>
            <th className="py-2 text-right font-medium">Thời gian xem</th>
          </tr>
        </thead>
        <tbody>
          {titles.length === 0 ? (
            <tr className="border-t border-zinc-800">
              <td colSpan={6} className="py-3 text-zinc-400">
                Không có nội dung
              </td>
            </tr>
          ) : (
            titles.map((title, index) => (
              <tr
                key={title.contentId}
                data-testid="stats-top-row"
                data-content-id={title.contentId}
                className="border-t border-zinc-800"
              >
                <td className="py-2 pr-4 tabular-nums text-zinc-400">{index + 1}</td>
                <td className="py-2 pr-4">
                  {title.title ?? (
                    <span className="text-zinc-400">Nội dung không xác định ({title.contentId.slice(0, 8)})</span>
                  )}
                  {!title.published ? (
                    <span className="ml-2 rounded-full bg-zinc-800 px-2 py-0.5 text-xs text-zinc-300">Chưa xuất bản</span>
                  ) : null}
                </td>
                <td className="py-2 pr-4 text-zinc-300">{title.kind ? KIND_LABELS[title.kind] : "—"}</td>
                <td className="py-2 pr-4 text-right tabular-nums">{title.qualifiedViews.toLocaleString("vi-VN")}</td>
                <td className="py-2 pr-4 text-right tabular-nums">{title.uniqueViewers.toLocaleString("vi-VN")}</td>
                <td className="py-2 text-right tabular-nums">{formatWatched(title.acceptedWatchedMs)}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
