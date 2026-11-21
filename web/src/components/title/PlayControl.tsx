"use client";

import Link from "next/link";
import { fetchEntitlement } from "@/lib/catalog/catalog";
import { describeDenial } from "@/lib/catalog/playability";
import { useProfiles } from "@/lib/session/profile-context";
import { useCoreClient, useSession } from "@/lib/session/session-context";
import { useProfileScoped } from "@/lib/session/use-profile-scoped";
import { primaryButtonClass, secondaryButtonClass } from "../ui";

/**
 * Playability comes only from a live entitlement check for the selected profile. The tier badge is
 * informational and never enables playback; admission rechecks everything again.
 */
export function PlayControl({ contentId }: { contentId: string }) {
  const client = useCoreClient();
  const { state } = useSession();
  const { selected } = useProfiles();
  const entitlement = useProfileScoped(
    (profileId, signal) => fetchEntitlement(client, profileId, contentId, signal),
    [contentId],
  );

  if (state.status === "anonymous") {
    return (
      <Link href="/login" data-testid="play-login" className={secondaryButtonClass}>
        Đăng nhập để xem
      </Link>
    );
  }
  if (state.status === "loading" || !selected || entitlement.status === "loading") {
    return (
      <span role="status" className="text-sm text-zinc-400">
        Đang kiểm tra quyền xem...
      </span>
    );
  }
  if (entitlement.status === "success") {
    return (
      <Link href={`/watch/${encodeURIComponent(contentId)}`} data-testid="play-button" className={primaryButtonClass}>
        Xem ngay
      </Link>
    );
  }
  const denial = describeDenial(entitlement.error);
  if (denial.kind === "denied") {
    return (
      <span data-testid="play-denied" data-denial-code={denial.code} className="text-sm font-medium text-amber-300">
        {denial.message}
      </span>
    );
  }
  return (
    <span className="flex items-center gap-3 text-sm text-red-300" data-testid="play-error">
      {denial.message}
      <button type="button" onClick={entitlement.reload} className={secondaryButtonClass}>
        Thử lại
      </button>
    </span>
  );
}
