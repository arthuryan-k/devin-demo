/// <reference types="vitest" />
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// Modes: development (live gateway, proxied), mock (in-browser fake backend), pages (mock, GitHub Pages base path).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const gateway = env.GATEWAY_URL ?? "http://localhost:8080";
  const marketData = env.MARKET_DATA_URL ?? gateway;
  return {
    base: mode === "pages" ? (env.PAGES_BASE ?? "/devin-demo/") : "/",
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        "/api": { target: gateway, changeOrigin: true },
        "/ws": { target: marketData.replace(/^http/, "ws"), ws: true, changeOrigin: true },
      },
    },
    build: { outDir: mode === "pages" ? "dist-pages" : "dist", sourcemap: true },
    test: {
      environment: "jsdom",
      globals: true,
      setupFiles: ["tests/setup.ts"],
      include: ["tests/**/*.test.{ts,tsx}"],
    },
  };
});
