"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import { FormField } from "@/components/FormField";
import { errorClass, primaryButtonClass } from "@/components/ui";
import { CoreNetworkError, CoreProblem } from "@/lib/core/errors";
import { useCoreClient } from "@/lib/session/session-context";

function registerMessage(error: unknown): string {
  if (error instanceof CoreNetworkError) {
    return "Không thể kết nối tới máy chủ. Vui lòng thử lại.";
  }
  if (error instanceof CoreProblem && (error.status === 400 || error.status === 422)) {
    return "Thông tin chưa hợp lệ. Hãy kiểm tra email, tên hiển thị và mật khẩu.";
  }
  if (error instanceof CoreProblem && error.status === 429) {
    return "Bạn thao tác quá nhanh. Vui lòng thử lại sau.";
  }
  return "Đăng ký không thành công. Vui lòng thử lại.";
}

export default function RegisterPage() {
  const client = useCoreClient();
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await client.request("POST", "/auth/register", { auth: false, body: { email, displayName, password } });
      setPassword("");
      setDone(true);
    } catch (failure) {
      setError(registerMessage(failure));
    } finally {
      setSubmitting(false);
    }
  }

  if (done) {
    return (
      <main className="mx-auto max-w-md px-4 py-12 sm:px-6">
        <h1 className="text-3xl font-semibold tracking-tight">Kiểm tra email của bạn</h1>
        <p data-testid="register-done" className="mt-6 text-zinc-300">
          Chúng tôi đã gửi liên kết xác minh tới địa chỉ email bạn đã đăng ký. Hãy xác minh email rồi đăng nhập.
        </p>
        <p className="mt-6 text-sm">
          <Link href="/login" className="text-amber-400 hover:underline">
            Tới trang đăng nhập
          </Link>
        </p>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-md px-4 py-12 sm:px-6">
      <h1 className="text-3xl font-semibold tracking-tight">Đăng ký</h1>
      <form onSubmit={onSubmit} className="mt-8 space-y-4" noValidate>
        <FormField
          id="register-email"
          testId="register-email"
          label="Email"
          type="email"
          autoComplete="email"
          required
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <FormField
          id="register-name"
          testId="register-name"
          label="Tên hiển thị"
          type="text"
          autoComplete="name"
          required
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
        />
        <FormField
          id="register-password"
          testId="register-password"
          label="Mật khẩu"
          type="password"
          autoComplete="new-password"
          required
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        {error ? (
          <p role="alert" data-testid="register-error" className={errorClass}>
            {error}
          </p>
        ) : null}
        <button
          type="submit"
          data-testid="register-submit"
          disabled={submitting}
          className={`${primaryButtonClass} w-full`}
        >
          {submitting ? "Đang đăng ký..." : "Đăng ký"}
        </button>
      </form>
      <p className="mt-6 text-sm text-zinc-400">
        Đã có tài khoản?{" "}
        <Link href="/login" className="text-amber-400 hover:underline">
          Đăng nhập
        </Link>
      </p>
    </main>
  );
}
