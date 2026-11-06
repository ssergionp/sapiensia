import { defineConfig, devices } from "@playwright/test";

// End-to-end tests run against the real system: database, API and the built app.
// Locally: `just db`, `just dev-back` and `just dev-front` running, then `just e2e` (uses the Vite address).
// In CI: the Docker image runs on port 8080 and E2E_BASE_URL points to it.
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.e2e.ts",
  outputDir: ".tmp/e2e-results",
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // The e-mail jobs run in the scheduler (polling every second locally and in CI); a test that waits for two e-mails
  // also pays for a cold-started backend, so 30 s was not enough.
  timeout: 60_000,
  // A cold-started backend answers the first registration slowly (JIT, password hashing, breach check).
  expect: { timeout: 15_000 },
  reporter: [
    ["list"],
    ["html", { outputFolder: ".tmp/e2e-report", open: "never" }],
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:5173",
    trace: "retain-on-failure",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    {
      name: "celular",
      use: { ...devices["Pixel 7"] },
      // There is a single Administrator, so the panel e2e runs only on desktop.
      testIgnore: "**/admin-panel.e2e.ts",
    },
  ],
});
