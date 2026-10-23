import { render } from "@testing-library/react";
import type { ReactNode } from "react";
import type { Backend, MarketFeedHandlers } from "../src/api/backend";
import type { Instrument, Session } from "../src/api/types";
import { MockBackend } from "../src/api/mock/mockBackend";
import { INSTRUMENTS } from "../src/api/mock/fixtures";
import { WorkspaceContext, type Workspace } from "../src/context";
import { MarketStore } from "../src/marketdata/marketStore";
import type { MarketDataMessage } from "../src/marketdata/wire";

export const SESSION: Session = { token: "t", username: "demo", participantId: 1001, accountId: "DU1" };

/** Wraps a backend so tests can push market-data messages by hand (a fake WebSocket feed). */
export function withManualFeed(
  base: Backend,
): Backend & { push(symbol: string, msg: MarketDataMessage): void; resyncs: string[] } {
  const handlers = new Map<string, MarketFeedHandlers>();
  const resyncs: string[] = [];
  return Object.assign(Object.create(base) as Backend, {
    priceHistory: async () => [],
    subscribeMarket(symbol: string, h: MarketFeedHandlers) {
      handlers.set(symbol, h);
      h.onStatus("connecting");
      return { close: () => handlers.delete(symbol), resync: () => resyncs.push(symbol) };
    },
    push(symbol: string, msg: MarketDataMessage) {
      const h = handlers.get(symbol)!;
      if (msg.type === "snapshot") h.onStatus("live");
      h.onMessage(msg);
    },
    resyncs,
  });
}

export function quietMock(): MockBackend {
  return new MockBackend({ tickMs: 0, latencyMs: 0, seed: 7 });
}

export function renderInWorkspace(ui: ReactNode, backend: Backend, instruments: Instrument[] = INSTRUMENTS) {
  const store = new MarketStore(backend, instruments);
  store.start();
  const notices: string[] = [];
  const ws: Workspace = { backend, store, session: SESSION, instruments, notify: (_l, t) => notices.push(t) };
  const result = render(<WorkspaceContext.Provider value={ws}>{ui}</WorkspaceContext.Provider>);
  return { ...result, store, notices };
}
