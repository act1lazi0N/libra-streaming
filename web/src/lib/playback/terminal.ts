import { CoreNetworkError, CoreProblem } from "../core/errors.ts";

/** Conflict codes that mean "this session is over; admit a new one". */
export const READMIT_CODES: ReadonlySet<string> = new Set([
  "PLAYBACK_EXPIRED",
  "PLAYBACK_ENDED",
  "MEDIA_BINDING_CHANGED",
  "PLAYBACK_EXPIRING",
]);

export function isReadmitProblem(error: unknown): boolean {
  return error instanceof CoreProblem && error.status === 409 && error.code !== null && READMIT_CODES.has(error.code);
}

/**
 * A progress answer after which the session can never accept anything again: the four lifecycle
 * conflicts, any 403, 404, and 401 (the core client already tried to refresh, so it is a sign-out).
 */
export function isTerminalProgressProblem(error: unknown): error is CoreProblem {
  if (!(error instanceof CoreProblem)) {
    return false;
  }
  return isReadmitProblem(error) || error.status === 403 || error.status === 404 || error.status === 401;
}

/** Transient failures worth retrying with the same request: network errors and 5xx. */
export function isTransientFailure(error: unknown): boolean {
  if (error instanceof CoreProblem) {
    return error.status >= 500;
  }
  return error instanceof CoreNetworkError;
}
