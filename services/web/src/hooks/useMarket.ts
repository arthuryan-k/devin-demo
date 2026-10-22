import { useSyncExternalStore } from "react";
import type { MarketState, MarketStore } from "../marketdata/marketStore";

export function useMarket(store: MarketStore, symbol: string): MarketState {
  return useSyncExternalStore(store.subscribe, () => store.get(symbol));
}
