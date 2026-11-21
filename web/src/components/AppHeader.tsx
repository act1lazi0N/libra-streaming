"use client";

import Link from "next/link";
import { useProfiles } from "@/lib/session/profile-context";
import { useSession } from "@/lib/session/session-context";
import { secondaryButtonClass } from "./ui";

export function AppHeader() {
  const { state, logout, logoutError } = useSession();
  const { selected } = useProfiles();

  return (
    <>
      <header data-testid="app-header" className="border-b border-zinc-800 bg-zinc-950/80 backdrop-blur">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-6">
          <Link href="/" className="text-sm font-semibold uppercase tracking-[0.3em] text-amber-400">
            LIBRA
          </Link>
          <nav aria-label="Tài khoản" className="flex flex-wrap items-center gap-3 text-sm">
            {state.status === "authenticated" ? (
              <>
                <span className="text-zinc-400" data-testid="header-account">
                  {state.account.email}
                </span>
                {selected ? (
                  <span data-testid="header-profile" className="rounded-full bg-zinc-800 px-3 py-1 text-zinc-100">
                    {selected.name}
                  </span>
                ) : null}
                <Link href="/profiles" data-testid="header-switch-profile" className="text-amber-400 hover:underline">
                  Đổi hồ sơ
                </Link>
                <button
                  type="button"
                  data-testid="header-logout"
                  onClick={() => void logout()}
                  className={secondaryButtonClass}
                >
                  Đăng xuất
                </button>
              </>
            ) : state.status === "anonymous" ? (
              <>
                <Link href="/login" data-testid="header-login" className="text-amber-400 hover:underline">
                  Đăng nhập
                </Link>
                <Link href="/register" className="text-zinc-300 hover:underline">
                  Đăng ký
                </Link>
              </>
            ) : null}
          </nav>
        </div>
      </header>
      {logoutError ? (
        <p
          role="alert"
          data-testid="logout-error"
          className="mx-auto max-w-6xl px-4 py-2 text-sm text-red-300 sm:px-6"
        >
          {logoutError}
        </p>
      ) : null}
      {state.status === "authenticated" && !state.account.emailVerified ? (
        <div
          role="status"
          data-testid="email-unverified"
          className="border-b border-amber-900 bg-amber-950/50 px-4 py-2 text-center text-sm text-amber-200"
        >
          Email của bạn chưa được xác minh. Hãy xác minh email để có thể xem nội dung.
        </div>
      ) : null}
    </>
  );
}
