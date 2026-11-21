import { tierLabel } from "@/lib/catalog/catalog";
import type { PublicContent } from "@/lib/core/types";

/** Two independent facts: the access tier (informational) and whether media is available. */
export function PlayableBadges({ content }: { content: PublicContent }) {
  return (
    <span className="flex flex-wrap items-center gap-2 text-xs">
      <span
        data-testid="tier-badge"
        className={`rounded-full px-2 py-0.5 ${
          content.metadata.accessTier === "PREMIUM" ? "bg-amber-400/20 text-amber-300" : "bg-zinc-800 text-zinc-300"
        }`}
      >
        {tierLabel(content.metadata.accessTier)}
      </span>
      <span data-testid="availability" className={content.mediaReady ? "text-zinc-400" : "text-zinc-500"}>
        {content.mediaReady ? "Sẵn sàng" : "Chưa sẵn sàng"}
      </span>
    </span>
  );
}
