/** Vietnamese copy for the code that stopped playback (admission denial or a later 403/404/401). */
export function describePlayerDenial(code: string | null): string {
  switch (code) {
    case "PREMIUM_REQUIRED":
      return "Cần gói Premium";
    case "EMAIL_VERIFICATION_REQUIRED":
      return "Cần xác minh email";
    case "MEDIA_NOT_READY":
      return "Chưa sẵn sàng";
    case "NOT_PLAYABLE_CONTENT":
      return "Nội dung này không thể phát trực tiếp";
    case "NOT_FOUND":
      return "Không tìm thấy nội dung";
    case "AUTHENTICATION_REQUIRED":
      return "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.";
    default:
      return "Bạn không có quyền xem nội dung này";
  }
}

/** m:ss or h:mm:ss for a position in milliseconds. */
export function formatClock(positionMs: number): string {
  const total = Math.max(Math.floor(positionMs / 1000), 0);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value: number) => String(value).padStart(2, "0");
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}
