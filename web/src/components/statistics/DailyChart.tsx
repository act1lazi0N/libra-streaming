import { formatWatched, type ReportDay } from "@/lib/statistics/report";

const WIDTH = 640;
const HEIGHT = 200;
const PADDING_TOP = 12;
const PADDING_BOTTOM = 28;

/** "2026-11-23" -> "23/11"; dates are UTC calendar dates, shown as such. */
function shortDay(day: string): string {
  return `${day.slice(8, 10)}/${day.slice(5, 7)}`;
}

export function DailyChart({ days }: { days: ReportDay[] }) {
  const max = Math.max(1, ...days.map((day) => day.qualifiedViews));
  const slot = WIDTH / days.length;
  const barWidth = Math.max(2, slot * 0.7);
  const plotHeight = HEIGHT - PADDING_TOP - PADDING_BOTTOM;
  const labelEvery = Math.ceil(days.length / 10);
  const total = days.reduce((sum, day) => sum + day.qualifiedViews, 0);

  return (
    <figure className="space-y-4">
      <svg
        data-testid="stats-chart"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        className="h-52 w-full"
        role="img"
        aria-label={`Lượt xem hợp lệ theo ngày (UTC) từ ${days[0]?.day} đến ${days[days.length - 1]?.day}: tổng ${total}`}
      >
        <line x1={0} x2={WIDTH} y1={HEIGHT - PADDING_BOTTOM} y2={HEIGHT - PADDING_BOTTOM} className="stroke-zinc-700" />
        {days.map((day, index) => {
          const height = (day.qualifiedViews / max) * plotHeight;
          const x = index * slot + (slot - barWidth) / 2;
          return (
            <g key={day.day}>
              <rect
                data-testid="stats-bar"
                data-day={day.day}
                data-value={day.qualifiedViews}
                x={x}
                y={HEIGHT - PADDING_BOTTOM - height}
                width={barWidth}
                height={height}
                rx={2}
                className="fill-amber-400"
              >
                <title>{`${day.day}: ${day.qualifiedViews} lượt xem`}</title>
              </rect>
              {index % labelEvery === 0 ? (
                <text x={index * slot + slot / 2} y={HEIGHT - 8} textAnchor="middle" className="fill-zinc-400 text-[11px]">
                  {shortDay(day.day)}
                </text>
              ) : null}
            </g>
          );
        })}
      </svg>
      <figcaption className="sr-only">Biểu đồ cột; bảng bên dưới có đầy đủ số liệu.</figcaption>
      <div className="overflow-x-auto">
        <table data-testid="stats-days" className="w-full text-left text-sm">
          <thead className="text-zinc-400">
            <tr>
              <th className="py-2 pr-4 font-medium">Ngày (UTC)</th>
              <th className="py-2 pr-4 text-right font-medium">Lượt xem</th>
              <th className="py-2 pr-4 text-right font-medium">Người xem</th>
              <th className="py-2 text-right font-medium">Thời gian xem</th>
            </tr>
          </thead>
          <tbody>
            {days.map((day) => (
              <tr key={day.day} data-day={day.day} className="border-t border-zinc-800">
                <td className="py-2 pr-4">{day.day}</td>
                <td className="py-2 pr-4 text-right tabular-nums">{day.qualifiedViews.toLocaleString("vi-VN")}</td>
                <td className="py-2 pr-4 text-right tabular-nums">{day.uniqueViewers.toLocaleString("vi-VN")}</td>
                <td className="py-2 text-right tabular-nums">{formatWatched(day.acceptedWatchedMs)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  );
}
