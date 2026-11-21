import { isAbortError } from "../core/errors.ts";

export type ScopedResult<T> =
  | { status: "success"; data: T }
  | { status: "error"; error: unknown };

export interface ScopedRun<T> {
  profileId: string;
  /** Generation captured when the run started. */
  generation: number;
  currentGeneration(): number;
  fetcher(profileId: string, signal: AbortSignal): Promise<T>;
  onResult(result: ScopedResult<T>): void;
}

/**
 * Runs a profile-scoped fetch. A result is delivered only if the run was not cancelled and the
 * profile generation did not change meanwhile, so data from a previous profile can never be shown
 * for the current one. Returns a cancel function that aborts the in-flight request.
 */
export function startScoped<T>(run: ScopedRun<T>): () => void {
  const controller = new AbortController();
  let cancelled = false;

  const stale = (): boolean => cancelled || run.generation !== run.currentGeneration();

  run.fetcher(run.profileId, controller.signal).then(
    (data) => {
      if (!stale()) {
        run.onResult({ status: "success", data });
      }
    },
    (error: unknown) => {
      if (stale() || isAbortError(error)) {
        return;
      }
      run.onResult({ status: "error", error });
    },
  );

  return () => {
    cancelled = true;
    controller.abort();
  };
}
