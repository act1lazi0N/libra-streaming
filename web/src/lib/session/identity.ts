import { CoreProblem } from "../core/errors.ts";

export const IDENTITY_BACKOFF_START_MS = 2000;
export const IDENTITY_BACKOFF_MAX_MS = 30_000;

export type IdentityFailure = { action: "anonymous" } | { action: "retry"; delayMs: number };

/** Backoff before the n-th consecutive retry (1-based): 2 s, 4 s, 8 s ... capped at 30 s. */
export function identityBackoffMs(failures: number): number {
  return Math.min(IDENTITY_BACKOFF_START_MS * 2 ** Math.max(failures - 1, 0), IDENTITY_BACKOFF_MAX_MS);
}

/**
 * What a failed `GET /me` means. Only a 401 proves there is no session; a network error, a 5xx or
 * anything else says nothing about identity, so the tab keeps its state and asks again later.
 */
export function decideIdentityFailure(error: unknown, consecutiveFailures: number): IdentityFailure {
  if (error instanceof CoreProblem && error.status === 401) {
    return { action: "anonymous" };
  }
  return { action: "retry", delayMs: identityBackoffMs(consecutiveFailures + 1) };
}
