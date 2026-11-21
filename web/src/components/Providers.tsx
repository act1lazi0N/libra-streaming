"use client";

import type { ReactNode } from "react";
import { ProfileProvider } from "@/lib/session/profile-context";
import { SessionProvider } from "@/lib/session/session-context";

export function Providers({ children }: { children: ReactNode }) {
  return (
    <SessionProvider>
      <ProfileProvider>{children}</ProfileProvider>
    </SessionProvider>
  );
}
