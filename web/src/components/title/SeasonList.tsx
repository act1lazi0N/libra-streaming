"use client";

import { useState } from "react";
import { errorClass, secondaryButtonClass } from "@/components/ui";
import { fetchChildren, formatDuration } from "@/lib/catalog/catalog";
import type { PublicContent } from "@/lib/core/types";
import { useCoreClient } from "@/lib/session/session-context";
import { useCoreResource } from "@/lib/session/use-core-resource";
import { PlayControl } from "./PlayControl";
import { PlayableBadges } from "./PlayableBadges";

function Episodes({ seasonId }: { seasonId: string }) {
  const client = useCoreClient();
  const episodes = useCoreResource((signal) => fetchChildren(client, seasonId, signal), [seasonId]);

  if (episodes.status === "loading") {
    return (
      <p role="status" className="py-3 text-sm text-zinc-400">
        Đang tải tập phim...
      </p>
    );
  }
  if (episodes.status === "error" || !episodes.data) {
    return (
      <div className="space-y-2 py-3">
        <p role="alert" className={errorClass}>
          Không thể tải danh sách tập.
        </p>
        <button type="button" onClick={episodes.reload} className={secondaryButtonClass}>
          Thử lại
        </button>
      </div>
    );
  }
  const items = episodes.data.filter((child) => child.kind === "EPISODE");
  if (items.length === 0) {
    return <p className="py-3 text-sm text-zinc-500">Mùa này chưa có tập nào.</p>;
  }
  return (
    <ul className="divide-y divide-zinc-800">
      {items.map((episode) => (
        <li
          key={episode.id}
          data-testid="episode"
          data-content-id={episode.id}
          className="flex flex-col gap-3 py-4 sm:flex-row sm:items-center sm:justify-between"
        >
          <div className="space-y-1">
            <p className="font-medium">
              <span data-testid="episode-ordinal" className="mr-2 text-zinc-500">
                {episode.ordinal ?? "-"}.
              </span>
              {episode.metadata.title}
            </p>
            <p className="text-sm text-zinc-400">{formatDuration(episode.durationSeconds) ?? ""}</p>
            <PlayableBadges content={episode} />
          </div>
          <PlayControl contentId={episode.id} />
        </li>
      ))}
    </ul>
  );
}

function Season({ season, initiallyOpen }: { season: PublicContent; initiallyOpen: boolean }) {
  const [open, setOpen] = useState(initiallyOpen);
  const panelId = `season-${season.id}`;
  return (
    <section data-testid="season" data-content-id={season.id} className="rounded-2xl border border-zinc-800 bg-zinc-900/60">
      <h3>
        <button
          type="button"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={() => setOpen((value) => !value)}
          className="flex w-full items-center justify-between rounded-2xl px-5 py-4 text-left text-lg font-medium focus:outline-none focus:ring-2 focus:ring-amber-300"
        >
          <span>{season.metadata.title}</span>
          <span aria-hidden className="text-zinc-500">
            {open ? "−" : "+"}
          </span>
        </button>
      </h3>
      <div id={panelId} hidden={!open} className="px-5 pb-2">
        {open ? <Episodes seasonId={season.id} /> : null}
      </div>
    </section>
  );
}

export function SeasonList({ seriesId }: { seriesId: string }) {
  const client = useCoreClient();
  const seasons = useCoreResource((signal) => fetchChildren(client, seriesId, signal), [seriesId]);

  if (seasons.status === "loading") {
    return (
      <p role="status" className="mt-8 text-zinc-400">
        Đang tải các mùa...
      </p>
    );
  }
  if (seasons.status === "error" || !seasons.data) {
    return (
      <div className="mt-8 space-y-2">
        <p role="alert" className={errorClass}>
          Không thể tải danh sách mùa.
        </p>
        <button type="button" onClick={seasons.reload} className={secondaryButtonClass}>
          Thử lại
        </button>
      </div>
    );
  }
  const items = seasons.data.filter((child) => child.kind === "SEASON");
  if (items.length === 0) {
    return <p className="mt-8 text-zinc-500">Chưa có mùa nào được phát hành.</p>;
  }
  return (
    <div className="mt-8 space-y-4">
      {items.map((season, index) => (
        <Season key={season.id} season={season} initiallyOpen={index === 0} />
      ))}
    </div>
  );
}
