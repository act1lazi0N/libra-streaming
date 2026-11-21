"use client";

import { CatalogGrid } from "@/components/catalog/CatalogGrid";
import { ContinueWatching } from "@/components/watch/ContinueWatching";

export default function Home() {
  return (
    <main data-testid="home" className="mx-auto max-w-6xl px-4 py-8 sm:px-6">
      <ContinueWatching />
      <CatalogGrid />
    </main>
  );
}
