"use client";

import { useEffect, useRef, useState } from "react";
import { isAbortError } from "../core/errors";

export interface ResourceState<T> {
  status: "loading" | "success" | "error";
  data: T | null;
  error: unknown;
  reload(): void;
}

type Dep = string | number | boolean | null | undefined;

/** Loads non-profile data (e.g. the public catalog). Re-keys on deps so stale data is never shown. */
export function useCoreResource<T>(
  loader: (signal: AbortSignal) => Promise<T>,
  deps: readonly Dep[],
  enabled = true,
): ResourceState<T> {
  const depsKey = JSON.stringify(deps);
  const [nonce, setNonce] = useState(0);
  const [result, setResult] = useState<{ key: string; data: T | null; error: unknown; failed: boolean } | null>(null);
  const loaderRef = useRef(loader);
  const key = `${depsKey}|${nonce}`;

  useEffect(() => {
    loaderRef.current = loader;
  });

  useEffect(() => {
    if (!enabled) {
      return;
    }
    const controller = new AbortController();
    loaderRef.current(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) {
          setResult({ key, data, error: null, failed: false });
        }
      },
      (error: unknown) => {
        if (!controller.signal.aborted && !isAbortError(error)) {
          setResult({ key, data: null, error, failed: true });
        }
      },
    );
    return () => controller.abort();
  }, [key, enabled]);

  const reload = () => setNonce((value) => value + 1);
  if (!result || result.key !== key) {
    return { status: "loading", data: null, error: null, reload };
  }
  return result.failed
    ? { status: "error", data: null, error: result.error, reload }
    : { status: "success", data: result.data, error: null, reload };
}
