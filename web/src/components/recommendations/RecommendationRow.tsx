"use client";

import Link from "next/link";
import { tierLabel } from "@/lib/catalog/catalog";
import { fetchList, REASON_LABELS, ROW_TITLES, rowView, type ListName } from "@/lib/recommendations/lists";
import { useCoreClient } from "@/lib/session/session-context";
import { useProfileScoped } from "@/lib/session/use-profile-scoped";

/**
 * One recommendation row for the selected profile. Loads per profile (a switch never shows the previous profile's
 * row), hides itself when empty or failing, and never offers playback: a card links to the title page, where the
 * normal admission decides.
 */
export function RecommendationRow({ list, seed }: { list: ListName; seed?: string }) {
  const client = useCoreClient();
  const state = useProfileScoped((profileId, signal) => fetchList(client, profileId, list, seed, signal), [list, seed]);

  if (state.status !== "success" || !state.data) {
    return null;
  }
  const view = rowView(state.data);
  if (view.kind === "hidden") {
    return null;
  }
  return (
    <section data-testid={`rec-row-${list}`} aria-label={ROW_TITLES[list]} className="mb-8">
      <div className="mb-3 flex flex-wrap items-baseline gap-3">
        <h2 className="text-xl font-semibold">{ROW_TITLES[list]}</h2>
        {view.fallback ? (
          <span data-testid={`rec-fallback-${list}`} className="text-xs text-zinc-400">
            Gợi ý cá nhân tạm thời không khả dụng
          </span>
        ) : null}
      </div>
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {view.items.map((item) => (
          <li key={item.content.id}>
            <Link
              href={`/title/${encodeURIComponent(item.content.id)}`}
              data-testid="rec-card"
              data-content-id={item.content.id}
              data-reason={item.reason}
              className="flex h-full flex-col rounded-xl border border-zinc-800 bg-zinc-900/60 p-3 hover:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-300"
            >
              <span className="text-[11px] uppercase tracking-wider text-amber-400">
                {item.content.kind === "SERIES" ? "Phim bộ" : "Phim lẻ"}
              </span>
              <span className="mt-1 line-clamp-2 text-sm font-medium">{item.content.metadata.title}</span>
              <span data-testid="rec-reason" className="mt-2 text-xs text-zinc-400">
                {REASON_LABELS[item.reason]}
              </span>
              <span className="mt-auto pt-2 text-[11px] text-zinc-500">{tierLabel(item.content.metadata.accessTier)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
