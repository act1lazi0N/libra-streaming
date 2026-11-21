"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { fetchTitle } from "@/lib/catalog/catalog";
import {
  PlaybackController,
  type HlsEngine,
  type PlayerSnapshot,
  type VideoLike,
} from "@/lib/playback/controller";
import { loadHlsEngine } from "@/lib/playback/hls-engine";
import { describePlayerDenial, formatClock } from "@/lib/playback/messages";
import { useProfiles } from "@/lib/session/profile-context";
import { useCoreClient } from "@/lib/session/session-context";
import { useCoreResource } from "@/lib/session/use-core-resource";
import { errorClass, primaryButtonClass } from "../ui";
import { NextEpisodePrompt } from "./NextEpisodePrompt";

const NO_HLS: HlsEngine = {
  isSupported: () => false,
  create: () => {
    throw new Error("hls.js is not available");
  },
};

const INITIAL: PlayerSnapshot = {
  status: "loading",
  errorReason: null,
  deniedCode: null,
  resumePositionMs: 0,
  next: { state: "none" },
};

interface Props {
  contentId: string;
  profileId: string;
  /** Called when the viewer asks to start the player again after it was interrupted. */
  onRestart(): void;
}

/**
 * One playback attempt for one (profile, content). The controller is created on mount and stopped
 * on unmount, on any profile switch / sign-out (generation change) and when the route changes.
 */
export function WatchPlayer({ contentId, profileId, onRestart }: Props) {
  const client = useCoreClient();
  const router = useRouter();
  const { generation } = useProfiles();
  const [mountGeneration] = useState(generation);
  const [mountProfileId] = useState(profileId);
  const interrupted = generation !== mountGeneration || profileId !== mountProfileId;
  const videoRef = useRef<HTMLVideoElement>(null);
  const controllerRef = useRef<PlaybackController | null>(null);
  const [snapshot, setSnapshot] = useState<PlayerSnapshot>(INITIAL);
  const [nextCancelled, setNextCancelled] = useState(false);
  const title = useCoreResource((signal) => fetchTitle(client, contentId, signal), [contentId]);

  useEffect(() => {
    let disposed = false;
    const hide = () => controllerRef.current?.flushOnHide();
    const visibility = () => {
      if (document.visibilityState === "hidden") {
        hide();
      } else {
        controllerRef.current?.resumeAfterHide();
      }
    };
    const pageShow = () => controllerRef.current?.resumeAfterHide();
    window.addEventListener("pagehide", hide);
    window.addEventListener("pageshow", pageShow);
    document.addEventListener("visibilitychange", visibility);

    void (async () => {
      const engine = await loadHlsEngine().catch(() => NO_HLS);
      const video = videoRef.current;
      if (disposed || !video) {
        return;
      }
      const controller = new PlaybackController({
        client,
        now: () => Date.now(),
        setTimeout: (callback, ms) => window.setTimeout(callback, ms),
        clearTimeout: (handle) => window.clearTimeout(handle as number),
        video: video as unknown as VideoLike,
        engine,
        onChange: setSnapshot,
      });
      controllerRef.current = controller;
      await controller.start(mountProfileId, contentId);
    })();

    return () => {
      disposed = true;
      window.removeEventListener("pagehide", hide);
      window.removeEventListener("pageshow", pageShow);
      document.removeEventListener("visibilitychange", visibility);
      controllerRef.current?.stop();
      controllerRef.current = null;
    };
  }, [client, contentId, mountProfileId]);

  useEffect(() => {
    if (interrupted) {
      controllerRef.current?.stop();
    }
  }, [interrupted]);

  const status = interrupted ? "error" : snapshot.status;
  const retry = useCallback(() => {
    void controllerRef.current?.retry();
  }, []);
  const playNext = useCallback(
    (nextId: string) => router.push(`/watch/${encodeURIComponent(nextId)}`),
    [router],
  );
  const next = snapshot.next;

  return (
    <main
      data-testid="watch-page"
      data-state={status}
      data-content-id={contentId}
      className="mx-auto max-w-5xl px-4 py-8 sm:px-6"
    >
      <h1 data-testid="watch-title" className="text-2xl font-semibold tracking-tight">
        {title.data?.metadata.title ?? "Đang phát"}
      </h1>

      {status === "denied" ? (
        <div role="alert" data-testid="player-denied" data-denial-code={snapshot.deniedCode ?? ""} className={`mt-4 ${errorClass}`}>
          {describePlayerDenial(snapshot.deniedCode)}
        </div>
      ) : null}
      {status === "error" && !interrupted && snapshot.errorReason === "unsupported" ? (
        <div role="alert" data-testid="player-unsupported" className={`mt-4 ${errorClass}`}>
          Trình duyệt của bạn không hỗ trợ phát HLS.
        </div>
      ) : null}
      {status === "error" && (interrupted || snapshot.errorReason === "playback") ? (
        <div role="alert" data-testid="player-error" className={`mt-4 flex flex-wrap items-center gap-3 ${errorClass}`}>
          <span>
            {interrupted
              ? "Phiên phát đã dừng vì tài khoản hoặc hồ sơ vừa thay đổi."
              : "Không thể tiếp tục phát. Vui lòng thử lại."}
          </span>
          <button
            type="button"
            data-testid="player-retry"
            onClick={interrupted ? onRestart : retry}
            className={primaryButtonClass}
          >
            Thử lại
          </button>
        </div>
      ) : null}

      <div className="mt-4 overflow-hidden rounded-2xl border border-zinc-800 bg-black">
        <video data-testid="video" controls playsInline className="aspect-video w-full" ref={videoRef} />
      </div>

      <p className="mt-3 flex flex-wrap items-center gap-3 text-sm text-zinc-400">
        <span>
          Trạng thái:{" "}
          <span data-testid="player-status" data-state={status}>
            {status}
          </span>
        </span>
        {snapshot.resumePositionMs > 0 && !interrupted ? (
          <span data-testid="resume-from">Tiếp tục từ {formatClock(snapshot.resumePositionMs)}</span>
        ) : null}
      </p>

      {status === "ended" && next.state === "ready" ? (
        next.contentId && !nextCancelled ? (
          <NextEpisodePrompt
            onPlay={() => playNext(next.contentId as string)}
            onCancel={() => setNextCancelled(true)}
          />
        ) : (
          <section data-testid="playback-finished" className="mt-6 rounded-2xl border border-zinc-800 bg-zinc-900/60 p-4">
            <p className="text-zinc-200">Bạn đã xem xong.</p>
            <Link href={`/title/${encodeURIComponent(contentId)}`} className="mt-2 inline-block text-amber-400 hover:underline">
              Quay lại trang nội dung
            </Link>
          </section>
        )
      ) : null}
    </main>
  );
}
