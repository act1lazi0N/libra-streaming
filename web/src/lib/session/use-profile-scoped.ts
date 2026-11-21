"use client";

import { useEffect, useRef, useState } from "react";
import { useProfiles } from "./profile-context";
import { startScoped, type ScopedResult } from "./scoped";

export interface ProfileScopedState<T> {
  status: "loading" | "success" | "error";
  data: T | null;
  error: unknown;
  reload(): void;
}

type Dep = string | number | boolean | null | undefined;

/**
 * Runs `fetcher(profileId, signal)` for the selected profile. Aborts on profile switch/unmount,
 * drops results that belong to an earlier generation, and reports `loading` immediately after a
 * switch so the previous profile's data is never rendered for the new one.
 */
export function useProfileScoped<T>(
  fetcher: (profileId: string, signal: AbortSignal) => Promise<T>,
  deps: readonly Dep[],
): ProfileScopedState<T> {
  const { selected, generation } = useProfiles();
  const profileId = selected?.id ?? null;
  const depsKey = JSON.stringify(deps);
  const [nonce, setNonce] = useState(0);
  const [result, setResult] = useState<{ key: string; value: ScopedResult<T> } | null>(null);
  const fetcherRef = useRef(fetcher);
  const generationRef = useRef(generation);
  const key = `${generation}|${profileId}|${depsKey}|${nonce}`;

  useEffect(() => {
    fetcherRef.current = fetcher;
    generationRef.current = generation;
  });

  useEffect(() => {
    if (!profileId) {
      return;
    }
    return startScoped<T>({
      profileId,
      generation,
      currentGeneration: () => generationRef.current,
      fetcher: (id, signal) => fetcherRef.current(id, signal),
      onResult: (value) => setResult({ key, value }),
    });
  }, [profileId, generation, key]);

  const reload = () => setNonce((value) => value + 1);
  const current = result && result.key === key ? result.value : null;
  if (!current) {
    return { status: "loading", data: null, error: null, reload };
  }
  if (current.status === "success") {
    return { status: "success", data: current.data, error: null, reload };
  }
  return { status: "error", data: null, error: current.error, reload };
}
