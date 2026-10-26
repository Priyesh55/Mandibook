import { defineConfig } from "@playwright/test";
import { WEB_PORT, WEB_URL } from "./e2e/ports";

// Runs against a DISPOSABLE stack (scripts/e2e/stack.js: its own PostgreSQL container and the real backend on :8091), never the
// development database. global-setup provisions synthetic owners through the real invitation path. See docs/RUNNING.md.
export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/global-setup.ts",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  outputDir: "test-results",
  use: {
    baseURL: WEB_URL,
    viewport: { width: 1920, height: 1080 },   // the reference screen of the product
    channel: process.env.PW_CHANNEL ?? "chrome", // uses the installed Chrome; no browser download needed
    screenshot: "on",
    trace: "retain-on-failure",
  },
  // Dedicated server: own build folder (.next-e2e, built by `npm run build:e2e`) and own port, never reusing
  // a server that might be running an older build.
  webServer: {
    command: `npx next start -p ${WEB_PORT} -H 127.0.0.1`,
    url: WEB_URL,
    reuseExistingServer: false,
    env: { NEXT_DIST_DIR: process.env.NEXT_DIST_DIR ?? ".next-e2e" },
    timeout: 60_000,
  },
});
