import type { Instrument } from "../types";

/** Reference data mirrored from the reference-data service's seed (Phase 1 tickers). */
export const INSTRUMENTS: Instrument[] = [
  { symbol: "ACME", name: "Acme Corp", tickSize: 0.01, lotSize: 1 },
  { symbol: "GLOBX", name: "Globex Industries", tickSize: 0.01, lotSize: 1 },
  { symbol: "INITD", name: "Initech Digital", tickSize: 0.01, lotSize: 1 },
  { symbol: "NVTA", name: "Novata Biosciences", tickSize: 0.01, lotSize: 1 },
];

/** Opening reference prices (ticks of 0.01) and per-step volatility for the synthetic market. */
export const MARKET_PARAMS: Record<string, { open: number; prevClose: number; vol: number; spread: number }> = {
  ACME: { open: 10_012, prevClose: 9_948, vol: 0.00045, spread: 1 },
  GLOBX: { open: 4_251, prevClose: 4_302, vol: 0.0006, spread: 1 },
  INITD: { open: 1_874, prevClose: 1_861, vol: 0.0008, spread: 1 },
  NVTA: { open: 23_140, prevClose: 22_795, vol: 0.0007, spread: 3 },
};

export const DEMO_USER = { username: "demo", password: "demo1234" };
export const STARTING_CASH_CENTS = 10_000_000; // 100,000.00

/** Positions the demo account carries into the session, with their average cost in ticks. */
export const DEMO_POSITIONS: { symbol: string; qty: number; avgCost: number }[] = [
  { symbol: "ACME", qty: 200, avgCost: 9_840 },
  { symbol: "GLOBX", qty: 500, avgCost: 4_310 },
  { symbol: "NVTA", qty: 25, avgCost: 22_580 },
];
