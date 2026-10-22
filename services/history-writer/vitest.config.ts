import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    globalSetup: ["test/globalSetup.ts"],
    testTimeout: 60_000,
    hookTimeout: 240_000,
  },
});
