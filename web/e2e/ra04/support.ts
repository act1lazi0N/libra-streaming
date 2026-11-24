import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

// RA04 browser acceptance evidence. Run by infra/smoke/check-web-ra04.ps1 after the RA03 suite, on the same stack.
// Nothing recorded here carries a password, cookie, token, identifier or response body.

export const EXPECTED_TESTS = 6;

const evidencePath = (() => {
  const value = process.env.LIBRA_RA04_EVIDENCE;
  if (!value) {
    throw new Error("LIBRA_RA04_EVIDENCE is not set; run infra/smoke/check-web-ra04.ps1");
  }
  return value;
})();
const runId = process.env.LIBRA_RA03_RUN_ID || undefined;
const ledgerPath = path.join(path.dirname(evidencePath), "ra04", "ledger.jsonl");

type Ledger = { test: number; line: string; mocked: string[] };

export function resetRa04Ledger() {
  mkdirSync(path.dirname(ledgerPath), { recursive: true });
  rmSync(ledgerPath, { force: true });
  rmSync(evidencePath, { force: true });
}

export function recordRa04Pass(test: number, line: string, mocked: string[] = []) {
  appendFileSync(ledgerPath, JSON.stringify({ test, line, mocked } satisfies Ledger) + "\n");
  console.log(`PASS: RA04 test ${test}: ${line}`);
}

export function writeRa04EvidenceWhenComplete(browserVersion: string) {
  if (!existsSync(ledgerPath)) {
    return false;
  }
  const entries: Ledger[] = readFileSync(ledgerPath, "utf-8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  if (entries.length !== EXPECTED_TESTS || new Set(entries.map((e) => e.test)).size !== EXPECTED_TESTS) {
    return false;
  }
  entries.sort((a, b) => a.test - b.test);
  const evidence = {
    result: "WEB_RA04_BROWSER_PASS",
    ...(runId ? { runId } : {}),
    checkedAt: new Date().toISOString(),
    browser: `Google Chrome ${browserVersion} (headless, Playwright)`,
    checks: entries.map((e) => `RA04 test ${e.test}: ${e.line}`),
    counts: { tests: entries.length },
    mockedSteps: entries.flatMap((e) => e.mocked),
    boundary: "Same disposable Compose as RA03 (real web image, Core, Media, Analytics, Kafka, PostgreSQL behind the " +
      "repository Nginx), run right after the RA03 suite whose qualified view it reads; real Google Chrome; every step " +
      "is real except those listed under mockedSteps, which fulfil the dashboard's report request with a canned body.",
  };
  mkdirSync(path.dirname(evidencePath), { recursive: true });
  writeFileSync(evidencePath, JSON.stringify(evidence, null, 2) + "\n", "utf-8");
  return true;
}
