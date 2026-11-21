import type { CoreClient } from "../core/client.ts";
import type { AccessTier, ContentKind, Eligibility, PublicContent } from "../core/types.ts";

export const PAGE_SIZE = 24;
export const CHILDREN_LIMIT = 100;

type Requester = Pick<CoreClient, "request">;

export type CatalogKindFilter = "ALL" | "MOVIE" | "SERIES";

export interface CatalogQuery {
  q: string;
  kind: CatalogKindFilter;
  /** Number of pages (of PAGE_SIZE items per kind) to load, starting at offset 0. */
  pages: number;
  signal?: AbortSignal;
}

export interface CatalogResult {
  items: PublicContent[];
  hasMore: boolean;
}

function kindsFor(filter: CatalogKindFilter): ContentKind[] {
  return filter === "ALL" ? ["MOVIE", "SERIES"] : [filter];
}

export function catalogPath(q: string, kind: ContentKind, offset: number): string {
  const params = new URLSearchParams();
  if (q.trim()) {
    params.set("q", q.trim());
  }
  params.set("kind", kind);
  params.set("limit", String(PAGE_SIZE));
  params.set("offset", String(offset));
  return `/catalog?${params.toString()}`;
}

/** Only MOVIE and SERIES are listed; seasons and episodes are reached through their series. */
export async function fetchCatalog(client: Requester, query: CatalogQuery): Promise<CatalogResult> {
  const seen = new Set<string>();
  const items: PublicContent[] = [];
  let hasMore = false;
  for (const kind of kindsFor(query.kind)) {
    for (let page = 0; page < query.pages; page += 1) {
      const batch = await client.request<PublicContent[]>("GET", catalogPath(query.q, kind, page * PAGE_SIZE), {
        signal: query.signal,
      });
      for (const item of batch) {
        if ((item.kind === "MOVIE" || item.kind === "SERIES") && !seen.has(item.id)) {
          seen.add(item.id);
          items.push(item);
        }
      }
      if (batch.length < PAGE_SIZE) {
        break;
      }
      if (page === query.pages - 1) {
        hasMore = true;
      }
    }
  }
  if (query.kind === "ALL") {
    items.sort((a, b) => b.publishedAt.localeCompare(a.publishedAt));
  }
  return { items, hasMore };
}

export function fetchTitle(client: Requester, id: string, signal?: AbortSignal): Promise<PublicContent> {
  return client.request<PublicContent>("GET", `/catalog/${encodeURIComponent(id)}`, { signal });
}

export async function fetchChildren(client: Requester, id: string, signal?: AbortSignal): Promise<PublicContent[]> {
  const children = await client.request<PublicContent[]>(
    "GET",
    `/catalog/${encodeURIComponent(id)}/children?limit=${CHILDREN_LIMIT}`,
    { signal },
  );
  return [...children].sort((a, b) => (a.ordinal ?? 0) - (b.ordinal ?? 0));
}

/** Always a live check: the result is a point-in-time answer, never cached or reused as a grant. */
export function fetchEntitlement(
  client: Requester,
  profileId: string,
  contentId: string,
  signal?: AbortSignal,
): Promise<Eligibility> {
  return client.request<Eligibility>(
    "GET",
    `/profiles/${encodeURIComponent(profileId)}/entitlements/${encodeURIComponent(contentId)}`,
    { signal },
  );
}

export function tierLabel(tier: AccessTier): string {
  return tier === "PREMIUM" ? "Premium" : "Miễn phí";
}

export function formatDuration(seconds: number | null): string | null {
  if (seconds === null || seconds <= 0) {
    return null;
  }
  const minutes = Math.max(1, Math.round(seconds / 60));
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) {
    return `${minutes} phút`;
  }
  return rest === 0 ? `${hours} giờ` : `${hours} giờ ${rest} phút`;
}

export function isPlayable(kind: ContentKind): boolean {
  return kind === "MOVIE" || kind === "EPISODE";
}
