import { defineConfig } from "@playwright/test";

// Browser acceptance runs only through infra/smoke/check-media-m29.ps1 -Milestone 33, which starts the disposable
// stack and passes its addresses. No trace, video, screenshot or HAR is recorded: they would hold signed URLs and
// cookies.
export default defineConfig({
  testDir: "./e2e",
  outputDir: "../target/verification/m33-playwright",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 10 * 60_000,
  reporter: [["list"]],
  use: {
    // The installed Google Chrome: Playwright's own Chromium build has no H.264/AAC decoder.
    channel: "chrome",
    headless: true,
    trace: "off",
    video: "off",
    screenshot: "off",
    acceptDownloads: false,
    serviceWorkers: "block",
  },
});
