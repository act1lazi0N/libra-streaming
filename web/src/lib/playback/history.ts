import type { CoreClient } from "../core/client.ts";
import type { HistoryItem, NextEpisode } from "./types.ts";

type Requester = Pick<CoreClient, "request">;

export const CONTINUE_WATCHING_LIMIT = 20;

export function fetchContinueWatching(
  client: Requester,
  profileId: string,
  signal?: AbortSignal,
): Promise<HistoryItem[]> {
  return client.request<HistoryItem[]>(
    "GET",
    `/profiles/${encodeURIComponent(profileId)}/continue-watching?limit=${CONTINUE_WATCHING_LIMIT}`,
    { signal },
  );
}

/** Admission still rechecks rights for whatever this returns; null means nothing follows. */
export async function fetchNextEpisode(
  client: Requester,
  profileId: string,
  contentId: string,
  signal?: AbortSignal,
): Promise<string | null> {
  const result = await client.request<NextEpisode>(
    "GET",
    `/profiles/${encodeURIComponent(profileId)}/next-episode/${encodeURIComponent(contentId)}`,
    { signal },
  );
  return typeof result?.contentId === "string" ? result.contentId : null;
}

/** Progress fraction in [0, 1] for the continue-watching bar. */
export function progressFraction(item: Pick<HistoryItem, "positionMs" | "durationMs">): number {
  if (item.durationMs <= 0) {
    return 0;
  }
  return Math.min(Math.max(item.positionMs / item.durationMs, 0), 1);
}
