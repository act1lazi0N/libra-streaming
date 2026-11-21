"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { PlayControl } from "@/components/title/PlayControl";
import { PlayableBadges } from "@/components/title/PlayableBadges";
import { SeasonList } from "@/components/title/SeasonList";
import { errorClass, secondaryButtonClass } from "@/components/ui";
import { fetchTitle, formatDuration, isPlayable } from "@/lib/catalog/catalog";
import { isProblem } from "@/lib/core/errors";
import { useCoreClient } from "@/lib/session/session-context";
import { useCoreResource } from "@/lib/session/use-core-resource";

export default function TitlePage() {
  const { id } = useParams<{ id: string }>();
  const client = useCoreClient();
  const title = useCoreResource((signal) => fetchTitle(client, id, signal), [id]);

  if (title.status === "loading") {
    return (
      <main className="mx-auto max-w-4xl px-4 py-10 sm:px-6">
        <p role="status" className="text-zinc-400">
          Đang tải...
        </p>
      </main>
    );
  }

  if (title.status === "error" || !title.data) {
    if (isProblem(title.error, 404)) {
      return (
        <main data-testid="title-not-found" className="mx-auto max-w-4xl px-4 py-10 sm:px-6">
          <h1 className="text-3xl font-semibold tracking-tight">Không tìm thấy nội dung</h1>
          <p className="mt-3 text-zinc-400">Nội dung này không tồn tại hoặc hiện chưa được phát hành.</p>
          <Link href="/" className="mt-6 inline-block text-amber-400 hover:underline">
            Về trang chủ
          </Link>
        </main>
      );
    }
    return (
      <main className="mx-auto max-w-4xl space-y-3 px-4 py-10 sm:px-6">
        <p role="alert" className={errorClass}>
          Không thể tải nội dung. Vui lòng thử lại.
        </p>
        <button type="button" onClick={title.reload} className={secondaryButtonClass}>
          Thử lại
        </button>
      </main>
    );
  }

  const content = title.data;
  const meta = content.metadata;
  const facts = [
    meta.releaseYear ? String(meta.releaseYear) : null,
    meta.language || null,
    formatDuration(content.durationSeconds),
  ].filter((fact): fact is string => Boolean(fact));

  return (
    <main data-testid="title-detail" data-content-id={content.id} className="mx-auto max-w-4xl px-4 py-10 sm:px-6">
      <Link href="/" className="text-sm text-amber-400 hover:underline">
        &larr; Danh mục
      </Link>
      <h1 data-testid="title-name" className="mt-4 text-4xl font-semibold tracking-tight">
        {meta.title}
      </h1>
      <p className="mt-2 text-sm text-zinc-400">{facts.join(" · ")}</p>
      {meta.genres.length > 0 ? (
        <ul className="mt-3 flex flex-wrap gap-2" aria-label="Thể loại">
          {meta.genres.map((genre) => (
            <li key={genre} className="rounded-full border border-zinc-700 px-3 py-1 text-xs text-zinc-300">
              {genre}
            </li>
          ))}
        </ul>
      ) : null}
      <p className="mt-6 max-w-3xl leading-7 text-zinc-300">{meta.description}</p>
      {meta.credits.length > 0 ? (
        <p className="mt-4 text-sm text-zinc-400">
          <span className="text-zinc-500">Diễn viên và ê-kíp: </span>
          {meta.credits.join(", ")}
        </p>
      ) : null}

      {isPlayable(content.kind) ? (
        <div className="mt-8 flex flex-col gap-3">
          <PlayableBadges content={content} />
          <div>
            <PlayControl contentId={content.id} />
          </div>
        </div>
      ) : (
        <div className="mt-6">
          <span
            data-testid="tier-badge"
            className="rounded-full bg-zinc-800 px-2 py-0.5 text-xs text-zinc-300"
          >
            {meta.accessTier === "PREMIUM" ? "Premium" : "Miễn phí"}
          </span>
        </div>
      )}

      {content.kind === "SERIES" ? <SeasonList seriesId={content.id} /> : null}
    </main>
  );
}
