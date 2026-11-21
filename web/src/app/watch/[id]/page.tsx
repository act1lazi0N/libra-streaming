"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { WatchPlayer } from "@/components/watch/WatchPlayer";
import { secondaryButtonClass } from "@/components/ui";
import { useProfiles } from "@/lib/session/profile-context";
import { useSession } from "@/lib/session/session-context";

/** Gate: playback needs a verified identity and a selected profile; the player does the rest. */
export default function WatchPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { state } = useSession();
  const { selected } = useProfiles();
  const [restarts, setRestarts] = useState(0);
  const anonymous = state.status === "anonymous";

  useEffect(() => {
    if (anonymous) {
      router.replace("/login");
    }
  }, [anonymous, router]);

  if (anonymous) {
    return (
      <main className="mx-auto max-w-4xl px-4 py-10 sm:px-6">
        <Link href="/login" data-testid="play-login" className={secondaryButtonClass}>
          Đăng nhập để xem
        </Link>
      </main>
    );
  }
  if (state.status === "loading" || !selected) {
    return (
      <main className="mx-auto max-w-4xl px-4 py-10 sm:px-6">
        <p role="status" className="text-zinc-400">
          Đang tải...
        </p>
      </main>
    );
  }
  return (
    <WatchPlayer
      // A profile switch must not remount (and so re-admit) under the new profile: the player captures the
      // profile it was started for and stops when it changes; only the viewer's retry starts it again.
      key={`${id}|${restarts}`}
      contentId={id}
      profileId={selected.id}
      onRestart={() => setRestarts((value) => value + 1)}
    />
  );
}
