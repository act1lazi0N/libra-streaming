import { CoreProblem } from "../core/errors.ts";

export type Playability =
  | { kind: "denied"; code: string; message: string }
  | { kind: "error"; message: string };

/**
 * Maps a failed entitlement check to what the viewer sees. A 200 means playable; the access tier
 * badge is purely informational and never decides this.
 */
export function describeDenial(error: unknown): Playability {
  if (error instanceof CoreProblem) {
    if (error.status === 403 && error.code === "PREMIUM_REQUIRED") {
      return { kind: "denied", code: "PREMIUM_REQUIRED", message: "Cần gói Premium" };
    }
    if (error.status === 403 && error.code === "EMAIL_VERIFICATION_REQUIRED") {
      return { kind: "denied", code: "EMAIL_VERIFICATION_REQUIRED", message: "Cần xác minh email" };
    }
    if (error.status === 409 && error.code === "MEDIA_NOT_READY") {
      return { kind: "denied", code: "MEDIA_NOT_READY", message: "Chưa sẵn sàng" };
    }
    if (error.status === 409 && error.code === "NOT_PLAYABLE_CONTENT") {
      return { kind: "denied", code: "NOT_PLAYABLE_CONTENT", message: "Nội dung này không thể phát trực tiếp" };
    }
    if (error.status === 404) {
      return { kind: "denied", code: "NOT_FOUND", message: "Không tìm thấy nội dung" };
    }
    if (error.status === 403) {
      return { kind: "denied", code: error.code ?? "FORBIDDEN", message: "Bạn không có quyền xem nội dung này" };
    }
  }
  return { kind: "error", message: "Không thể kiểm tra quyền xem. Vui lòng thử lại." };
}
