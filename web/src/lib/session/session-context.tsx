"use client";

import { useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { createCoreClient, type CoreClient } from "../core/client";
import { CoreNetworkError, CoreProblem } from "../core/errors";
import type { AccountView } from "../core/types";
import { createAuthBridge } from "./bridge";
import { decideIdentityFailure } from "./identity";
import { createLazyChannel } from "./channel";

export const PROFILE_STORAGE_PREFIX = "libra.profile.";

export type SessionState =
  | { status: "loading" }
  | { status: "anonymous" }
  | { status: "authenticated"; account: AccountView };

export interface SessionContextValue {
  client: CoreClient;
  state: SessionState;
  /** Increments every time the session ends (local logout, refresh failure or broadcast). */
  epoch: number;
  logoutError: string | null;
  login(email: string, password: string): Promise<void>;
  logout(): Promise<void>;
}

const SessionContext = createContext<SessionContextValue | null>(null);

function purgeProfileSelection(): void {
  try {
    const doomed: string[] = [];
    for (let i = 0; i < window.sessionStorage.length; i += 1) {
      const key = window.sessionStorage.key(i);
      if (key?.startsWith(PROFILE_STORAGE_PREFIX)) {
        doomed.push(key);
      }
    }
    doomed.forEach((key) => window.sessionStorage.removeItem(key));
  } catch {
    // sessionStorage can be unavailable; selection then simply is not persisted.
  }
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [state, setState] = useState<SessionState>({ status: "loading" });
  const [epoch, setEpoch] = useState(0);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const stateRef = useRef<SessionState>(state);

  const [{ client, channel, bridge }] = useState(() => {
    const authBridge = createAuthBridge();
    const lazyChannel = createLazyChannel();
    const created = createCoreClient({
      fetch: (input, init) => globalThis.fetch(input, init),
      now: () => Date.now(),
      storage: typeof window === "undefined" ? undefined : window.localStorage,
      locks: typeof navigator === "undefined" ? undefined : navigator.locks,
      channel: lazyChannel,
      onSignedOut: authBridge.signedOut,
      onRemoteSignedOut: authBridge.remoteSignedOut,
      onSignedIn: authBridge.signedIn,
    });
    return { client: created, channel: lazyChannel, bridge: authBridge };
  });

  const identityLoop = useRef<{ cancel(): void } | null>(null);

  /**
   * Reads `/me`. Only a 401 makes the tab anonymous; any other failure keeps the current state
   * (`loading` on first load, unchanged after a `signed-in` broadcast) and asks again with backoff
   * until it is answered or the provider unmounts. `initial` skips the refresh when this browser
   * has no session hint.
   */
  const readIdentity = useCallback(
    (initial = false) => {
      identityLoop.current?.cancel();
      let cancelled = false;
      let timer: number | undefined;
      let failures = 0;
      const attempt = () => {
        const refresh = initial ? client.hasAuthHint() : true;
        client.me({ refresh }).then(
          (account) => {
            if (!cancelled) {
              setState({ status: "authenticated", account });
            }
          },
          (error: unknown) => {
            if (cancelled) {
              return;
            }
            const decision = decideIdentityFailure(error, failures);
            if (decision.action === "anonymous") {
              setState({ status: "anonymous" });
              return;
            }
            failures += 1;
            timer = window.setTimeout(attempt, decision.delayMs);
          },
        );
      };
      identityLoop.current = {
        cancel: () => {
          cancelled = true;
          window.clearTimeout(timer);
        },
      };
      attempt();
    },
    [client],
  );

  const endSession = useCallback(
    (redirect: boolean) => {
      purgeProfileSelection();
      client.clearAuthHint();
      identityLoop.current?.cancel();
      setState({ status: "anonymous" });
      setEpoch((value) => value + 1);
      if (redirect) {
        router.replace("/login");
      }
    },
    [client, router],
  );

  /**
   * Another tab reported a sign-out, but it may only have lost a refresh race against a login in
   * this very account. Quiesce first (the epoch bump aborts profile-scoped work, stops the player
   * and re-reads profiles), then let the backend decide: 401 ends the session, 200 keeps it.
   */
  const verifyRemoteSignedOut = useCallback(() => {
    setEpoch((value) => value + 1);
    void client.me({ refresh: false }).then(
      (account) => setState({ status: "authenticated", account }),
      (error: unknown) => {
        if (error instanceof CoreProblem && error.status === 401) {
          endSession(stateRef.current.status === "authenticated");
        }
        // Any other failure leaves identity unchanged; the next request decides.
      },
    );
  }, [client, endSession]);

  useEffect(() => {
    stateRef.current = state;
    bridge.assign({
      signedOut: () => endSession(stateRef.current.status === "authenticated"),
      remoteSignedOut: verifyRemoteSignedOut,
      signedIn: () => readIdentity(false),
    });
  }, [state, bridge, endSession, readIdentity, verifyRemoteSignedOut]);

  useEffect(() => {
    channel.open();
    readIdentity(true);
    return () => {
      identityLoop.current?.cancel();
      channel.shutdown();
    };
  }, [channel, readIdentity]);

  const login = useCallback(
    async (email: string, password: string) => {
      await client.login(email, password);
      const account = await client.me();
      identityLoop.current?.cancel();
      setLogoutError(null);
      setState({ status: "authenticated", account });
      client.announce("signed-in");
    },
    [client],
  );

  const logout = useCallback(async () => {
    try {
      await client.logout();
    } catch (error) {
      if (!(error instanceof CoreProblem && error.status === 401)) {
        setLogoutError(
          error instanceof CoreNetworkError
            ? "Không thể kết nối để đăng xuất. Vui lòng thử lại."
            : "Đăng xuất không thành công. Vui lòng thử lại.",
        );
        return;
      }
    }
    setLogoutError(null);
    endSession(true);
    client.announce("signed-out");
  }, [client, endSession]);

  const value = useMemo<SessionContextValue>(
    () => ({ client, state, epoch, logoutError, login, logout }),
    [client, state, epoch, logoutError, login, logout],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) {
    throw new Error("useSession must be used inside SessionProvider");
  }
  return value;
}

export function useCoreClient(): CoreClient {
  return useSession().client;
}
