import { createContext, useContext } from "react";
import type { Backend } from "./api/backend";
import type { Instrument, Session } from "./api/types";
import type { MarketStore } from "./marketdata/marketStore";

export type NoticeLevel = "info" | "success" | "error";

export interface Notice {
  id: number;
  time: number;
  level: NoticeLevel;
  text: string;
}

export interface Workspace {
  backend: Backend;
  store: MarketStore;
  session: Session;
  instruments: Instrument[];
  notify(level: NoticeLevel, text: string): void;
}

export const WorkspaceContext = createContext<Workspace | null>(null);

export function useWorkspace(): Workspace {
  const ws = useContext(WorkspaceContext);
  if (!ws) throw new Error("useWorkspace outside WorkspaceContext");
  return ws;
}

export function instrumentOf(ws: Workspace, symbol: string): Instrument {
  return ws.instruments.find((i) => i.symbol === symbol) ?? { symbol, name: symbol, tickSize: 0.01, lotSize: 1 };
}
