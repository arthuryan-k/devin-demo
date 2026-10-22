import type { Backend } from "./backend";
import { HttpBackend } from "./http";
import { MockBackend } from "./mock/mockBackend";

export function createBackend(): Backend {
  if (import.meta.env.VITE_BACKEND === "mock") return new MockBackend();
  return new HttpBackend({
    apiBase: import.meta.env.VITE_API_BASE,
    marketDataUrl: import.meta.env.VITE_MARKET_DATA_URL,
  });
}
