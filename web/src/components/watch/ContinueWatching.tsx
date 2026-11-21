"use client";

import Link from "next/link";
import { fetchContinueWatching, progressFraction } from "@/lib/playback/history";
import { useProfiles } from "@/lib/session/profile-context";
import { useCoreClient, useSession } from "@/lib/session/session-context";
import { useProfileScoped } from "@/lib/session/use-profile-scoped";

/** The selected profile's unfinished titles; the whole row is hidden when there is nothing to resume. */
export function ContinueWatching() {
  const client = useCoreClient();
  const { state } = useSession();
  const { selected } = useProfiles();
  const items = useProfileScoped((profileId, signal) => fetchContinueWatching(client, profileId, signal), []);

  if (state.status !== "authenticated" || !selected || items.status !== "success" || !items.data?.length) {
    return null;
  }
  return (
    <section data-testid="continue-watching" aria-labelledby="continue-watching-heading" className="mb-10">
      <h2 id="continue-watching-heading" className="mb-4 text-xl font-semibold tracking-tight">
        Tiếp tục xem
      </h2>
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {items.data.map((item) => {
          const percent = Math.round(progressFraction(item) * 100);
          return (
            <li key={item.contentId}>
              <Link
                href={`/watch/${encodeURIComponent(item.contentId)}`}
                data-testid="continue-item"
                data-content-id={item.contentId}
                className="block rounded-2xl border border-zinc-800 bg-zinc-900/60 p-4 hover:border-zinc-600"
              >
                <span className="block truncate font-medium text-zinc-100">{item.title}</span>
                <span
                  role="progressbar"
                  aria-label="Tiến độ xem"
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-valuenow={percent}
                  data-testid="continue-progress"
                  className="mt-3 block h-1.5 overflow-hidden rounded-full bg-zinc-800"
                >
                  <span className="block h-full bg-amber-400" style={{ width: `${percent}%` }} />
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
