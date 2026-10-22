import { defineConfig, devices } from "@playwright/test";

// Runs against the static GitHub Pages build (mock backend) served by `vite preview`.
export default defineConfig({
  testDir: "e2e",
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? "github" : "list",
  use: { baseURL: "http://localhost:4174/devin-demo/", trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1600, height: 1000 } } }],
  webServer: {
    command: "npm run build:pages && npx vite preview --mode pages --outDir dist-pages --port 4174 --strictPort",
    url: "http://localhost:4174/devin-demo/",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
