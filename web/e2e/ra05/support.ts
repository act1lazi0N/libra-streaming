import { appendFileSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

// RA05 browser acceptance evidence. Run by infra/smoke/check-web-ra05.ps1 after the RA03 and RA04 suites, on the
// same stack. Nothing recorded here carries a password, cookie, token, identifier or response body.

export const EXPECTED_TESTS = 5;

const evidencePath = (() => {
  const value = process.env.LIBRA_RA05_EVIDENCE;
  if (!value) {
    throw new Error("LIBRA_RA05_EVIDENCE is not set; run infra/smoke/check-web-ra05.ps1");
  }
  return value;
})();
const runId = process.env.LIBRA_RA03_RUN_ID || undefined;
const ledgerPath = path.join(path.dirname(evidencePath), "ra05", "ledger.jsonl");

type Ledger = { test: number; line: string; mocked: string[] };

export function resetRa05Ledger() {
  mkdirSync(path.dirname(ledgerPath), { recursive: true });
  rmSync(ledgerPath, { force: true });
  rmSync(evidencePath, { force: true });
}

export function recordRa05Pass(test: number, line: string, mocked: string[] = []) {
  appendFileSync(ledgerPath, JSON.stringify({ test, line, mocked } satisfies Ledger) + "\n");
  console.log(`PASS: RA05 test ${test}: ${line}`);
}

export function writeRa05EvidenceWhenComplete(browserVersion: string) {
  if (!existsSync(ledgerPath)) {
    return false;
  }
  const entries: Ledger[] = readFileSync(ledgerPath, "utf-8").split("\n").filter(Boolean).map((l) => JSON.parse(l));
  if (entries.length !== EXPECTED_TESTS || new Set(entries.map((e) => e.test)).size !== EXPECTED_TESTS) {
    return false;
  }
  entries.sort((a, b) => a.test - b.test);
  const evidence = {
    result: "WEB_RA05_BROWSER_PASS",
    ...(runId ? { runId } : {}),
    checkedAt: new Date().toISOString(),
    browser: `Google Chrome ${browserVersion} (headless, Playwright)`,
    checks: entries.map((e) => `RA05 test ${e.test}: ${e.line}`),
    counts: { tests: entries.length },
    mockedSteps: entries.flatMap((e) => e.mocked),
    boundary: "Same disposable Compose as RA03 and RA04 (real web image, Core, Media, Analytics, Kafka, PostgreSQL " +
      "behind the repository Nginx), run after the RA03 suite whose playback feeds the lists; real Google Chrome; " +
      "every step is real except those listed under mockedSteps, which fulfil one list request with a canned body.",
  };
  mkdirSync(path.dirname(evidencePath), { recursive: true });
  writeFileSync(evidencePath, JSON.stringify(evidence, null, 2) + "\n", "utf-8");
  return true;
}
