import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { CoreNetworkError, CoreProblem } from "../core/errors.ts";
import { decideIdentityFailure, identityBackoffMs } from "./identity.ts";

describe("identity failure decisions", () => {
  test("only a 401 problem makes the tab anonymous", () => {
    assert.deepEqual(decideIdentityFailure(new CoreProblem(401, "AUTHENTICATION_REQUIRED", null, null), 0), {
      action: "anonymous",
    });
    assert.deepEqual(decideIdentityFailure(new CoreProblem(401, null, null, null), 5), { action: "anonymous" });
  });

  test("network errors, 5xx and other failures keep the state and retry", () => {
    for (const error of [
      new CoreNetworkError(),
      new CoreProblem(503, "UNAVAILABLE", null, null),
      new CoreProblem(500, null, null, null),
      new CoreProblem(403, "ACCESS_DENIED", null, null),
      new TypeError("boom"),
    ]) {
      assert.deepEqual(decideIdentityFailure(error, 0), { action: "retry", delayMs: 2000 });
    }
  });

  test("backoff is 2 s, 4 s, 8 s, 16 s then capped at 30 s", () => {
    const delays = [0, 1, 2, 3, 4, 5, 20].map((failures) => {
      const decision = decideIdentityFailure(new CoreNetworkError(), failures);
      return decision.action === "retry" ? decision.delayMs : -1;
    });
    assert.deepEqual(delays, [2000, 4000, 8000, 16_000, 30_000, 30_000, 30_000]);
    assert.equal(identityBackoffMs(1), 2000);
    assert.equal(identityBackoffMs(0), 2000);
  });
});
