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
    { name: "celular", use: { ...devices["Pixel 7"] } },
  ],
});
