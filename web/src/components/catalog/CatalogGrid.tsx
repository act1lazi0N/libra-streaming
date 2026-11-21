"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { errorClass, inputClass, secondaryButtonClass } from "@/components/ui";
import { fetchCatalog, formatDuration, tierLabel, type CatalogKindFilter } from "@/lib/catalog/catalog";
import { useCoreResource } from "@/lib/session/use-core-resource";
import { useCoreClient } from "@/lib/session/session-context";

const SEARCH_DEBOUNCE_MS = 300;

const KIND_OPTIONS: { value: CatalogKindFilter; label: string }[] = [
  { value: "ALL", label: "Tất cả" },
  { value: "MOVIE", label: "Phim lẻ" },
  { value: "SERIES", label: "Phim bộ" },
];

export function CatalogGrid() {
  const client = useCoreClient();
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState<CatalogKindFilter>("ALL");
  const [pages, setPages] = useState(1);

  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(input.trim());
      setPages(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [input]);

  const catalog = useCoreResource(
    (signal) => fetchCatalog(client, { q: query, kind, pages, signal }),
    [query, kind, pages],
  );

  return (
    <section aria-label="Danh mục phim">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex-1">
          <label htmlFor="catalog-search" className="mb-1 block text-sm text-zinc-300">
            Tìm kiếm
          </label>
          <input
            id="catalog-search"
            data-testid="catalog-search"
            type="search"
            value={input}
            onChange={(event) => setInput(event.target.value)}
            placeholder="Nhập tên phim..."
            className={inputClass}
          />
        </div>
        <div>
          <label htmlFor="catalog-kind" className="mb-1 block text-sm text-zinc-300">
            Loại
          </label>
          <select
            id="catalog-kind"
            data-testid="catalog-kind"
            value={kind}
            onChange={(event) => {
              setKind(event.target.value as CatalogKindFilter);
              setPages(1);
            }}
            className={`${inputClass} sm:w-44`}
          >
            {KIND_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      {catalog.status === "loading" ? (
        <p role="status" className="mt-8 text-zinc-400">
          Đang tải danh mục...
        </p>
      ) : null}

      {catalog.status === "error" ? (
        <div className="mt-8 space-y-3" data-testid="catalog-error">
          <p role="alert" className={errorClass}>
            Không thể tải danh mục. Vui lòng thử lại.
          </p>
          <button type="button" onClick={catalog.reload} className={secondaryButtonClass}>
            Thử lại
          </button>
        </div>
      ) : null}

      {catalog.status === "success" && catalog.data ? (
        catalog.data.items.length === 0 ? (
          <p data-testid="catalog-empty" className="mt-8 text-zinc-400">
            Không tìm thấy nội dung phù hợp.
          </p>
        ) : (
          <>
            <ul className="mt-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {catalog.data.items.map((item) => (
                <li key={item.id}>
                  <Link
                    href={`/title/${encodeURIComponent(item.id)}`}
                    data-testid="catalog-card"
                    data-content-id={item.id}
                    className="flex h-full flex-col rounded-2xl border border-zinc-800 bg-zinc-900/60 p-5 hover:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-300"
                  >
                    <span className="text-xs uppercase tracking-wider text-amber-400">
                      {item.kind === "SERIES" ? "Phim bộ" : "Phim lẻ"}
                    </span>
                    <span className="mt-2 text-lg font-medium">{item.metadata.title}</span>
                    <span className="mt-1 text-sm text-zinc-400">
                      {[item.metadata.releaseYear, formatDuration(item.durationSeconds)].filter(Boolean).join(" · ")}
                    </span>
                    <span className="mt-3 line-clamp-3 text-sm text-zinc-500">{item.metadata.description}</span>
                    <span className="mt-auto pt-4 text-xs text-zinc-400">{tierLabel(item.metadata.accessTier)}</span>
                  </Link>
                </li>
              ))}
            </ul>
            {catalog.data.hasMore ? (
              <div className="mt-6 text-center">
                <button
                  type="button"
                  data-testid="catalog-more"
                  onClick={() => setPages((value) => value + 1)}
                  className={secondaryButtonClass}
                >
                  Tải thêm
                </button>
              </div>
            ) : null}
          </>
        )
      ) : null}
    </section>
  );
}
