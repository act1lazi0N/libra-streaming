// Error types never carry response bodies, cookies or tokens in their messages.

export class CoreProblem extends Error {
  readonly status: number;
  readonly code: string | null;
  readonly correlationId: string | null;
  readonly retryAfterSeconds: number | null;

  constructor(
    status: number,
    code: string | null,
    correlationId: string | null,
    retryAfterSeconds: number | null,
  ) {
    super(`Core request failed with status ${status}${code ? ` (${code})` : ""}`);
    this.name = "CoreProblem";
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export class CoreNetworkError extends Error {
  constructor() {
    super("Core request could not be completed");
    this.name = "CoreNetworkError";
  }
}

export function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

export function isProblem(error: unknown, status?: number, code?: string): error is CoreProblem {
  return (
    error instanceof CoreProblem &&
    (status === undefined || error.status === status) &&
    (code === undefined || error.code === code)
  );
}
