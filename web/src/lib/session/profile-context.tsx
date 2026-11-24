"use client";

import { usePathname, useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { Profile } from "../core/types";
import { PROFILE_STORAGE_PREFIX, useSession } from "./session-context";

export interface ProfileContextValue {
  status: "none" | "loading" | "ready" | "error";
  profiles: Profile[];
  selected: Profile | null;
  select(id: string): void;
  /** Increments on every profile switch and on every sign-out. */
  generation: number;
  reload(): void;
}

const ProfileContext = createContext<ProfileContextValue | null>(null);

interface Loaded {
  accountId: string;
  nonce: number;
  profiles: Profile[];
  selectedId: string | null;
}

function storageKey(accountId: string): string {
  return `${PROFILE_STORAGE_PREFIX}${accountId}`;
}

function readStoredSelection(accountId: string): string | null {
  try {
    return window.sessionStorage.getItem(storageKey(accountId));
  } catch {
    return null;
  }
}

export function ProfileProvider({ children }: { children: ReactNode }) {
  const { client, state, epoch } = useSession();
  const router = useRouter();
  const pathname = usePathname();
  const accountId = state.status === "authenticated" ? state.account.id : null;
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [failed, setFailed] = useState<{ accountId: string; nonce: number } | null>(null);
  const [switches, setSwitches] = useState(0);
  const [nonce, setNonce] = useState(0);

  useEffect(() => {
    if (!accountId) {
      return;
    }
    const controller = new AbortController();
    client
      .request<Profile[]>("GET", "/profiles", { signal: controller.signal })
      .then((profiles) => {
        const stored = readStoredSelection(accountId);
        const valid = profiles.some((profile) => profile.id === stored);
        setLoaded({ accountId, nonce, profiles, selectedId: valid ? stored : null });
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setFailed({ accountId, nonce });
        }
      });
    return () => controller.abort();
  }, [client, accountId, nonce, epoch]);

  const current = loaded && loaded.accountId === accountId && loaded.nonce === nonce ? loaded : null;
  const hasFailed = failed !== null && failed.accountId === accountId && failed.nonce === nonce && !current;
  const profiles = useMemo(() => current?.profiles ?? [], [current]);
  const selected = profiles.find((profile) => profile.id === current?.selectedId) ?? null;

  let status: ProfileContextValue["status"] = "none";
  if (accountId) {
    status = current ? "ready" : hasFailed ? "error" : "loading";
  }

  const select = useCallback(
    (id: string) => {
      if (!accountId || !current || !current.profiles.some((profile) => profile.id === id)) {
        return;
      }
      try {
        window.sessionStorage.setItem(storageKey(accountId), id);
      } catch {
        // Selection is kept in memory when sessionStorage is unavailable.
      }
      if (current.selectedId !== id) {
        setSwitches((value) => value + 1);
      }
      setLoaded({ ...current, selectedId: id });
    },
    [accountId, current],
  );

  const reload = useCallback(() => setNonce((value) => value + 1), []);

  useEffect(() => {
    // Administration pages act for the account, not a viewing profile (RA04.3): they never require one.
    if (status === "ready" && !selected && pathname !== "/profiles" && !pathname.startsWith("/admin/")) {
      router.replace("/profiles");
    }
  }, [status, selected, pathname, router]);

  const value = useMemo<ProfileContextValue>(
    () => ({ status, profiles, selected, select, generation: epoch + switches, reload }),
    [status, profiles, selected, select, epoch, switches, reload],
  );

  return <ProfileContext.Provider value={value}>{children}</ProfileContext.Provider>;
}

export function useProfiles(): ProfileContextValue {
  const value = useContext(ProfileContext);
  if (!value) {
    throw new Error("useProfiles must be used inside ProfileProvider");
  }
  return value;
}
