"use client";

import { useEffect, useRef, useState } from "react";
import { primaryButtonClass, secondaryButtonClass } from "../ui";

export const NEXT_EPISODE_COUNTDOWN_SECONDS = 10;

export function NextEpisodePrompt({ onPlay, onCancel }: { onPlay(): void; onCancel(): void }) {
  const [remaining, setRemaining] = useState(NEXT_EPISODE_COUNTDOWN_SECONDS);
  const fired = useRef(false);

  useEffect(() => {
    const timer = window.setInterval(() => setRemaining((value) => Math.max(value - 1, 0)), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (remaining === 0 && !fired.current) {
      fired.current = true;
      onPlay();
    }
  }, [remaining, onPlay]);

  return (
    <section
      data-testid="next-episode"
      className="mt-6 flex flex-wrap items-center gap-4 rounded-2xl border border-zinc-800 bg-zinc-900/60 p-4"
    >
      <p className="text-zinc-200">
        Tập tiếp theo sẽ phát sau{" "}
        <span data-testid="next-episode-countdown" className="font-semibold text-amber-300">
          {remaining}
        </span>{" "}
        giây
      </p>
      <button type="button" data-testid="next-episode-play" onClick={onPlay} className={primaryButtonClass}>
        Phát ngay
      </button>
      <button type="button" data-testid="next-episode-cancel" onClick={onCancel} className={secondaryButtonClass}>
        Hủy
      </button>
    </section>
  );
}
