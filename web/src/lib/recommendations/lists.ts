import type { CoreClient } from "../core/client.ts";
import type { PublicContent } from "../core/types.ts";

// GET /v1/profiles/{profileId}/recommendations/lists/{list} (contracts/http/core-analytics.v1.json 1.2.0, RA05.3).

export type ListName = "personal" | "similar" | "popular" | "new";
export type Reason = "GENRE_INTEREST" | "SIMILAR_GENRES" | "POPULAR" | "NEWLY_PUBLISHED";
export type FailureReason = "DISABLED" | "UNAVAILABLE" | "INVALID_RESPONSE" | "STALE" | "NO_VISIBLE_CANDIDATES";

export const ROW_LIMIT = 12;

export interface RecommendationItem {
  content: PublicContent;
  reason: Reason;
}

export interface RecommendationList {
  source: "ANALYTICS" | "FALLBACK";
  reason: FailureReason | null;
  list: ListName;
  algorithmVersion: string | null;
  updatedAt: string | null;
  items: RecommendationItem[];
}

export const ROW_TITLES: Record<ListName, string> = {
  personal: "Dành cho bạn",
  similar: "Tương tự",
  popular: "Phổ biến",
  new: "Mới phát hành",
};

/** The reason shown on a card is exactly the rule that selected the title; nothing else is ever displayed. */
export const REASON_LABELS: Record<Reason, string> = {
  GENRE_INTEREST: "Hợp với thể loại bạn hay xem",
  SIMILAR_GENRES: "Cùng thể loại",
  POPULAR: "Nhiều người xem tuần này",
  NEWLY_PUBLISHED: "Mới phát hành",
};

export function listPath(profileId: string, list: ListName, seed?: string, limit: number = ROW_LIMIT): string {
  const params = new URLSearchParams({ limit: String(limit) });
  if (list === "similar") {
    if (!seed) {
      throw new Error("A similar list needs a seed title");
    }
    params.set("contentId", seed);
  }
  return `/profiles/${encodeURIComponent(profileId)}/recommendations/lists/${list}?${params.toString()}`;
}

export function fetchList(
  client: Pick<CoreClient, "request">,
  profileId: string,
  list: ListName,
  seed?: string,
  signal?: AbortSignal,
): Promise<RecommendationList> {
  return client.request<RecommendationList>("GET", listPath(profileId, list, seed), { signal });
}

export type RowView =
  | { kind: "hidden" }
  | { kind: "items"; items: RecommendationItem[]; fallback: boolean };

/**
 * What a row shows. An empty list is hidden (nothing honest to show); a fallback keeps Core's own titles but the row
 * says that personal recommendations are unavailable. A card's reason is only ever a known rule's label.
 */
export function rowView(list: RecommendationList): RowView {
  const items = list.items.filter((item) => Object.hasOwn(REASON_LABELS, item.reason));
  if (items.length === 0) {
    return { kind: "hidden" };
  }
  return { kind: "items", items, fallback: list.source === "FALLBACK" };
}
