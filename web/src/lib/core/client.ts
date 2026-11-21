import { CoreNetworkError, CoreProblem, isAbortError } from "./errors.ts";
import type { AccountView, CsrfView, SessionResult } from "./types.ts";

export const CORE_BASE = "/api/core/v1";
export const REFRESHED_AT_KEY = "libra.auth.refreshedAt";
export const REFRESH_LOCK_NAME = "libra-auth-refresh";
export const AUTH_CHANNEL_NAME = "libra-auth";
/** Non-identity hint: "1" once this browser has had a session, removed on any sign-out. */
export const AUTH_HINT_KEY = "libra.auth.present";

export type FetchLike = (input: string, init: RequestInit) => Promise<Response>;

export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem?(key: string): void;
}

export interface LocksLike {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>;
}

export interface AuthMessage {
  type: "signed-out" | "signed-in";
}

export interface ChannelLike {
  postMessage(message: AuthMessage): void;
  onmessage: ((event: { data: unknown }) => void) | null;
}

export interface CoreClientDeps {
  fetch: FetchLike;
  now(): number;
  storage?: StorageLike;
  locks?: LocksLike;
  channel?: ChannelLike;
  /** A LOCAL sign-out (this tab's refresh failed or the user logged out). */
  onSignedOut(): void;
  /**
   * A `signed-out` message from ANOTHER tab. The receiver must verify identity itself (the other
   * tab may merely have lost a race against a login); when omitted it falls back to `onSignedOut`.
   */
  onRemoteSignedOut?(): void;
  onSignedIn?(): void;
}

export interface RequestOptions {
  body?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  keepalive?: boolean;
  /** When false the request never triggers a session refresh. Defaults to true. */
  auth?: boolean;
  /** Use only an already cached CSRF token; fail fast instead of fetching one. */
  cachedCsrfOnly?: boolean;
  /** Receives the headers of a successful response (never logged or stored by the client). */
  onHeaders?(headers: Headers): void;
}

export interface CoreClient {
  request<T>(method: string, path: string, options?: RequestOptions): Promise<T>;
  login(email: string, password: string): Promise<SessionResult>;
  logout(): Promise<void>;
  logoutAll(): Promise<void>;
  refresh(): Promise<void>;
  /** `refresh: false` never rotates the session on 401 (initial load without a hint, remote sign-out check). */
  me(options?: { refresh?: boolean }): Promise<AccountView>;
  /** Whether this browser may hold a session; true when it cannot be told (no storage). */
  hasAuthHint(): boolean;
  clearAuthHint(): void;
  invalidateCsrf(): void;
  cachedCsrf(): CsrfView | null;
  announce(type: AuthMessage["type"]): void;
  close(): void;
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const REFRESHABLE_CODES = new Set(["AUTHENTICATION_REQUIRED", "INVALID_CREDENTIALS"]);

export function createCoreClient(deps: CoreClientDeps): CoreClient {
  let csrf: CsrfView | null = null;
  let csrfInFlight: Promise<CsrfView> | null = null;
  let csrfEpoch = 0;
  let refreshInFlight: Promise<boolean> | null = null;
  let localRefreshedAt = 0;

  function invalidateCsrf(): void {
    csrf = null;
    csrfInFlight = null;
    csrfEpoch += 1;
  }

  function readRefreshedAt(): number {
    let stored = 0;
    try {
      const raw = deps.storage?.getItem(REFRESHED_AT_KEY);
      const parsed = raw ? Number(raw) : 0;
      stored = Number.isFinite(parsed) ? parsed : 0;
    } catch {
      stored = 0;
    }
    return Math.max(stored, localRefreshedAt);
  }

  function writeRefreshedAt(value: number): void {
    localRefreshedAt = value;
    try {
      deps.storage?.setItem(REFRESHED_AT_KEY, String(value));
    } catch {
      // Storage may be unavailable (private mode); the in-memory value still covers this tab.
    }
  }

  function hasAuthHint(): boolean {
    if (!deps.storage) {
      return true;
    }
    try {
      return deps.storage.getItem(AUTH_HINT_KEY) === "1";
    } catch {
      return true;
    }
  }

  function setAuthHint(): void {
    try {
      deps.storage?.setItem(AUTH_HINT_KEY, "1");
    } catch {
      // Hint only; without it the next load merely tries a refresh.
    }
  }

  function clearAuthHint(): void {
    try {
      if (deps.storage?.removeItem) {
        deps.storage.removeItem(AUTH_HINT_KEY);
      } else {
        deps.storage?.setItem(AUTH_HINT_KEY, "0");
      }
    } catch {
      // Ignored: storage may be unavailable.
    }
  }

  function announce(type: AuthMessage["type"]): void {
    try {
      deps.channel?.postMessage({ type });
    } catch {
      // A closed channel must never break the authentication flow.
    }
  }

  if (deps.channel) {
    deps.channel.onmessage = (event) => {
      const data = event.data as Partial<AuthMessage> | null;
      if (data?.type === "signed-out") {
        clearAuthHint();
        (deps.onRemoteSignedOut ?? deps.onSignedOut)();
      } else if (data?.type === "signed-in") {
        deps.onSignedIn?.();
      }
    };
  }

  async function rawFetch(
    method: string,
    path: string,
    options: RequestOptions,
    headers: Record<string, string>,
    body: string | undefined,
  ): Promise<Response> {
    try {
      return await deps.fetch(`${CORE_BASE}${path}`, {
        method,
        headers,
        body,
        signal: options.signal,
        keepalive: options.keepalive,
        credentials: "same-origin",
        cache: "no-store",
        redirect: "error",
      });
    } catch (error) {
      if (isAbortError(error)) {
        throw error;
      }
      throw new CoreNetworkError();
    }
  }

  async function toProblem(response: Response): Promise<CoreProblem> {
    let code: string | null = null;
    let correlationId = response.headers.get("X-Correlation-ID");
    const contentType = response.headers.get("Content-Type") ?? "";
    if (contentType.includes("json")) {
      try {
        const parsed: unknown = JSON.parse(await response.text());
        if (parsed && typeof parsed === "object") {
          const record = parsed as Record<string, unknown>;
          code = typeof record.code === "string" ? record.code : null;
          if (typeof record.correlationId === "string") {
            correlationId = record.correlationId;
          }
        }
      } catch {
        code = null;
      }
    }
    const retryHeader = response.headers.get("Retry-After");
    const retry = retryHeader === null ? Number.NaN : Number(retryHeader);
    return new CoreProblem(response.status, code, correlationId, Number.isFinite(retry) ? retry : null);
  }

  function fetchCsrf(): Promise<CsrfView> {
    if (csrf) {
      return Promise.resolve(csrf);
    }
    if (!csrfInFlight) {
      const epoch = csrfEpoch;
      const flight = (async () => {
        const response = await rawFetch("GET", "/auth/csrf", {}, { Accept: "application/json" }, undefined);
        if (!response.ok) {
          throw await toProblem(response);
        }
        const view = (await response.json()) as CsrfView;
        if (epoch === csrfEpoch) {
          csrf = view;
        }
        return view;
      })();
      csrfInFlight = flight;
      const release = () => {
        if (csrfInFlight === flight) {
          csrfInFlight = null;
        }
      };
      flight.then(release, release);
    }
    return csrfInFlight;
  }

  async function parseSuccess<T>(response: Response): Promise<T> {
    if (response.status === 204) {
      return undefined as T;
    }
    const contentType = response.headers.get("Content-Type") ?? "";
    if (!contentType.includes("json")) {
      return undefined as T;
    }
    try {
      return (await response.json()) as T;
    } catch {
      throw new CoreProblem(response.status, null, response.headers.get("X-Correlation-ID"), null);
    }
  }

  async function call<T>(method: string, path: string, options: RequestOptions): Promise<T> {
    const unsafe = !SAFE_METHODS.has(method);
    const startedAt = deps.now();
    const body = options.body === undefined ? undefined : JSON.stringify(options.body);
    let csrfRetried = false;
    let refreshed = false;

    for (;;) {
      const headers: Record<string, string> = { Accept: "application/json", ...options.headers };
      if (body !== undefined) {
        headers["Content-Type"] = "application/json";
      }
      if (unsafe) {
        const token = options.cachedCsrfOnly ? csrf : await fetchCsrf();
        if (!token) {
          throw new CoreNetworkError();
        }
        headers[token.headerName] = token.token;
      }
      const response = await rawFetch(method, path, options, headers, body);
      if (response.ok) {
        try {
          options.onHeaders?.(response.headers);
        } catch {
          // A faulty observer must never fail the request.
        }
        return parseSuccess<T>(response);
      }
      const problem = await toProblem(response);

      if (unsafe && !csrfRetried && problem.status === 403 && problem.code === "ACCESS_DENIED") {
        csrfRetried = true;
        invalidateCsrf();
        continue;
      }
      const refreshable =
        problem.status === 401 &&
        problem.code !== null &&
        REFRESHABLE_CODES.has(problem.code) &&
        options.auth !== false &&
        !path.startsWith("/auth/");
      if (refreshable && !refreshed) {
        refreshed = true;
        if (await ensureRefreshed(startedAt)) {
          continue;
        }
      }
      throw problem;
    }
  }

  function ensureRefreshed(startedAt: number): Promise<boolean> {
    if (!refreshInFlight) {
      const flight = runRefresh(startedAt);
      refreshInFlight = flight;
      const release = () => {
        if (refreshInFlight === flight) {
          refreshInFlight = null;
        }
      };
      flight.then(release, release);
    }
    return refreshInFlight;
  }

  function runRefresh(startedAt: number): Promise<boolean> {
    const inner = async (): Promise<boolean> => {
      if (readRefreshedAt() > startedAt) {
        return true;
      }
      invalidateCsrf();
      try {
        await call<unknown>("POST", "/auth/refresh", {});
      } catch (error) {
        if (error instanceof CoreProblem && error.status === 401) {
          clearAuthHint();
          deps.onSignedOut();
          announce("signed-out");
          return false;
        }
        throw error;
      } finally {
        invalidateCsrf();
      }
      writeRefreshedAt(deps.now());
      setAuthHint();
      return true;
    };
    return deps.locks ? deps.locks.request(REFRESH_LOCK_NAME, inner) : inner();
  }

  async function withCsrfReset<T>(operation: () => Promise<T>): Promise<T> {
    try {
      return await operation();
    } finally {
      invalidateCsrf();
    }
  }

  async function endingSession(operation: () => Promise<void>): Promise<void> {
    try {
      await withCsrfReset(operation);
    } catch (error) {
      if (error instanceof CoreProblem && error.status === 401) {
        clearAuthHint();
      }
      throw error;
    }
    clearAuthHint();
  }

  return {
    request: (method, path, options = {}) => call(method, path, options),
    login: async (email, password) => {
      const result = await withCsrfReset(() =>
        call<SessionResult>("POST", "/auth/login", { body: { email, password } }),
      );
      setAuthHint();
      return result;
    },
    logout: () => endingSession(() => call<void>("POST", "/auth/logout", {})),
    logoutAll: () => endingSession(() => call<void>("POST", "/auth/logout-all", {})),
    // Same lock + single-flight path as the automatic refresh, so a manual call cannot race it.
    refresh: async () => {
      if (!(await ensureRefreshed(deps.now()))) {
        throw new CoreProblem(401, "INVALID_SESSION", null, null);
      }
    },
    me: async (options = {}) => {
      const account = await call<AccountView>("GET", "/me", { auth: options.refresh !== false });
      setAuthHint();
      return account;
    },
    hasAuthHint,
    clearAuthHint,
    invalidateCsrf,
    cachedCsrf: () => csrf,
    announce,
    close: () => {
      if (deps.channel) {
        deps.channel.onmessage = null;
      }
    },
  };
}
