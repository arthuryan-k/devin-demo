/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_BACKEND?: "mock" | "live";
  readonly VITE_API_BASE?: string;
  readonly VITE_MARKET_DATA_URL?: string;
}
