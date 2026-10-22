import { TickConverter } from "./ticks.js";

/** Trading parameters for one listed symbol (from the reference-data service). */
export interface Instrument {
  symbol: string;
  name?: string;
  tickSize: string;
  lotSize: number;
}

export class SymbolDirectory {
  private readonly bySymbol = new Map<string, { instrument: Instrument; ticks: TickConverter }>();

  constructor(instruments: Instrument[]) {
    for (const instrument of instruments) {
      this.bySymbol.set(instrument.symbol, { instrument, ticks: new TickConverter(instrument.tickSize) });
    }
  }

  symbols(): string[] {
    return [...this.bySymbol.keys()];
  }

  instruments(): Instrument[] {
    return [...this.bySymbol.values()].map((v) => v.instrument);
  }

  get(symbol: string): { instrument: Instrument; ticks: TickConverter } | undefined {
    return this.bySymbol.get(symbol);
  }
}

export const DEFAULT_INSTRUMENTS: Instrument[] = ["ACME", "GLOBX", "INITD", "NVTA"].map((symbol) => ({
  symbol,
  tickSize: "0.01",
  lotSize: 1,
}));

/** Loads instruments from the reference-data service, e.g. {@code GET /symbols}. */
export async function fetchInstruments(url: string, fetchImpl: typeof fetch = fetch): Promise<Instrument[]> {
  const res = await fetchImpl(url);
  if (!res.ok) {
    throw new Error(`reference data ${url} returned ${res.status}`);
  }
  const body = (await res.json()) as unknown;
  const list = (Array.isArray(body) ? body : (body as { symbols?: unknown[] }).symbols) ?? [];
  return list.map((raw) => {
    const r = raw as Record<string, unknown>;
    return {
      symbol: String(r.symbol),
      name: r.name === undefined ? undefined : String(r.name),
      tickSize: String(r.tickSize ?? r.tick_size ?? "0.01"),
      lotSize: Number(r.lotSize ?? r.lot_size ?? 1),
    };
  });
}
