"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { cardClass, errorClass, secondaryButtonClass } from "@/components/ui";
import { useProfiles } from "@/lib/session/profile-context";
import { useSession } from "@/lib/session/session-context";

export default function ProfilesPage() {
  const { state } = useSession();
  const { status, profiles, selected, select, reload } = useProfiles();
  const router = useRouter();

  useEffect(() => {
    if (state.status === "anonymous") {
      router.replace("/login");
    }
  }, [state.status, router]);

  return (
    <main className="mx-auto max-w-3xl px-4 py-12 sm:px-6">
      <h1 className="text-3xl font-semibold tracking-tight">Ai đang xem?</h1>
      <p className="mt-2 text-zinc-400">Chọn hồ sơ để tiếp tục. Lịch sử xem được lưu riêng theo từng hồ sơ.</p>
      {status === "loading" || state.status === "loading" ? (
        <p role="status" className="mt-8 text-zinc-400">
          Đang tải hồ sơ...
        </p>
      ) : null}
      {status === "error" ? (
        <div className="mt-8 space-y-3">
          <p role="alert" className={errorClass}>
            Không thể tải danh sách hồ sơ.
          </p>
          <button type="button" onClick={reload} className={secondaryButtonClass}>
            Thử lại
          </button>
        </div>
      ) : null}
      {status === "ready" ? (
        <ul className="mt-8 grid gap-4 sm:grid-cols-2">
          {profiles.map((profile) => {
            const current = selected?.id === profile.id;
            return (
              <li key={profile.id}>
                <button
                  type="button"
                  data-testid="profile-option"
                  data-profile-id={profile.id}
                  aria-pressed={current}
                  onClick={() => {
                    select(profile.id);
                    router.push("/");
                  }}
                  className={`${cardClass} flex w-full items-center justify-between p-5 text-left hover:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-300 ${
                    current ? "border-amber-400" : ""
                  }`}
                >
                  <span className="text-lg font-medium">{profile.name}</span>
                  {current ? (
                    <span data-testid="profile-current" className="text-xs uppercase tracking-wider text-amber-400">
                      Đang chọn
                    </span>
                  ) : profile.isDefault ? (
                    <span className="text-xs uppercase tracking-wider text-zinc-500">Mặc định</span>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}
    </main>
  );
}
