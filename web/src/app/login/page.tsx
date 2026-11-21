"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { FormField } from "@/components/FormField";
import { errorClass, primaryButtonClass } from "@/components/ui";
import { CoreNetworkError, CoreProblem } from "@/lib/core/errors";
import { useSession } from "@/lib/session/session-context";

function loginMessage(error: unknown): string {
  if (error instanceof CoreNetworkError) {
    return "Không thể kết nối tới máy chủ. Vui lòng thử lại.";
  }
  if (error instanceof CoreProblem && error.status === 401) {
    return "Email hoặc mật khẩu không đúng.";
  }
  if (error instanceof CoreProblem && error.status === 429) {
    return "Bạn thử đăng nhập quá nhiều lần. Vui lòng thử lại sau.";
  }
  return "Đăng nhập không thành công. Vui lòng thử lại.";
}

export default function LoginPage() {
  const { state, login } = useSession();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (state.status === "authenticated") {
      router.replace("/");
    }
  }, [state.status, router]);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await login(email, password);
      setPassword("");
    } catch (failure) {
      setError(loginMessage(failure));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto max-w-md px-4 py-12 sm:px-6">
      <h1 className="text-3xl font-semibold tracking-tight">Đăng nhập</h1>
      <form onSubmit={onSubmit} className="mt-8 space-y-4" noValidate>
        <FormField
          id="login-email"
          testId="login-email"
          label="Email"
          type="email"
          autoComplete="username"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <FormField
          id="login-password"
          testId="login-password"
          label="Mật khẩu"
          type="password"
          autoComplete="current-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        {error ? (
          <p role="alert" data-testid="login-error" className={errorClass}>
            {error}
          </p>
        ) : null}
        <button
          type="submit"
          data-testid="login-submit"
          disabled={submitting}
          className={`${primaryButtonClass} w-full`}
        >
          {submitting ? "Đang đăng nhập..." : "Đăng nhập"}
        </button>
      </form>
      <p className="mt-6 text-sm text-zinc-400">
        Chưa có tài khoản?{" "}
        <Link href="/register" className="text-amber-400 hover:underline">
          Đăng ký
        </Link>
      </p>
    </main>
  );
}
