import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { describePlayerDenial, formatClock } from "./messages.ts";

describe("player messages", () => {
  test("maps the denial codes to their Vietnamese copy and falls back for unknown ones", () => {
    assert.equal(describePlayerDenial("PREMIUM_REQUIRED"), "Cần gói Premium");
    assert.equal(describePlayerDenial("EMAIL_VERIFICATION_REQUIRED"), "Cần xác minh email");
    assert.equal(describePlayerDenial("MEDIA_NOT_READY"), "Chưa sẵn sàng");
    assert.equal(describePlayerDenial("NOT_FOUND"), "Không tìm thấy nội dung");
    assert.equal(describePlayerDenial("ACCESS_DENIED"), "Bạn không có quyền xem nội dung này");
    assert.equal(describePlayerDenial(null), "Bạn không có quyền xem nội dung này");
  });

  test("formats resume positions", () => {
    assert.equal(formatClock(0), "0:00");
    assert.equal(formatClock(75_900), "1:15");
    assert.equal(formatClock(3_725_000), "1:02:05");
    assert.equal(formatClock(-5), "0:00");
  });
});
